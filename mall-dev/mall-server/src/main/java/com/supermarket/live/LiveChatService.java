package com.supermarket.live;

import com.supermarket.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 人工实时客服业务编排：会话生命周期、消息落库与实时推送、已读与输入状态。
 * <p>
 * 所有消息先落库再推送，断线客户端可通过 REST 增量拉取补偿；推送失败不影响业务事务。
 */
@Service
public class LiveChatService {

    private static final Logger log = LoggerFactory.getLogger(LiveChatService.class);
    private static final int MAX_CONTENT = 2000;
    private static final int PREVIEW_LENGTH = 100;
    private static final Pattern CLIENT_MESSAGE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final LiveChatStore store;
    private final LiveConnectionRegistry registry;
    private final List<LiveAgentBridge> bridges;

    public LiveChatService(LiveChatStore store, LiveConnectionRegistry registry, List<LiveAgentBridge> bridges) {
        this.store = store;
        this.registry = registry;
        this.bridges = bridges;
    }

    public record SendOutcome(LiveMessage message, boolean duplicate) {
    }

    private record AppendResult(LiveMessage message, boolean created) {
    }

    // ------------------------------------------------------------------ 会话

    public LiveSession currentSession(int userId) {
        return store.openSession(userId);
    }

    /** 外层业务事务提交后，再通知客户端读取完整的转接关联。 */
    public void announce(LiveSession session) { pushState(session); }

    public LiveSession createSession(int userId) {
        LiveSession open = store.openSession(userId);
        if (open != null) {
            return open;
        }
        LiveSession session = store.transaction(() -> {
            store.lockUser(userId);
            LiveSession existing = store.openSession(userId);
            if (existing != null) {
                return existing;
            }
            long id = store.createSession(userId, LiveSender.USER.code());
            store.event(id, "created", LiveSender.USER.code(), userId, null);
            return store.session(id);
        });
        pushState(session);
        return session;
    }

    public List<LiveSession> userSessions(int userId, int limit) {
        int size = Math.min(50, Math.max(1, limit));
        return store.userSessions(userId, size);
    }

    /** 管理端列表：status 支持 waiting / serving / closed / active（未结束）。 */
    public Map<String, Object> adminSessions(String status, boolean onlyMine, int adminId, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        Integer filterAdmin = onlyMine ? adminId : null;
        String filterStatus = status == null || status.isBlank() ? "active" : status;
        List<LiveSession> records = store.listSessions(filterStatus, filterAdmin, (safePage - 1) * safeSize, safeSize);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", store.countSessions(filterStatus, filterAdmin));
        result.put("page", safePage);
        result.put("size", safeSize);
        return result;
    }

    public Map<String, Object> stats() {
        Map<String, Integer> counts = store.countByStatus();
        Map<String, Object> result = new LinkedHashMap<>(counts);
        result.put("onlineAdmins", registry.onlineAdmins());
        return result;
    }

    /** 供 WebSocket 层复用的会话状态视图。 */
    public Map<String, Object> stateView(LiveSession session) {
        return stateData(session);
    }

    public LiveSession session(long sessionId, String role, int actorId) {
        LiveSession session = store.session(sessionId);
        requireVisible(session, role, actorId);
        return session;
    }

    // ------------------------------------------------------------------ 消息

    public SendOutcome sendUserMessage(int userId, Long sessionId, String clientMsgId, String contentType, String content) {
        String text = normalizeContent(content, contentType);
        String messageId = normalizeClientMessageId(clientMsgId);
        LiveSession session = sessionId == null ? createSession(userId) : store.session(sessionId);
        if (session == null || session.userId() != userId) {
            throw new ServiceException(404, "会话不存在或无权访问");
        }
        if (session.isClosed()) {
            throw new ServiceException(409, "会话已结束，请重新发起会话");
        }

        SendOutcome outcome = store.transaction(() -> {
            LiveSession locked = store.lockSession(session.id());
            if (locked == null) {
                throw new ServiceException(404, "会话不存在");
            }
            if (locked.isClosed()) {
                throw new ServiceException(409, "会话已结束，请重新发起会话");
            }
            AppendResult appended = append(LiveSender.USER, userId, text, messageId, locked.id());
            if (appended.created()) {
                store.touch(locked.id(), LiveSender.USER.code(), preview(text), LocalDateTime.now());
            }
            return new SendOutcome(appended.message(), !appended.created());
        });

        if (!outcome.duplicate()) {
            publishMessage(outcome.message());
            notifyBridges(outcome.message());
        }
        return outcome;
    }

