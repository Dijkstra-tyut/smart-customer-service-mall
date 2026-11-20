package com.supermarket.agent;

import com.supermarket.entity.Order;
import com.supermarket.entity.User;
import com.supermarket.enums.OrderStatusEnum;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.supermarket.agent.AgentJson.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the actual migration and SQL ownership boundary without a live business database. */
class AgentDataServiceTest {
    private JdbcTemplate jdbc;
    private AgentStore store;
    private AgentDataService data;
    private UserService users;
    private OrderService orders;
    private AgentRun alice;
    private AgentRun bob;

    @BeforeEach
    void setUp() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:agent_data_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE cs_session(id BIGINT PRIMARY KEY,user_id INT NOT NULL,is_deleted INT DEFAULT 0,title VARCHAR(100),create_time TIMESTAMP,update_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE cs_message(id BIGINT AUTO_INCREMENT PRIMARY KEY,session_id BIGINT,type VARCHAR(16),content LONGTEXT,create_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE oms_order(id INT PRIMARY KEY,user_id INT NOT NULL,order_sn VARCHAR(80),"
                + "total_amount DECIMAL(12,2),status INT,create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,is_deleted INT DEFAULT 0)");
        new ResourceDatabasePopulator(new FileSystemResource("../db/migrations/20261110_customer_agent.sql"))
                .execute(source);
        new ResourceDatabasePopulator(new FileSystemResource("../db/migrations/20261115_customer_memory.sql")).execute(source);
        new ResourceDatabasePopulator(new FileSystemResource("../db/migrations/20261113_cs_live_chat.sql")).execute(source);
        store = new AgentStore(jdbc, new DataSourceTransactionManager(source));
        users = mock(UserService.class);
        orders = mock(OrderService.class);
        data = new AgentDataService(store, users, orders,
                mock(ProductViewService.class), mock(ProductSearchService.class), new AgentMemoryService(store));
        jdbc.update("INSERT INTO cs_session(id,user_id) VALUES (11,1),(22,2)");
        alice = createRun("run-alice", 11, 1);
        bob = createRun("run-bob", 22, 2);
        when(users.getById(1)).thenReturn(new User().setId(1).setName("小林")
                .setPassword("must-not-leak").setPhone("13812345678")
                .setAddress("完整的私人地址").setEmail("private@example.test").setRemark("内部备注"));
        when(users.getById(2)).thenReturn(new User().setId(2).setName("小张"));
    }

    private AgentRun createRun(String id, long session, int user) {
        jdbc.update("INSERT INTO cs_agent_run(id,session_id,user_id,active_session_id,status,revision,token_hash,token_expires_at)"
                        + " VALUES (?,?,?,?,?,?,?,?)", id, session, user, session, "running", 1,
                hash("opaque-" + id), LocalDateTime.now().plusHours(1));
        return store.run(id);
    }

    @Test
    void contextReturnsAnExplicitWhitelistRatherThanTheUserEntity() {
        Map<String, Object> response = data.context(alice);
        assertThat(response).containsEntry("user_id", "1").containsEntry("session_id", "11")
                .containsEntry("run_id", "run-alice");
        assertThat(object(response.get("user_context"))).containsOnly(entry("display_name", "小林"));
        assertThat(write(response)).doesNotContain("must-not-leak", "13812345678", "private@example.test",
                "完整的私人地址", "内部备注", "token_hash", "opaque-");
    }

    @Test
    void orderListsAreScopedByAuthenticatedRunAndPaginatedInTheDatabase() {
        jdbc.update("INSERT INTO oms_order(id,user_id,order_sn,total_amount,status) VALUES "
                + "(101,1,'ALICE-A',12.00,0),(102,1,'ALICE-B',13.00,1),(201,2,'BOB-A',25.00,0)");
        Map<String, Object> response = data.listOrders(alice, null, 1, 1);
        assertThat(((Number) response.get("total")).longValue()).isEqualTo(2);
        assertThat((List<?>) response.get("items")).hasSize(1);
        assertThat(write(response)).contains("ALICE-B").doesNotContain("BOB-A", "ALICE-A");
        assertThat(write(data.listOrders(bob, 0, 1, 10))).contains("BOB-A").doesNotContain("ALICE-");
        assertThatThrownBy(() -> data.listOrders(alice, null, 0, 10)).isInstanceOf(AgentException.class);
        assertThatThrownBy(() -> data.listOrders(alice, null, 1, 1000)).isInstanceOf(AgentException.class);
    }

    @Test
    void foreignOrderIsDeniedEvenWhenAnUpstreamServiceReturnsIt() {
        Order foreign = new Order().setId(201).setUserId(2).setOrderSn("PRIVATE-BOB")
                .setTotalAmount(BigDecimal.TEN).setStatus(OrderStatusEnum.PENDING_PAY);
        when(orders.detail(1, 201)).thenReturn(foreign);
        assertThatThrownBy(() -> data.order(alice, 201)).isInstanceOf(AgentException.class)
                .hasMessageContaining("无权");
        verify(orders).detail(1, 201);
    }

    @Test
    void aForgedRunUserDoesNotOverridePersistedIdentity() {
        AgentRun forged = new AgentRun(alice.id(), 11, 2, "running", 1, null);
        assertThatThrownBy(() -> data.context(forged)).isInstanceOf(AgentException.class)
                .hasMessageContaining("身份");
        verify(users, never()).getById(2);
    }

    @Test
    void businessReadsAreDeniedAfterRunCancellationOrSessionDeletion() {
        jdbc.update("UPDATE cs_agent_run SET status='cancelled',active_session_id=NULL WHERE id=?", alice.id());
        assertThatThrownBy(() -> data.order(alice, 101)).isInstanceOf(AgentException.class);
        jdbc.update("UPDATE cs_session SET is_deleted=1 WHERE id=22");
        assertThatThrownBy(() -> data.tickets(bob, 1, 10)).isInstanceOf(AgentException.class);
        verifyNoInteractions(orders);
    }

    @Test
    void ordinaryTicketCreationIsRealPersistentAndIdempotent() {
        Map<String, Object> request = command("create-1", "ticket_create",
                map("summary", "商品破损", "details", "用户要求记录售后诉求", "priority", "medium"));
        Map<String, Object> first = data.action(alice, request);
        Map<String, Object> replay = data.action(alice, request);
        assertThat(replay).isEqualTo(first);
        assertThat(first).containsEntry("status", "created");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cs_ticket", Integer.class)).isEqualTo(1);
        assertThat(data.ticket(alice, (String) first.get("ticket_id"))).containsEntry("summary", "商品破损");
        assertThatThrownBy(() -> data.ticket(bob, (String) first.get("ticket_id")))
                .isInstanceOf(AgentException.class);
    }

    @Test
    void operationIdCannotBeReusedForDifferentArgumentsOrAnotherRun() {
        Map<String, Object> original = command("stable-operation", "ticket_create",
                map("summary", "售后申请", "details", "原始诉求", "priority", "medium"));
        data.action(alice, original);
        Map<String, Object> changed = command("stable-operation", "ticket_create",
                map("summary", "另一申请", "details", "不同诉求", "priority", "medium"));
        assertThatThrownBy(() -> data.action(alice, changed)).isInstanceOf(AgentException.class)
                .hasMessageContaining("标识");
        assertThatThrownBy(() -> data.action(bob, original)).isInstanceOf(AgentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cs_ticket", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentRetriesOfTheSameOperationCreateOnlyOneTicket() throws Exception {
        Map<String, Object> request = command("simultaneous-create", "ticket_create",
                map("summary", "并发重试", "details", "相同诉求的两次投递", "priority", "medium"));
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> { start.await(); return data.action(alice, request); });
            var second = executor.submit(() -> { start.await(); return data.action(alice, request); });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cs_ticket", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cs_agent_operation", Integer.class)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void invalidTicketDoesNotLeaveAnOperationOrPartialRecord() {
        assertThatThrownBy(() -> data.action(alice, command("invalid-create", "ticket_create",
                map("summary", "申请", "details", "事实", "priority", "not-a-priority"))))
                .isInstanceOf(AgentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cs_ticket", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cs_agent_operation", Integer.class)).isZero();
    }

