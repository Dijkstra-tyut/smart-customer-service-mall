package com.supermarket.live;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 在线连接注册表：用户按 userId 归集，管理员按 adminId 归集。
 * <p>
 * 单机内存实现，多实例部署时需要替换为 Redis Pub/Sub 广播。连接发送统一走
 * {@link org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator}，避免并发写同一个 WS 会话。
 */
@Component
public class LiveConnectionRegistry {

    private static final Logger log = LoggerFactory.getLogger(LiveConnectionRegistry.class);

    private final Map<Integer, Set<WebSocketSession>> users = new ConcurrentHashMap<>();
    private final Map<Integer, Set<WebSocketSession>> admins = new ConcurrentHashMap<>();

    public void register(LiveIdentity identity, WebSocketSession outbound) {
        Map<Integer, Set<WebSocketSession>> target = identity.isAdmin() ? admins : users;
        target.computeIfAbsent(identity.userId(), key -> new CopyOnWriteArraySet<>()).add(outbound);
    }

    public void unregister(String webSocketId) {
        users.values().forEach(set -> set.removeIf(session -> session.getId().equals(webSocketId)));
        admins.values().forEach(set -> set.removeIf(session -> session.getId().equals(webSocketId)));
        users.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        admins.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    public int sendToUser(int userId, String payload) {
        return send(users.get(userId), payload);
    }

    public int sendToAdmin(int adminId, String payload) {
        return send(admins.get(adminId), payload);
    }

    public int broadcastAdmins(String payload) {
        int delivered = 0;
        for (Set<WebSocketSession> connections : admins.values()) {
            delivered += send(connections, payload);
        }
        return delivered;
    }

    public int onlineAdmins() {
        return admins.size();
    }

    public boolean isAdminOnline(int adminId) {
        Set<WebSocketSession> connections = admins.get(adminId);
        return connections != null && !connections.isEmpty();
    }

    private int send(Set<WebSocketSession> connections, String payload) {
        if (connections == null || connections.isEmpty()) {
            return 0;
        }
        int delivered = 0;
        for (WebSocketSession session : connections) {
            try {
                if (!session.isOpen()) {
                    unregister(session.getId());
                    continue;
                }
                session.sendMessage(new TextMessage(payload));
                delivered++;
            } catch (IOException | IllegalStateException e) {
                log.warn("实时客服推送失败, wsId={}: {}", session.getId(), e.getMessage());
                unregister(session.getId());
            }
        }
        return delivered;
    }
}