    public SendOutcome sendAdminMessage(int adminId, long sessionId, String clientMsgId, String contentType, String content) {
        String text = normalizeContent(content, contentType);
        String messageId = normalizeClientMessageId(clientMsgId);
        LiveSession session = store.session(sessionId);
        requireVisible(session, LiveIdentity.ROLE_ADMIN, adminId);
        if (!session.isServing()) {
            throw new ServiceException(409, "请先接入该会话再发送消息");
        }

        SendOutcome outcome = store.transaction(() -> {
            LiveSession locked = store.lockSession(sessionId);
            if (locked == null) {
                throw new ServiceException(404, "会话不存在");
            }
            if (locked.isClosed()) {
                throw new ServiceException(409, "会话已结束");
            }
            if (locked.adminId() == null || locked.adminId() != adminId) {
                throw new ServiceException(403, "该会话不属于当前客服");
            }
            AppendResult appended = append(LiveSender.ADMIN, adminId, text, messageId, sessionId);
            if (appended.created()) {
                store.touch(sessionId, LiveSender.ADMIN.code(), preview(text), LocalDateTime.now());
            }
            return new SendOutcome(appended.message(), !appended.created());
        });

        if (!outcome.duplicate()) {
            publishMessage(outcome.message());
        }
        return outcome;
    }

    /** Agent 内部接口推送（智能客服接管或人工代理回复）。 */
    public LiveMessage sendAgentMessage(int userId, long sessionId, String content) {
        String text = normalizeContent(content, "text");
        LiveSession session = store.session(sessionId);
        if (session == null || session.userId() != userId) {
            throw new ServiceException(404, "会话不存在或无权访问");
        }
        if (session.isClosed()) {
            throw new ServiceException(409, "会话已结束");
        }
        LiveMessage message = store.transaction(() -> {
            LiveMessage saved = store.append(sessionId, LiveSender.AGENT.code(), null, "text", text, null);
            store.touch(sessionId, LiveSender.AGENT.code(), preview(text), LocalDateTime.now());
            return saved;
        });
        publishMessage(message);
        return message;
    }

    public List<LiveMessage> history(long sessionId, Long afterId, Integer limit, String role, int actorId) {
        requireVisible(store.session(sessionId), role, actorId);
        int size = limit == null ? 50 : Math.min(100, Math.max(1, limit));
        return store.messages(sessionId, afterId == null ? 0L : Math.max(0L, afterId), size);
    }

    // ------------------------------------------------------------------ 状态流转

    public LiveSession claim(int adminId, long sessionId) {
        record ClaimResult(LiveSession session, LiveMessage greeting) {
        }
        ClaimResult result = store.transaction(() -> {
            int rows = store.claim(sessionId, adminId);
            if (rows == 0) {
                LiveSession existing = store.session(sessionId);
                if (existing == null) {
                    throw new ServiceException(404, "会话不存在");
                }
                if (existing.isServing() && existing.adminId() != null && existing.adminId() == adminId) {
                    return new ClaimResult(existing, null);
                }
                throw new ServiceException(409, "会话已被其他客服接入或已结束");
            }
            store.event(sessionId, "claimed", LiveSender.ADMIN.code(), adminId, null);
            LiveMessage greeting = store.append(sessionId, LiveSender.SYSTEM.code(), null, "text",
                    "客服已接入，很高兴为您服务", null);
            store.touch(sessionId, LiveSender.SYSTEM.code(), "客服已接入", LocalDateTime.now());
            return new ClaimResult(store.session(sessionId), greeting);
        });

        pushState(result.session());
        if (result.greeting() != null) {
            publishMessage(result.greeting());
        }
        return result.session();
    }

