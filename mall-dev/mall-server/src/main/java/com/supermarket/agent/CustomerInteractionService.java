package com.supermarket.agent;

import com.supermarket.live.LiveChatService;
import com.supermarket.live.LiveIdentity;
import com.supermarket.live.LiveSession;
import com.supermarket.service.OrderService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.supermarket.agent.AgentJson.*;

/** 用户点击后的明确入口：聊天意图由 Agent 识别，实际接管和申请由 Java 办理。 */
@Service
public class CustomerInteractionService {
    private final AgentStore store;
    private final AgentRuntimeService runtime;
    private final LiveChatService live;
    private final OrderService orders;

    public CustomerInteractionService(AgentStore store, AgentRuntimeService runtime, LiveChatService live, OrderService orders) {
        this.store = store; this.runtime = runtime; this.live = live; this.orders = orders;
    }

    public LiveSession current(long conversationId, int userId) {
        store.requireSession(conversationId, userId);
        var row = store.one("SELECT id FROM cs_live_session WHERE agent_session_id=? AND user_id=? AND is_deleted=0 ORDER BY id DESC LIMIT 1", conversationId, userId);
        return row == null ? null : live.session(((Number) row.get("id")).longValue(), LiveIdentity.ROLE_USER, userId);
    }

    public LiveSession handoff(long conversationId, int userId) {
        LiveSession result = store.transaction(() -> {
            store.lockSession(conversationId, userId);
            // 结束旧 AI 运行并使旧审批失效，真人接管期间不能并行办理旧方案。
            runtime.cancel(conversationId, userId);
            LiveSession session = live.createSession(userId);
            store.one("SELECT id FROM cs_live_session WHERE id=? FOR UPDATE", session.id());
            var linked = store.one("SELECT agent_session_id FROM cs_live_session WHERE id=?", session.id());
            if (linked.get("agent_session_id") instanceof Number id && id.longValue() != conversationId)
                throw new AgentException(409, "另一个会话正在人工服务，请先结束该会话");
            store.update("UPDATE cs_live_session SET agent_session_id=? WHERE id=?", conversationId, session.id());
            return live.session(session.id(), LiveIdentity.ROLE_USER, userId);
        });
        live.announce(result);
        return result;
    }

    public List<Map<String, Object>> context(long liveId, int adminId) {
        LiveSession session = live.session(liveId, LiveIdentity.ROLE_ADMIN, adminId);
        if (session.agentSessionId() == null) return List.of();
        var rows = store.rows("SELECT m.type,m.content,m.create_time FROM cs_message m LEFT JOIN cs_live_message l ON l.agent_message_id=m.id WHERE m.session_id=? AND l.id IS NULL ORDER BY m.id DESC LIMIT 20", session.agentSessionId());
        java.util.Collections.reverse(rows);
        return rows;
    }

    public void close(long conversationId, int userId) {
        LiveSession session = current(conversationId, userId);
        if (session != null && !session.isClosed()) live.close(session.id(), LiveIdentity.ROLE_USER, userId, "user_return_to_ai");
    }

    public Map<String, Object> afterSales(long conversationId, int userId, Map<String, Object> body) {
        String reason = text(body, "reason", 2000), requestId = text(body, "request_id", 64);
        int orderId = number(body, "order_id");
        if (!requestId.matches("[a-zA-Z0-9_-]+") || orderId <= 0) throw new AgentException(400, "售后申请参数不合法");
        return store.transaction(() -> {
            store.lockSession(conversationId, userId);
            var order = orders.detail(userId, orderId);
            if (order == null || !Integer.valueOf(userId).equals(order.getUserId())) throw new AgentException(404, "订单不存在或无权访问");
            if (order.getStatus() == null || !java.util.Set.of(1, 2, 3).contains(order.getStatus().getCode()))
                throw new AgentException(400, "该订单当前状态不支持提交售后申请");
            String id = "AS-" + UUID.nameUUIDFromBytes((userId + ":" + requestId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String details = "订单编号：" + orderId + "\n申请说明：" + reason;
            var existing = store.one("SELECT session_id,details FROM cs_ticket WHERE id=? AND user_id=?", id, userId);
            if (existing == null) {
                store.update("INSERT INTO cs_ticket(id,user_id,session_id,run_id,summary,details,priority,status) VALUES (?,?,?,?,?,?,?,?)",
                    id, userId, conversationId, "customer-button", "用户提交售后申请", details, "medium", "created");
                store.insertMessage(conversationId, "user", "提交售后申请：" + details);
                store.insertMessage(conversationId, "system", "售后申请已提交，工单编号：" + id + "。等待核实，尚未完成退货或退款。");
            }
            else if (((Number) existing.get("session_id")).longValue() != conversationId || !details.equals(existing.get("details")))
                throw new AgentException(409, "该申请编号已用于其他内容");
            return map("ticket_id", id, "status", "created", "message", "售后申请已提交，等待核实；尚未完成退货或退款。");
        });
    }
}
