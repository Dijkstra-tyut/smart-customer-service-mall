package com.supermarket.agent;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** SQL and transaction boundary for the agent-owned business records. */
@Repository
public class AgentStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public AgentStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
    }

    public <T> T transaction(Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }

    public List<Map<String, Object>> rows(String sql, Object... arguments) {
        return jdbc.queryForList(sql, arguments);
    }

    public Map<String, Object> one(String sql, Object... arguments) {
        List<Map<String, Object>> rows = rows(sql, arguments);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int update(String sql, Object... arguments) { return jdbc.update(sql, arguments); }

    public AgentRun run(String id) {
        return toRun(one("SELECT * FROM cs_agent_run WHERE id = ?", id));
    }

    public AgentRun lockRun(String id) {
        return toRun(one("SELECT * FROM cs_agent_run WHERE id = ? FOR UPDATE", id));
    }

    public AgentRun authenticate(String token) {
        return toRun(one("SELECT * FROM cs_agent_run WHERE token_hash = ? AND token_expires_at > ?",
                AgentJson.hash(token), LocalDateTime.now()));
    }

    public Map<String, Object> lockSession(long sessionId, int userId) {
        Map<String, Object> session = one("SELECT * FROM cs_session WHERE id = ? AND user_id = ? AND is_deleted = 0 FOR UPDATE",
                sessionId, userId);
        if (session == null) throw new AgentException(404, "会话不存在或无权访问");
        return session;
    }

    public void requireSession(long sessionId, int userId) {
        if (one("SELECT id FROM cs_session WHERE id = ? AND user_id = ? AND is_deleted = 0", sessionId, userId) == null)
            throw new AgentException(404, "会话不存在或无权访问");
    }

    public long insertMessage(long sessionId, String type, String content) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO cs_message(session_id,type,content,create_time) VALUES (?,?,?,?)", new String[]{"id"});
            statement.setLong(1, sessionId);
            statement.setString(2, type);
            statement.setString(3, content);
            statement.setObject(4, LocalDateTime.now());
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    public List<Map<String, Object>> history(long sessionId, int limit) {
        var messages = rows("SELECT m.id,m.type,m.content,m.create_time,a.author_type AS origin,a.message_key AS agent_message_key FROM cs_message m LEFT JOIN cs_agent_message a ON a.message_id=m.id WHERE m.session_id = ? ORDER BY m.id DESC LIMIT ?", sessionId, limit);
        java.util.Collections.reverse(messages);
        return messages;
    }

    static AgentRun toRun(Map<String, Object> row) {
        if (row == null) return null;
        return new AgentRun((String) row.get("id"), ((Number) row.get("session_id")).longValue(),
                ((Number) row.get("user_id")).intValue(), (String) row.get("status"),
                ((Number) row.get("revision")).intValue(), (String) row.get("human_task_id"));
    }
}
