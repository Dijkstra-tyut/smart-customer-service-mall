package com.supermarket.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.supermarket.agent.AgentJson.*;
import static org.assertj.core.api.Assertions.*;

/** 在真实 MySQL 验证 SQL 方言；所有测试写入在外层事务回滚，不污染用户记录。 */
@EnabledIfEnvironmentVariable(named="AGENT_TEST_MYSQL_URL", matches=".+")
class AgentMemoryMysqlTest {
    @Test void mysqlPersistsQuotedPreferencesAndRollsBackTestConversation() {
        var source = new DriverManagerDataSource(System.getenv("AGENT_TEST_MYSQL_URL"),
                System.getenv("AGENT_TEST_MYSQL_USER"), System.getenv("AGENT_TEST_MYSQL_PASSWORD"));
        var manager = new DataSourceTransactionManager(source);
        var jdbc = new JdbcTemplate(source);
        var store = new AgentStore(jdbc, manager);
        var memory = new AgentMemoryService(store);
        String marker = "memory-test-" + UUID.randomUUID();
        new TransactionTemplate(manager).execute(status -> {
            jdbc.update("INSERT INTO cs_session(user_id,title) VALUES (?,?)", -190001, marker);
            long session = jdbc.queryForObject("SELECT id FROM cs_session WHERE title=?", Long.class, marker);
            String runId = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO cs_agent_run(id,session_id,user_id,status,revision,token_hash,token_expires_at) VALUES (?,?,?,'completed',1,?,?)",
                    runId, session, -190001, hash(marker), LocalDateTime.now().plusHours(1));
            var run = store.run(runId);
            long message = store.insertMessage(session, "user", "以后请称呼我小林，今后回复简短一点");
            var payload = map("expected_revision", 1, "through_message_id", message, "summary", "用户希望使用小林称呼并采用简短回复。",
                    "facts", List.of(map("attribute", "preferred_name", "value", "小林", "quote", "以后请称呼我小林", "source_message_id", message),
                            map("attribute", "response_style", "value", "简短", "quote", "今后回复简短一点", "source_message_id", message)));
            memory.save(run, payload);
            memory.save(run, payload);
            assertThat(memory.profile(-190001)).hasSize(2);
            assertThat(write(memory.load(-190001, session))).contains("小林", "简短", "conversation_summary");
            assertThat(write(memory.messages(-190001, session, null, 10, true))).contains("以后请称呼我小林");
            long newer = store.insertMessage(session, "user", "以后改为称呼小王");
            memory.save(run, map("expected_revision", 1, "through_message_id", newer, "summary", "用户现在希望称呼小王。",
                    "facts", List.of(map("attribute", "preferred_name", "value", "小王", "quote", "以后改为称呼小王", "source_message_id", newer))));
            memory.save(run, payload);
            assertThat(write(memory.load(-190001, session))).contains("小王").doesNotContain("小林");
            status.setRollbackOnly();
            return null;
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cs_session WHERE title=?", Integer.class, marker)).isZero();
    }
}