    public LiveSession close(long sessionId, String role, int actorId, String reason) {
        LiveSession session = store.session(sessionId);
        requireVisible(session, role, actorId);
        String closeReason = reason == null || reason.isBlank() ? "user_closed" : reason;
        if (closeReason.length() > 64) {
            throw new ServiceException(400, "结束原因过长");
        }
        if (!session.isClosed()) {
            store.transaction(() -> {
                store.close(sessionId, closeReason);
                store.event(sessionId, "closed", role, actorId, closeReason);
                return null;
            });
            session = store.session(sessionId);
            String payload = LiveJson.envelope("session.closed", null,
                    LiveJson.map("sessionId", sessionId, "reason", closeReason, "closedBy", role));
            registry.sendToUser(session.userId(), payload);
            if (session.adminId() != null) {
                registry.sendToAdmin(session.adminId(), payload);
            }
            registry.broadcastAdmins(payload);
            notifyClosed(session);
        }
        return session;
    }

    /** 标记对端消息已读，并同步已读回执。 */
    public Map<String, Object> markRead(long sessionId, String role, int actorId, Long upToId) {
        LiveSession session = store.session(sessionId);
        requireVisible(session, role, actorId);
        long upTo = upToId == null || upToId <= 0 ? store.maxMessageId(sessionId) : upToId;
        int changed = store.transaction(() -> store.markRead(sessionId, role, upTo));
        Map<String, Object> data = LiveJson.map("sessionId", sessionId, "role", role, "readUpTo", upTo, "changed", changed);
        if (LiveIdentity.ROLE_ADMIN.equals(role)) {
            registry.sendToUser(session.userId(), LiveJson.envelope("session.read", null, data));
        } else if (session.adminId() != null) {
            registry.sendToAdmin(session.adminId(), LiveJson.envelope("session.read", null, data));
        }
        return data;
    }

    /** 输入中提示，不落库，仅转发给对端。 */
    public void typing(long sessionId, String role, int actorId) {
        LiveSession session = store.session(sessionId);
        requireVisible(session, role, actorId);
        String payload = LiveJson.envelope("session.typing", null,
                LiveJson.map("sessionId", sessionId, "role", role, "adminId", session.adminId()));
        if (LiveIdentity.ROLE_ADMIN.equals(role)) {
            registry.sendToUser(session.userId(), payload);
        } else if (session.adminId() != null) {
            registry.sendToAdmin(session.adminId(), payload);
        }
    }

    // ------------------------------------------------------------------ Agent 接入

    /** 智能客服转人工：复用未结束会话，否则新建，并进入 waiting 队列。 */
    public LiveSession handoffFromAgent(int userId, String reason) {
        LiveSession session = store.openSession(userId);
        if (session == null) {
            session = createSession(userId);
        }
        long sessionId = session.id();
        LiveSession current = session;
        if (current.isClosed() || !current.isWaiting()) {
            return current;
        }
        store.transaction(() -> {
            int changed = store.markAgentSource(sessionId);
            if (changed > 0) {
                store.append(sessionId, LiveSender.SYSTEM.code(), null, "text",
                        "已为您转接人工客服，请稍候", null);
                store.touch(sessionId, LiveSender.SYSTEM.code(), "已转接人工客服", LocalDateTime.now());
                store.event(sessionId, "transferred", LiveSender.AGENT.code(), null,
                        reason == null || reason.isBlank() ? "agent_handoff" : reason);
            }
            return null;
        });
        LiveSession updated = store.session(sessionId);
        pushState(updated);
        LiveMessage latest = store.latestMessage(sessionId);
        if (latest != null) {
            publishMessage(latest);
        }
        return updated;
    }

    public LiveSession agentSession(int userId) {
        return store.openSession(userId);
    }

    // ------------------------------------------------------------------ 推送与校验

    private AppendResult append(LiveSender sender, Integer senderId, String content, String clientMsgId, long sessionId) {
        if (clientMsgId != null) {
            LiveMessage existing = store.messageByClientId(sessionId, clientMsgId);
            if (existing != null) {
                return new AppendResult(existing, false);
            }
        }
        try {
            LiveMessage saved = store.append(sessionId, sender.code(), senderId, "text", content, clientMsgId);
            return new AppendResult(saved, true);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            LiveMessage existing = store.messageByClientId(sessionId, clientMsgId);
            if (existing == null) {
                throw duplicate;
            }
            return new AppendResult(existing, false);
        }
    }

