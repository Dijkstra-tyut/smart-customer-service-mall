package com.supermarket.agent;

import com.supermarket.entity.User;
import com.supermarket.service.OrderService;
import com.supermarket.service.ProductSearchService;
import com.supermarket.service.ProductViewService;
import com.supermarket.service.UserService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.supermarket.agent.AgentJson.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentRuntimeServiceTest {
    private JdbcTemplate jdbc;
    private AgentStore store;
    private AgentRuntimeService runtime;
    private Map<String, Object> request;
    private AgentRun run;

    @BeforeEach void setUp() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:runtime_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE cs_session(id BIGINT PRIMARY KEY,user_id INT NOT NULL,is_deleted INT DEFAULT 0,title VARCHAR(100),create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,update_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE cs_message(id BIGINT AUTO_INCREMENT PRIMARY KEY,session_id BIGINT,type VARCHAR(16),content LONGTEXT,create_time TIMESTAMP)");
        new ResourceDatabasePopulator(new FileSystemResource("../db/migrations/20261110_customer_agent.sql")).execute(source);
        new ResourceDatabasePopulator(new FileSystemResource("../db/migrations/20261115_customer_memory.sql")).execute(source);
        new ResourceDatabasePopulator(new FileSystemResource("../db/migrations/20261113_cs_live_chat.sql")).execute(source);
        store = new AgentStore(jdbc, new DataSourceTransactionManager(source));
        UserService users = mock(UserService.class);
        when(users.getById(1)).thenReturn(new User().setId(1).setName("小林"));
        var data = new AgentDataService(store, users, mock(OrderService.class), mock(ProductViewService.class), mock(ProductSearchService.class), new AgentMemoryService(store));
        runtime = new AgentRuntimeService(store, data, WebClient.create("http://127.0.0.1:1"), "test-service-key", 3600, 300);
        jdbc.update("INSERT INTO cs_session(id,user_id,title) VALUES (11,1,'新对话')");
        request = runtime.beginChat(11, "我要人工处理", 1);
        run = store.run((String) request.get("run_id"));
    }

    private Map<String, Object> proposal(String id, String reason) {
        var request = map("task_id", id, "run_id", run.id(), "revision", run.revision(), "reason", reason,
                "draft", "待人工确认的草稿", "review", Map.of(), "evidence", List.of());
        if ("sensitive_action".equals(reason)) request.put("action_proposal", map("kind", "order_cancel", "arguments", map("order_id", 101)));
        return request;
    }

    private void pending(String id, String reason) {
        runtime.prepareHuman(run, proposal(id, reason));
        runtime.activateHuman(run, id, map("version", 1));
    }

    @Test void credentialsAreOpaqueHashedAndNeverExposedInPortalStatus() {
        String token = (String) request.get("run_token");
        assertThat(token).hasSizeGreaterThanOrEqualTo(40);
        assertThat(store.authenticate(token).id()).isEqualTo(run.id());
        assertThat(store.authenticate("wrong")).isNull();
        assertThat(jdbc.queryForObject("SELECT token_hash FROM cs_agent_run WHERE id=?", String.class, run.id())).isNotEqualTo(token);
        assertThat(write(runtime.portalRun(11, 1))).doesNotContain(token, "token_hash", "run_token");
        assertThatThrownBy(() -> runtime.portalRun(11, 2)).isInstanceOf(AgentException.class);
    }

    @Test void preparingTaskCannotBeDecidedBeforeDurableActivation() {
        runtime.prepareHuman(run, proposal("h1", "user_requested"));
        assertThat(runtime.task("h1")).containsEntry("status", "preparing");
        assertThatThrownBy(() -> runtime.decide("h1", map("decisionId", "d1", "version", 1, "action", "send_reply", "reply", "人工回复"), 9))
                .isInstanceOf(AgentException.class);
        runtime.activateHuman(run, "h1", map("version", 1));
        assertThat(runtime.task("h1")).containsEntry("status", "pending");
        assertThat(store.run(run.id()).status()).isEqualTo("waiting_human");
    }

    @Test void prepareAndActivationAreIdempotentButPayloadCollisionIsRejected() {
        Map<String, Object> task = proposal("h1", "user_requested");
        assertThat(runtime.prepareHuman(run, task)).isEqualTo(runtime.prepareHuman(run, task));
        var changed = proposal("h1", "user_requested"); changed.put("draft", "不同草稿");
        assertThatThrownBy(() -> runtime.prepareHuman(run, changed)).isInstanceOf(AgentException.class);
        runtime.activateHuman(run, "h1", map("version", 1));
        runtime.activateHuman(run, "h1", map("version", 1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cs_human_task", Integer.class)).isEqualTo(1);
    }

    @Test void supplementsInvalidateActionAndDoNotDispatchAnotherRun() {
        pending("h1", "sensitive_action");
        var result = runtime.beginChat(11, "先别取消，我补充一个情况", 1);
        assertThat(result).containsEntry("supplement", true).containsEntry("run_id", run.id());
        assertThat(runtime.task("h1")).containsEntry("version", 2).containsEntry("actionStale", true);
        assertThatThrownBy(() -> runtime.decide("h1", map("decisionId", "old", "version", 1, "action", "approve_action"), 9))
                .isInstanceOf(AgentException.class);
        assertThatThrownBy(() -> runtime.decide("h1", map("decisionId", "new", "version", 2, "action", "approve_action"), 9))
                .isInstanceOf(AgentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cs_agent_run", Integer.class)).isEqualTo(1);
    }

    @Test void concurrentStartsCannotCreateTwoActiveRuns() throws Exception {
        // End setup run; two independent requests now race on the same session row.
        runtime.cancel(11, 1);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> { start.await(); return tryStart(); });
            var b = pool.submit(() -> { start.await(); return tryStart(); });
            start.countDown();
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cs_agent_run WHERE active_session_id=11", Integer.class)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    private boolean tryStart() {
        try { runtime.beginChat(11, "另一个问题", 1); return true; }
        catch (AgentException error) { return false; }
    }

    @Test void decisionIsPersistentIdempotentAndPreservesExactHumanText() {
        pending("h1", "user_requested");
        String reply = "  人工确认\n第二行  ";
        var body = map("decisionId", "d1", "version", 1, "action", "send_reply", "reply", reply);
        assertThat(runtime.decide("h1", body, 9)).containsEntry("status", "decision_recorded");
        runtime.decide("h1", body, 9);
        assertThat(object(runtime.task("h1").get("decision"))).containsEntry("reply", reply);
        assertThat(runtime.dueDecisions()).containsExactly("h1");
        assertThatThrownBy(() -> runtime.decide("h1", map("decisionId", "d2", "version", 1, "action", "send_reply", "reply", "其他回复"), 8))
                .isInstanceOf(AgentException.class);
        var afterRestart = new AgentRuntimeService(store, mock(AgentDataService.class), WebClient.create(), "test-service-key", 3600, 300);
        assertThat(afterRestart.dueDecisions()).containsExactly("h1");
    }

    @Test void unsafeHumanReplyIsRejectedBeforeTheDecisionBecomesImmutable() {
        pending("h1", "user_requested");
        for (String unsafe : List.of("请联系 support@example.com", "手机号：13812345678", "证件号：11010119900307123X")) {
            for (String action : List.of("send_reply", "request_info")) {
                assertThatThrownBy(() -> runtime.decide("h1", map("decisionId", "still-editable", "version", 1,
                        "action", action, "reply", unsafe), 9)).isInstanceOf(AgentException.class)
                        .hasMessageContaining("未脱敏");
                assertThat(runtime.task("h1")).containsEntry("status", "pending").containsEntry("decision", null);
                assertThat(jdbc.queryForObject("SELECT decision_id FROM cs_human_task WHERE id='h1'", String.class)).isNull();
                assertThat(runtime.dueDecisions()).isEmpty();
            }
        }
        // A validation error leaves the editor free to correct and submit the same action.
        String corrected = "请使用商城客服入口继续联系。";
        runtime.decide("h1", map("decisionId", "still-editable", "version", 1,
                "action", "send_reply", "reply", corrected), 9);
        assertThat(runtime.task("h1")).containsEntry("status", "decision_recorded");
        assertThat(object(runtime.task("h1").get("decision"))).containsEntry("reply", corrected);
    }

    @Test void outcomePersistsWithoutAnySseSubscriberAndDuplicateCallbackDoesNotDuplicateMessage() {
        var outcome = map("run_id", run.id(), "expected_revision", 1, "status", "completed", "message_id", "stable-final", "reply", "已确认的正式回复");
        runtime.outcome(run, run.id(), outcome);
        runtime.outcome(run, run.id(), outcome);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cs_message WHERE type='assistant'", Integer.class)).isEqualTo(1);
        assertThat(runtime.portalRun(11, 1)).containsEntry("status", "completed").containsEntry("reply", "已确认的正式回复");
        outcome.put("reply", "篡改相同标识的回复");
        assertThatThrownBy(() -> runtime.outcome(run, run.id(), outcome)).isInstanceOf(AgentException.class);
    }

    @Test void cancellationRejectsLatePublicationAndInvalidatesPendingTasks() {
        pending("h1", "user_requested");
        runtime.cancel(11, 1);
        assertThat(runtime.task("h1")).containsEntry("status", "cancelled");
        assertThatThrownBy(() -> runtime.outcome(run, run.id(), map("expected_revision", 1, "status", "completed", "message_id", "late", "reply", "迟到回复")))
                .isInstanceOf(AgentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cs_message WHERE type='assistant'", Integer.class)).isZero();
    }

    @Test void historyKeepsAgentReplyKeyAndDatabaseIdForCheckpointDeduplication() {
        runtime.outcome(run, run.id(), map("expected_revision", 1, "status", "completed", "message_id", "reply-key", "reply", "上一轮回复"));
        var next = runtime.beginChat(11, "继续聊天", 1);
        var history = (List<Map<String, Object>>) next.get("history");
        var reply = history.stream().filter(message -> "上一轮回复".equals(message.get("content"))).findFirst().orElseThrow();
        assertThat(reply).containsEntry("id", "reply-key").containsEntry("role", "assistant");
        assertThat(reply.get("database_id")).isNotNull().isNotEqualTo("reply-key");
    }

    @Test void waitingUserContinuesSameRunWithNewRevisionAndFreshCredential() {
        runtime.outcome(run, run.id(), map("expected_revision", 1, "status", "waiting_user", "message_id", "question", "reply", "请提供订单号"));
        var next = runtime.beginChat(11, "101", 1);
        assertThat(next).containsEntry("run_id", run.id()).containsEntry("revision", 2);
        assertThat(next.get("run_token")).isNotEqualTo(request.get("run_token"));
        assertThat(store.authenticate((String) request.get("run_token"))).isNull();
    }

    @Test void latestRunUsesInputOrderEvenWhenTimestampsAndUuidSortFavorOlderRun() {
        runtime.outcome(run, run.id(), map("expected_revision", 1, "status", "failed", "message_id", "failed", "reply", "暂时无法处理。"));
        var next = runtime.beginChat(11, "请转人工客服", 1);
        String newerId = (String) next.get("run_id");
        jdbc.update("UPDATE cs_agent_run SET created_at='2026-11-15 12:00:00' WHERE session_id=11");
        String highOldId = "zzzz-old-run";
        jdbc.update("UPDATE cs_agent_run SET id=? WHERE id=?", highOldId, run.id());
        assertThat(runtime.portalRun(11, 1)).containsEntry("runId", newerId).containsEntry("status", "running");
        runtime.cancel(11, 1);
        assertThat(runtime.portalRun(11, 1)).containsEntry("runId", newerId).containsEntry("status", "cancelled");
    }

    @Test void aCommittedInterruptIsRecoveredAfterActivationCallbackWasLost() {
        runtime.prepareHuman(run, proposal("h1", "user_requested"));
        jdbc.update("UPDATE cs_human_task SET created_at=DATEADD('MINUTE',-1,CURRENT_TIMESTAMP) WHERE id='h1'");
        assertThat(runtime.preparingTasks()).containsExactly("h1");
        var falseStatus = map("run_id", run.id(), "status", "waiting_human", "revision", 1,
                "human_task_id", "h1", "checkpoint_paused", false);
        runtimeWithResponse(falseStatus).reconcilePreparing("h1");
        assertThat(runtime.task("h1")).containsEntry("status", "preparing");
        falseStatus.put("checkpoint_paused", true);
        falseStatus.put("human_task_id", "another-task");
        runtimeWithResponse(falseStatus).reconcilePreparing("h1");
        assertThat(runtime.task("h1")).containsEntry("status", "preparing");
        falseStatus.put("human_task_id", "h1");
        runtimeWithResponse(falseStatus).reconcilePreparing("h1");
        assertThat(runtime.task("h1")).containsEntry("status", "pending");
        assertThat(store.run(run.id()).status()).isEqualTo("waiting_human");
    }

    @Test void resumeResponseRecoversLostPublicationAcknowledgementWithoutRewritingHumanText() {
        pending("h1", "user_requested");
        String humanText = "  人工终稿\n保留空格  ";
        runtime.decide("h1", map("decisionId", "d1", "version", 1, "action", "send_reply", "reply", humanText), 9);
        var normalized = map("run_id", run.id(), "status", "completed", "revision", 1,
                "message_id", "human-final", "reply", humanText);
        runtimeWithResponse(normalized).deliverDecision("h1");
        assertThat(runtime.task("h1")).containsEntry("status", "applied");
        assertThat(runtime.portalRun(11, 1)).containsEntry("status", "completed").containsEntry("reply", humanText);
        assertThat(jdbc.queryForObject("SELECT author_type FROM cs_agent_message WHERE message_key='human-final'", String.class)).isEqualTo("human");
        runtimeWithResponse(normalized).deliverDecision("h1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cs_message WHERE type='assistant'", Integer.class)).isEqualTo(1);
    }

    private AgentRuntimeService runtimeWithResponse(Map<String, Object> response) {
        var client = WebClient.builder().exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(write(response)).build())).build();
        var data = mock(AgentDataService.class);
        when(data.userContext(1)).thenReturn(map("display_name", "小林"));
        return new AgentRuntimeService(store, data, client, "test-service-key", 3600, 300);
    }

    @Test void userMemoryRequiresPublishedReplyAndVerifiableCurrentUserQuote() {
        var memory = new AgentMemoryService(store);
        long input = ((Number) jdbc.queryForMap("SELECT id FROM cs_message WHERE type='user'").get("id")).longValue();
        jdbc.update("UPDATE cs_message SET content='以后服务优先人工' WHERE id=?", input);
        var body = map("expected_revision", 1, "through_message_id", String.valueOf(input), "summary", "用户请求人工处理。",
                "facts", List.of(map("attribute", "service_preference", "value", "人工", "quote", "以后服务优先人工", "source_message_id", String.valueOf(input))));
        assertThatThrownBy(() -> memory.save(run, body)).isInstanceOf(AgentException.class);
        runtime.outcome(run, run.id(), map("expected_revision", 1, "status", "completed", "message_id", "formal", "reply", "已处理。"));
        memory.save(run, body);
        memory.save(run, body);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cs_user_memory", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cs_conversation_summary", Integer.class)).isEqualTo(1);
        assertThat(write(memory.load(1, 11))).contains("人工", "service_preference");
        body.put("facts", List.of(map("attribute", "preferred_name", "value", "别人的昵称", "quote", "叫我别人的昵称", "source_message_id", String.valueOf(input))));
        assertThatThrownBy(() -> memory.save(run, body)).isInstanceOf(AgentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cs_user_memory", Integer.class)).isEqualTo(1);
        body.put("facts", List.of(map("attribute", "preferred_name", "value", "13812345678", "quote", "13812345678", "source_message_id", String.valueOf(input))));
        assertThatThrownBy(() -> memory.save(run, body)).isInstanceOf(AgentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cs_user_memory", Integer.class)).isEqualTo(1);
        jdbc.update("UPDATE cs_session SET is_deleted=1 WHERE id=11");
        jdbc.update("INSERT INTO cs_session(id,user_id,title) VALUES (12,1,'其他会话')");
        assertThat((List<?>) memory.load(1, 12).get("profile")).isEmpty();
    }

    @Test void conversationsAreOwnedAndMessagePaginationDoesNotLoseLongOriginals() {
        var memory = new AgentMemoryService(store);
        jdbc.update("INSERT INTO cs_session(id,user_id,title) VALUES (22,2,'别人的会话'),(12,1,'上次咨询')");
        store.insertMessage(22, "user", "其他用户隐私");
        long first = store.insertMessage(12, "user", "甲".repeat(4000));
        long second = store.insertMessage(12, "assistant", "历史回复");
        long third = store.insertMessage(12, "user", "后续问题");
        assertThat(write(memory.conversations(1, 1, 10))).doesNotContain("别人的会话");
        var page = memory.messages(1, 12, null, 2, true);
        assertThat(page).containsEntry("has_more", true).containsEntry("next_before_id", second);
        assertThat(write(page)).contains("后续问题").doesNotContain("其他用户隐私");
        var earlier = memory.messages(1, 12, second, 2, true);
        var row = object(((List<?>) earlier.get("items")).get(0));
        assertThat(row).containsEntry("id", first).containsEntry("truncated", true);
        assertThat(((String) row.get("content")).length()).isEqualTo(2000);
        assertThat(write(memory.messages(1, 12, second, 2, false))).contains("甲".repeat(4000));
        assertThatThrownBy(() -> memory.messages(1, 22, null, 10, true)).isInstanceOf(AgentException.class);
        jdbc.update("UPDATE cs_session SET is_deleted=1 WHERE id=12");
        assertThatThrownBy(() -> memory.messages(1, 12, null, 10, true)).isInstanceOf(AgentException.class);
        assertThat(third).isGreaterThan(second);
    }

    @Test void aLateOlderRunCannotOverwriteNewerUserPreferenceOrSummary() {
        var memory = new AgentMemoryService(store);
        long oldInput = ((Number) jdbc.queryForMap("SELECT id FROM cs_message WHERE type='user'").get("id")).longValue();
        jdbc.update("UPDATE cs_message SET content='以后服务优先人工' WHERE id=?", oldInput);
        runtime.outcome(run, run.id(), map("expected_revision", 1, "status", "completed", "message_id", "old", "reply", "已处理。"));
        var next = runtime.beginChat(11, "以后服务优先自助", 1);
        AgentRun newer = store.run((String) next.get("run_id"));
        long newInput = Long.parseLong((String) next.get("message_id"));
        runtime.outcome(newer, newer.id(), map("expected_revision", 1, "status", "completed", "message_id", "new", "reply", "好的。"));
        memory.save(newer, map("expected_revision", 1, "through_message_id", newInput, "summary", "用户现在希望优先自助。", "facts",
                List.of(map("attribute", "service_preference", "value", "自助", "quote", "以后服务优先自助", "source_message_id", newInput))));
        memory.save(run, map("expected_revision", 1, "through_message_id", oldInput, "summary", "旧摘要", "facts",
                List.of(map("attribute", "service_preference", "value", "人工", "quote", "以后服务优先人工", "source_message_id", oldInput))));
        assertThat(write(memory.load(1, 11))).contains("自助").doesNotContain("旧摘要", "人工");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cs_message", Integer.class)).isEqualTo(4);
    }

    @Test void oneOffHandoffIsNotSavedAsLongTermPreferenceButSummaryStillPersists() {
        var memory = new AgentMemoryService(store);
        long input = ((Number) jdbc.queryForMap("SELECT id FROM cs_message WHERE type='user'").get("id")).longValue();
        runtime.outcome(run, run.id(), map("expected_revision", 1, "status", "completed", "message_id", "one-off", "reply", "已处理。"));
        memory.save(run, map("expected_revision", 1, "through_message_id", input, "summary", "本次用户请求人工处理。",
                "facts", List.of(map("attribute", "service_preference", "value", "人工", "quote", "我要人工处理", "source_message_id", input))));
        assertThat(memory.profile(1)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cs_conversation_summary", Integer.class)).isEqualTo(1);
    }
}
