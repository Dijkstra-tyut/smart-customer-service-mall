package com.supermarket.live;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 人工实时客服的 SQL 与事务边界。所有状态流转都用条件 UPDATE，避免并发下脏写。
 */
@Repository
public class LiveChatStore {

    private static final String SESSION_VIEW = """
            SELECT s.*, u.name AS user_name, a.name AS admin_name
            FROM cs_live_session s
            LEFT JOIN sys_user u ON u.id = s.user_id
            LEFT JOIN sys_user a ON a.id = s.admin_id
            """;

    private static final String MESSAGE_VIEW = """
            SELECT m.*, u.name AS sender_name
            FROM cs_live_message m
            LEFT JOIN sys_user u ON u.id = m.sender_id
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public LiveChatStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
    }

    public <T> T transaction(Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }

    // ---------------------------------------------------------------- session

    public long createSession(int userId, String source) {
        LocalDateTime now = LocalDateTime.now();
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO cs_live_session(user_id,status,source,create_time,update_time) VALUES (?,?,?,?,?)",
                    new String[]{"id"});
            statement.setInt(1, userId);
            statement.setString(2, LiveStatus.WAITING.code());
            statement.setString(3, source);
            statement.setObject(4, now);
            statement.setObject(5, now);
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    public LiveSession openSession(int userId) {
        return toSession(one(SESSION_VIEW + " WHERE s.user_id = ? AND s.status <> 'closed' AND s.is_deleted = 0 "
                + "ORDER BY s.id DESC LIMIT 1", userId));
    }

    /** 串行化同一用户的会话创建，避免并发首条消息生成两个进行中的会话。 */
    public void lockUser(int userId) {
        jdbc.queryForList("SELECT id FROM sys_user WHERE id = ? FOR UPDATE", userId);
    }

    public LiveSession session(long sessionId) {
        return toSession(one(SESSION_VIEW + " WHERE s.id = ? AND s.is_deleted = 0", sessionId));
    }

    public List<LiveSession> userSessions(int userId, int limit) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                SESSION_VIEW + " WHERE s.user_id = ? AND s.is_deleted = 0 ORDER BY s.update_time DESC LIMIT ?",
                userId, limit);
        List<LiveSession> sessions = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            sessions.add(toSession(row));
        }
        return sessions;
    }

    public LiveSession lockSession(long sessionId) {
        return toSession(one("SELECT * FROM cs_live_session WHERE id = ? AND is_deleted = 0 FOR UPDATE", sessionId));
    }

    /** 抢单：只有 waiting 状态能被成功认领，返回影响行数用于判断并发结果。 */
    public int claim(long sessionId, int adminId) {
        return jdbc.update("UPDATE cs_live_session SET admin_id = ?, status = 'serving', update_time = ? "
                + "WHERE id = ? AND status = 'waiting' AND is_deleted = 0", adminId, LocalDateTime.now(), sessionId);
    }

    public int close(long sessionId, String reason) {
        return jdbc.update("UPDATE cs_live_session SET status = 'closed', close_reason = ?, update_time = ? "
                + "WHERE id = ? AND status <> 'closed' AND is_deleted = 0", reason, LocalDateTime.now(), sessionId);
    }

    public int markAgentSource(long sessionId) {
        return jdbc.update("UPDATE cs_live_session SET source = 'agent', update_time = ? "
                + "WHERE id = ? AND status = 'waiting' AND source <> 'agent' AND is_deleted = 0",
                LocalDateTime.now(), sessionId);
    }

    /** 更新会话摘要与未读数；increment 由调用方根据发送方角色固定选择，不含外部输入。 */
    public void touch(long sessionId, String senderType, String preview, LocalDateTime at) {
        String counter = LiveSender.USER.code().equals(senderType)
                ? "unread_admin = unread_admin + 1"
                : "unread_user = unread_user + 1";
        jdbc.update("UPDATE cs_live_session SET last_message_preview = ?, last_message_at = ?, update_time = ?, "
                + counter + " WHERE id = ?", preview, at, at, sessionId);
    }

    public List<LiveSession> listSessions(String status, Integer adminId, int offset, int size) {        List<Object> args = new ArrayList<>();
        String where = buildFilter(status, adminId, args);
        String order = LiveStatus.WAITING.code().equals(status) ? "s.update_time ASC" : "s.update_time DESC";
        args.add(size);
        args.add(offset);
        List<Map<String, Object>> rows = jdbc.queryForList(
                SESSION_VIEW + where + " ORDER BY " + order + " LIMIT ? OFFSET ?", args.toArray());
        List<LiveSession> sessions = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            sessions.add(toSession(row));
        }
        return sessions;
    }

    public int countSessions(String status, Integer adminId) {
        List<Object> args = new ArrayList<>();
        String where = buildFilter(status, adminId, args);
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM cs_live_session s" + where, Integer.class, args.toArray());
        return total == null ? 0 : total;
    }

    private String buildFilter(String status, Integer adminId, List<Object> args) {
        StringBuilder where = new StringBuilder(" WHERE s.is_deleted = 0");
        if ("active".equals(status)) {
            where.append(" AND s.status <> 'closed'");
        } else if (status != null && Set.of("waiting", "serving", "closed").contains(status)) {
            where.append(" AND s.status = ?");
            args.add(status);
        }
        if (adminId != null) {
            where.append(" AND s.admin_id = ?");
            args.add(adminId);
        }
        return where.toString();
    }

    public Map<String, Integer> countByStatus() {
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put(LiveStatus.WAITING.code(), 0);
        result.put(LiveStatus.SERVING.code(), 0);
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT status, COUNT(*) AS total FROM cs_live_session WHERE is_deleted = 0 AND status <> 'closed' GROUP BY status")) {
            result.put((String) row.get("status"), ((Number) row.get("total")).intValue());
        }
        return result;
    }

    // ---------------------------------------------------------------- message

    public LiveMessage append(long sessionId, String senderType, Integer senderId, String contentType,
                              String content, String clientMsgId) {
        LocalDateTime now = LocalDateTime.now();
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO cs_live_message(session_id,sender_type,sender_id,content_type,content,client_msg_id,is_read,create_time) "
                            + "VALUES (?,?,?,?,?,?,0,?)", new String[]{"id"});
            statement.setLong(1, sessionId);
            statement.setString(2, senderType);
            if (senderId == null) {
                statement.setNull(3, java.sql.Types.INTEGER);
            } else {
                statement.setInt(3, senderId);
            }
            statement.setString(4, contentType);
            statement.setString(5, content);
            statement.setString(6, clientMsgId);
            statement.setObject(7, now);
            return statement;
        }, key);
        long id = key.getKey().longValue();
        // 与实时消息处于同一个事务，重发幂等时不会再次进入 append。
        var linked = one("SELECT agent_session_id FROM cs_live_session WHERE id=?", sessionId);
        if (linked != null && linked.get("agent_session_id") instanceof Number target && ("user".equals(senderType) || "admin".equals(senderType))) {
            GeneratedKeyHolder conversationKey = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("INSERT INTO cs_message(session_id,type,content,create_time) VALUES (?,?,?,?)", new String[]{"id"});
                statement.setLong(1, target.longValue());
                statement.setString(2, "admin".equals(senderType) ? "human" : "user");
                statement.setString(3, content);
                statement.setObject(4, now);
                return statement;
            }, conversationKey);
            jdbc.update("UPDATE cs_live_message SET agent_message_id=? WHERE id=?", conversationKey.getKey().longValue(), id);
            jdbc.update("UPDATE cs_session SET update_time=? WHERE id=?", now, target.longValue());
        }
        return message(id);
    }

    public LiveMessage message(long messageId) {
        return toMessage(one(MESSAGE_VIEW + " WHERE m.id = ?", messageId));
    }

    public LiveMessage messageByClientId(long sessionId, String clientMsgId) {
        return toMessage(one(MESSAGE_VIEW + " WHERE m.session_id = ? AND m.client_msg_id = ?", sessionId, clientMsgId));
    }

    public LiveMessage latestMessage(long sessionId) {
        return toMessage(one(MESSAGE_VIEW + " WHERE m.session_id = ? ORDER BY m.id DESC LIMIT 1", sessionId));
    }

    public List<LiveMessage> messages(long sessionId, long afterId, int limit) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                MESSAGE_VIEW + " WHERE m.session_id = ? AND m.id > ? ORDER BY m.id ASC LIMIT ?", sessionId, afterId, limit);
        List<LiveMessage> messages = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            messages.add(toMessage(row));
        }
        return messages;
    }

    public long maxMessageId(long sessionId) {        Long max = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM cs_live_message WHERE session_id = ?",
                Long.class, sessionId);
        return max == null ? 0L : max;
    }

    /** 对端已读：reader 为 admin 时标记用户消息，为 user 时标记客服消息，并清零对应未读数。 */
    public int markRead(long sessionId, String readerRole, long upToId) {
        boolean admin = LiveIdentity.ROLE_ADMIN.equals(readerRole);
        String senders = admin ? "'user'" : "'admin','agent'";
        int rows = jdbc.update("UPDATE cs_live_message SET is_read = 1 WHERE session_id = ? AND id <= ? "
                + "AND is_read = 0 AND sender_type IN (" + senders + ")", sessionId, upToId);
        String counter = admin ? "unread_admin" : "unread_user";
        jdbc.update("UPDATE cs_live_session SET " + counter + " = 0 WHERE id = ?", sessionId);
        return rows;
    }

    // ---------------------------------------------------------------- event

    public void event(long sessionId, String eventType, String actorType, Integer actorId, String payload) {
        jdbc.update("INSERT INTO cs_live_event(session_id,event_type,actor_type,actor_id,payload,create_time) "
                        + "VALUES (?,?,?,?,?,?)",
                sessionId, eventType, actorType, actorId, payload, LocalDateTime.now());
    }

    // ---------------------------------------------------------------- mapping

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    static LiveSession toSession(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        return new LiveSession(
                number(row, "id").longValue(),
                number(row, "user_id").intValue(),
                nullableInt(row, "admin_id"),
                (String) row.get("status"),
                (String) row.get("source"),
                (String) row.get("close_reason"),
                (String) row.get("last_message_preview"),
                time(row, "last_message_at"),
                intValue(row, "unread_user"),
                intValue(row, "unread_admin"),
                time(row, "create_time"),
                time(row, "update_time"),
                (String) row.get("user_name"),
                (String) row.get("admin_name"),
                row.get("agent_session_id") instanceof Number id ? id.longValue() : null);
    }

    static LiveMessage toMessage(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        return new LiveMessage(
                number(row, "id").longValue(),
                number(row, "session_id").longValue(),
                (String) row.get("sender_type"),
                nullableInt(row, "sender_id"),
                (String) row.get("content_type"),
                (String) row.get("content"),
                (String) row.get("client_msg_id"),
                intValue(row, "is_read") == 1,
                time(row, "create_time"),
                (String) row.get("sender_name"));
    }

    private static Number number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("字段缺失: " + key);
        }
        return number;
    }

    private static Integer nullableInt(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number number ? number.intValue() : null;
    }

    private static int intValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static LocalDateTime time(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof LocalDateTime time) {
            return time;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return null;
    }
}