    private void publishMessage(LiveMessage message) {
        LiveSession session = store.session(message.sessionId());
        if (session == null) {
            return;
        }
        String payload = LiveJson.envelope("chat.message", null, messageData(message));
        registry.sendToUser(session.userId(), payload);
        if (session.adminId() != null) {
            registry.sendToAdmin(session.adminId(), payload);
        } else {
            registry.broadcastAdmins(LiveJson.envelope("session.waiting", null,
                    LiveJson.map("sessionId", session.id(), "userId", session.userId(),
                            "userName", session.userName(), "preview", session.lastMessagePreview(),
                            "unreadAdmin", session.unreadAdmin(), "waitingSince", session.updateTime())));
        }
    }

    private void pushState(LiveSession session) {
        if (session == null) {
            return;
        }
        String payload = LiveJson.envelope("session.state", null, stateData(session));
        registry.sendToUser(session.userId(), payload);
        if (session.adminId() != null) {
            registry.sendToAdmin(session.adminId(), payload);
        }
        registry.broadcastAdmins(payload);
    }

    private void notifyBridges(LiveMessage message) {
        if (bridges.isEmpty()) {
            return;
        }
        LiveSession session = store.session(message.sessionId());
        for (LiveAgentBridge bridge : bridges) {
            try {
                bridge.onUserMessage(session, message);
            } catch (RuntimeException e) {
                log.warn("LiveAgentBridge 处理用户消息失败, sessionId={}: {}", message.sessionId(), e.getMessage());
            }
        }
    }

    private void notifyClosed(LiveSession session) {
        for (LiveAgentBridge bridge : bridges) {
            try {
                bridge.onSessionClosed(session);
            } catch (RuntimeException e) {
                log.warn("LiveAgentBridge 处理会话关闭失败, sessionId={}: {}", session.id(), e.getMessage());
            }
        }
    }

    private void requireVisible(LiveSession session, String role, int actorId) {
        if (session == null) {
            throw new ServiceException(404, "会话不存在");
        }
        if (LiveIdentity.ROLE_ADMIN.equals(role)) {
            if (session.isWaiting() || session.isClosed()
                    || (session.adminId() != null && session.adminId() == actorId)) {
                return;
            }
            throw new ServiceException(403, "该会话不属于当前客服");
        }
        if (session.userId() != actorId) {
            throw new ServiceException(404, "会话不存在或无权访问");
        }
    }

    private static String normalizeContent(String content, String contentType) {
        String type = contentType == null || contentType.isBlank() ? "text" : contentType;
        if (!"text".equals(type)) {
            throw new ServiceException(400, "当前仅支持文本消息");
        }
        if (content == null || content.isBlank()) {
            throw new ServiceException(400, "消息内容不能为空");
        }
        String text = content.trim();
        if (text.length() > MAX_CONTENT) {
            throw new ServiceException(400, "消息内容不能超过 " + MAX_CONTENT + " 字");
        }
        return text;
    }

    private static String normalizeClientMessageId(String clientMsgId) {
        if (clientMsgId == null || clientMsgId.isBlank()) {
            return UUID.randomUUID().toString().replace("-", "");
        }
        if (!CLIENT_MESSAGE_ID.matcher(clientMsgId).matches()) {
            throw new ServiceException(400, "clientMsgId 格式不合法");
        }
        return clientMsgId;
    }

    private static String preview(String content) {
        return content.length() <= PREVIEW_LENGTH ? content : content.substring(0, PREVIEW_LENGTH);
    }

    private static Map<String, Object> messageData(LiveMessage message) {
        return LiveJson.map("id", message.id(), "sessionId", message.sessionId(),
                "senderType", message.senderType(), "senderId", message.senderId(),
                "senderName", message.senderName(), "contentType", message.contentType(),
                "content", message.content(), "clientMsgId", message.clientMsgId(),
                "createTime", message.createTime());
    }

    private static Map<String, Object> stateData(LiveSession session) {
        return LiveJson.map("sessionId", session.id(), "userId", session.userId(),
                "userName", session.userName(), "status", session.status(), "source", session.source(),
                "adminId", session.adminId(), "adminName", session.adminName(),
                "agentSessionId", session.agentSessionId(),
                "unreadUser", session.unreadUser(), "unreadAdmin", session.unreadAdmin(),
                "lastMessagePreview", session.lastMessagePreview(), "lastMessageAt", session.lastMessageAt(),
                "createTime", session.createTime());
    }
}
