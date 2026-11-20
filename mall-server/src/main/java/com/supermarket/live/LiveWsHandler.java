package com.supermarket.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.supermarket.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;

/**
 * 实时客服 WebSocket 入口：/ws/cs?token=xxx
 * <p>
 * 同一端点上按握手身份分流：user 只能操作自己的会话，admin 走抢单/回复/关闭。
 * 每条上行消息独立捕获异常，单条失败不会断开连接。
 */
@Component
public class LiveWsHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(LiveWsHandler.class);
    private static final String OUTBOUND = "liveOutbound";
    private static final int SEND_TIME_LIMIT_MS = 5000;
    private static final int SEND_BUFFER_BYTES = 512 * 1024;

    private final LiveChatService service;
    private final LiveConnectionRegistry registry;

    public LiveWsHandler(LiveChatService service, LiveConnectionRegistry registry) {
        this.service = service;
        this.registry = registry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (!(session.getAttributes().get(LiveHandshakeInterceptor.IDENTITY) instanceof LiveIdentity identity)) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        WebSocketSession outbound = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_BYTES);
        session.getAttributes().put(OUTBOUND, outbound);
        registry.register(identity, outbound);

        Map<String, Object> data = LiveJson.map("userId", identity.userId(), "role", identity.role(),
                "displayName", identity.displayName(), "serverTime", System.currentTimeMillis());
        if (identity.isAdmin()) {
            data.put("waiting", service.stats().get(LiveStatus.WAITING.code()));
            data.put("onlineAdmins", registry.onlineAdmins());
        }
        send(session, "connected", null, data);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage textMessage) {
        LiveIdentity identity = (LiveIdentity) session.getAttributes().get(LiveHandshakeInterceptor.IDENTITY);
        if (identity == null) {
            return;
        }
        String requestId = null;
        try {
            JsonNode root = LiveJson.parse(textMessage.getPayload());
            String type = LiveJson.text(root, "type", 32, true);
            requestId = LiveJson.text(root, "requestId", 64, false);
            JsonNode data = root.path("data");
            switch (type) {
                case "ping" -> send(session, "pong", requestId,
                        LiveJson.map("serverTime", System.currentTimeMillis()));
                case "chat.send" -> chatSend(session, identity, requestId, data);
                case "session.current" -> current(session, identity, requestId, data);
                case "session.claim" -> claim(session, identity, requestId, data);
                case "session.close" -> close(session, identity, requestId, data);
                case "session.read" -> read(session, identity, requestId, data);
                case "session.typing" -> typing(session, identity, data);
                default -> error(session, requestId, 400, "不支持的消息类型: " + type);
            }
        } catch (ServiceException e) {
            error(session, requestId, e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("实时客服消息处理失败, wsId={}", session.getId(), e);
            error(session, requestId, 500, "服务器内部错误");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.unregister(session.getId());
        log.debug("实时客服连接关闭, wsId={}, status={}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        registry.unregister(session.getId());
        log.warn("实时客服连接异常, wsId={}: {}", session.getId(), exception.getMessage());
    }

    // ------------------------------------------------------------------ 指令

    private void chatSend(WebSocketSession session, LiveIdentity identity, String requestId, JsonNode data) {
        Long sessionId = LiveJson.longOrNull(data, "sessionId");
        String clientMsgId = LiveJson.text(data, "clientMsgId", 64, false);
        String contentType = LiveJson.text(data, "contentType", 16, false);
        String content = LiveJson.text(data, "content", 2000, true);

        LiveChatService.SendOutcome outcome = identity.isAdmin()
                ? service.sendAdminMessage(identity.userId(), requireSessionId(sessionId), clientMsgId, contentType, content)
                : service.sendUserMessage(identity.userId(), sessionId, clientMsgId, contentType, content);

        send(session, "chat.ack", requestId, LiveJson.map(
                "sessionId", outcome.message().sessionId(),
                "messageId", outcome.message().id(),
                "clientMsgId", outcome.message().clientMsgId(),
                "duplicate", outcome.duplicate()));
    }

    private void current(WebSocketSession session, LiveIdentity identity, String requestId, JsonNode data) {
        LiveSession liveSession = identity.isAdmin()
                ? service.session(requireSessionId(LiveJson.longOrNull(data, "sessionId")),
                        LiveIdentity.ROLE_ADMIN, identity.userId())
                : service.currentSession(identity.userId());
        send(session, "session.state", requestId,
                liveSession == null ? LiveJson.map("sessionId", null) : service.stateView(liveSession));
    }

    private void claim(WebSocketSession session, LiveIdentity identity, String requestId, JsonNode data) {
        requireAdmin(identity);
        LiveSession claimed = service.claim(identity.userId(), requireSessionId(LiveJson.longOrNull(data, "sessionId")));
        send(session, "session.claimed", requestId, service.stateView(claimed));
    }

    private void close(WebSocketSession session, LiveIdentity identity, String requestId, JsonNode data) {
        String reason = LiveJson.text(data, "reason", 64, false);
        LiveSession closed = service.close(requireSessionId(LiveJson.longOrNull(data, "sessionId")),
                identity.role(), identity.userId(), reason);
        send(session, "session.closed", requestId, LiveJson.map(
                "sessionId", closed.id(), "status", closed.status(),
                "reason", closed.closeReason(), "closedBy", identity.role()));
    }

    private void read(WebSocketSession session, LiveIdentity identity, String requestId, JsonNode data) {
        Map<String, Object> result = service.markRead(requireSessionId(LiveJson.longOrNull(data, "sessionId")),
                identity.role(), identity.userId(), LiveJson.longOrNull(data, "upToId"));
        send(session, "session.read.ack", requestId, result);
    }

    private void typing(WebSocketSession session, LiveIdentity identity, JsonNode data) {
        service.typing(requireSessionId(LiveJson.longOrNull(data, "sessionId")), identity.role(), identity.userId());
    }

    // ------------------------------------------------------------------ 工具

    private static long requireSessionId(Long sessionId) {
        if (sessionId == null) {
            throw new ServiceException(400, "sessionId 不能为空");
        }
        return sessionId;
    }

    private static void requireAdmin(LiveIdentity identity) {
        if (!identity.isAdmin()) {
            throw new ServiceException(403, "仅管理员可执行该操作");
        }
    }

    private void error(WebSocketSession session, String requestId, int code, String message) {
        send(session, "error", requestId, LiveJson.map("code", code, "message", message));
    }

    private void send(WebSocketSession session, String type, String requestId, Map<String, Object> data) {
        WebSocketSession outbound = (WebSocketSession) session.getAttributes().get(OUTBOUND);
        try {
            if (outbound != null && outbound.isOpen()) {
                outbound.sendMessage(new TextMessage(LiveJson.envelope(type, requestId, data)));
            }
        } catch (IOException | IllegalStateException e) {
            log.warn("实时客服下行失败, wsId={}: {}", session.getId(), e.getMessage());
        }
    }
}