    @Test
    void sensitiveActionNeedsMatchingLiveHumanApproval() {
        Map<String, Object> arguments = map("order_id", 101);
        Map<String, Object> request = command("cancel-order", "order_cancel", arguments);
        assertThatThrownBy(() -> data.action(alice, request)).isInstanceOf(AgentException.class)
                .hasMessageContaining("批准");
        approval("human-cancel", "order_cancel", arguments, false, "approve_action");
        request.put("approval_task_id", "human-cancel");
        jdbc.update("INSERT INTO oms_order(id,user_id,order_sn,total_amount,status) VALUES (101,1,'ALICE-A',12,0)");
        assertThat(data.action(alice, request)).containsEntry("status", "cancelled");
        data.action(alice, request);
        verify(orders, times(1)).cancel(101);
    }

    @Test
    void staleRejectedOrDifferentApprovalCannotExecuteAnAction() {
        Map<String, Object> arguments = map("order_id", 101);
        approval("stale", "order_cancel", arguments, true, "approve_action");
        approval("rejected", "order_cancel", arguments, false, "reject_action");
        approval("wrong-object", "order_cancel", map("order_id", 999), false, "approve_action");
        for (String id : List.of("stale", "rejected", "wrong-object")) {
            Map<String, Object> request = command("try-" + id, "order_cancel", arguments);
            request.put("approval_task_id", id);
            assertThatThrownBy(() -> data.action(alice, request)).isInstanceOf(AgentException.class);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cs_agent_operation", Integer.class)).isZero();
        verifyNoInteractions(orders);
    }

    @Test
    void staleRunRevisionCannotCreateANewBusinessRecord() {
        jdbc.update("UPDATE cs_agent_run SET revision=2 WHERE id=?", alice.id());
        assertThatThrownBy(() -> data.action(alice, command("old-input", "ticket_create",
                map("summary", "原申请", "details", "已被新输入取代", "priority", "medium"))))
                .isInstanceOf(AgentException.class).hasMessageContaining("版本");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cs_ticket", Integer.class)).isZero();
    }

    private Map<String, Object> command(String id, String kind, Map<String, Object> arguments) {
        return map("operation_id", id, "expected_revision", 1, "kind", kind, "arguments", arguments);
    }

    private void approval(String id, String kind, Map<String, Object> arguments, boolean stale, String action) {
        Map<String, Object> proposal = map("kind", kind, "arguments", arguments);
        jdbc.update("INSERT INTO cs_human_task(id,run_id,run_revision,version,reason,status,payload_hash,action_json,"
                        + "action_stale,decision_id,decision_json) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                id, alice.id(), 1, 1, "sensitive_action", "applying", payloadHash(proposal), write(proposal),
                stale, "decision-" + id, write(map("action", action, "version", 1)));
    }
}
