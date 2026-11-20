package com.supermarket.agent;

import com.supermarket.document.ProductDocument;
import com.supermarket.entity.Order;
import com.supermarket.entity.Product;
import com.supermarket.entity.User;
import com.supermarket.service.OrderService;
import com.supermarket.service.ProductSearchService;
import com.supermarket.service.ProductViewService;
import com.supermarket.service.UserService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.supermarket.agent.AgentJson.*;

/** Internal business facade. The authenticated run, never a model argument, selects the user. */
@Service
public class AgentDataService {
    private final AgentStore store;
    private final UserService users;
    private final OrderService orders;
    private final ProductViewService products;
    private final ProductSearchService search;
    private final AgentMemoryService memory;

    public AgentDataService(AgentStore store, UserService users, OrderService orders,
                            ProductViewService products, ProductSearchService search, AgentMemoryService memory) {
        this.store = store; this.users = users; this.orders = orders; this.products = products; this.search = search;
        this.memory = memory;
    }

    public Map<String, Object> memory(AgentRun supplied) {
        AgentRun run = fresh(supplied);
        return memory.load(run.userId(), run.sessionId());
    }

    public Map<String, Object> saveMemory(AgentRun supplied, Map<String, Object> body) {
        return memory.save(supplied, body);
    }

    public Map<String, Object> conversations(AgentRun supplied, int page, int size) {
        AgentRun run = readable(supplied);
        return memory.conversations(run.userId(), page, size);
    }

    public Map<String, Object> conversation(AgentRun supplied, long sessionId, Long beforeId, int size) {
        AgentRun run = readable(supplied);
        return memory.messages(run.userId(), sessionId, beforeId, size, true);
    }

    public Map<String, Object> context(AgentRun supplied) {
        AgentRun run = fresh(supplied);
        Map<String, Object> background = userContext(run.userId());
        return map("user_id", String.valueOf(run.userId()), "session_id", String.valueOf(run.sessionId()),
                "run_id", run.id(), "status", run.status(), "revision", run.revision(),
                "human_task_id", run.humanTaskId(), "user_context", background,
                "display_name", background.get("display_name"));
    }

    public Map<String, Object> userContext(int userId) {
        User user = users.getById(userId);
        if (user == null || (user.getDeleted() != null && user.getDeleted() != 0))
            throw new AgentException(404, "用户不存在或无权访问");
        if (user.getStatus() == com.supermarket.enums.UserStatusEnum.DISABLED)
            throw new AgentException(403, "当前账号不可使用客服业务服务");
        return map("display_name", user.getName() == null ? "用户" : user.getName());
    }

    public Map<String, Object> listOrders(AgentRun supplied, Integer status, int page, int size) {
        AgentRun run = readable(supplied);
        page = page(page); size = size(size);
        List<Object> args = new ArrayList<>(List.of(run.userId()));
        String filter = "user_id = ? AND is_deleted = 0";
        if (status != null) {
            if (status < 0 || status > 4) throw new AgentException(400, "订单状态不合法");
            filter += " AND status = ?"; args.add(status);
        }
        Map<String, Object> count = store.one("SELECT COUNT(*) AS total FROM oms_order WHERE " + filter, args.toArray());
        args.add(size); args.add((page - 1) * size);
        List<Map<String, Object>> rows = store.rows("SELECT id,order_sn,total_amount,status,create_time FROM oms_order WHERE "
                + filter + " ORDER BY id DESC LIMIT ? OFFSET ?", args.toArray());
        var records = rows.stream().map(row -> map("order_id", row.get("id"), "order_sn", row.get("order_sn"),
                "amount", row.get("total_amount"), "status", orderStatus(row.get("status")),
                "created_at", String.valueOf(row.get("create_time")))).toList();
        return pageResult(records, count.get("total"), page, size);
    }

    public Map<String, Object> order(AgentRun supplied, int id) {
        AgentRun run = readable(supplied);
        Order order;
        try { order = orders.detail(run.userId(), id); }
        catch (com.supermarket.exception.ServiceException error) { throw new AgentException(404, "订单不存在或无权访问"); }
        if (order == null || !Integer.valueOf(run.userId()).equals(order.getUserId()))
            throw new AgentException(404, "订单不存在或无权访问");
        var items = order.getItems() == null ? List.of() : order.getItems().stream().map(item ->
                map("product_id", item.getProductId(), "name", item.getProductName(), "quantity", item.getAmount(),
                        "price", item.getProductPrice())).toList();
        return map("order_id", order.getId(), "order_sn", order.getOrderSn(),
                "status", order.getStatus() == null ? "unknown" : order.getStatus().name().toLowerCase(java.util.Locale.ROOT),
                "amount", order.getTotalAmount(), "items", items, "created_at", order.getCreateTime(),
                "read_at", LocalDateTime.now().toString());
    }

    public Map<String, Object> listProducts(AgentRun run, String keyword, int page, int size) {
        readable(run); page = page(page); size = size(size);
        if (keyword == null || keyword.isBlank() || keyword.length() > 100) throw new AgentException(400, "请提供有效的商品关键词");
        Map<String, Object> found = search.search(keyword, page, size);
        if (found == null) return pageResult(List.of(), 0, page, size);
        Object source = found.get("records");
        List<Map<String, Object>> records = new ArrayList<>();
        if (source instanceof List<?> list) for (Object item : list) {
            if (item instanceof Product p && p.getStatus() == com.supermarket.enums.ProductStatusEnum.ON_SHELF) records.add(productData(p));
            else if (item instanceof ProductDocument p && Integer.valueOf(1).equals(p.getStatus()))
                records.add(map("product_id", p.getId(), "name", p.getName(), "description", p.getDetail(), "price", p.getPrice(), "stock", p.getStock()));
        }
        return pageResult(records, found.getOrDefault("total", records.size()), page, size);
    }

    public Map<String, Object> product(AgentRun run, int id) {
        readable(run);
        Product product = products.detail(id);
        if (product == null || product.getStatus() == null || product.getStatus().getCode() != 1)
            throw new AgentException(404, "商品不存在或已下架");
        return productData(product);
    }

    private Map<String, Object> productData(Product p) {
        return map("product_id", p.getId(), "name", p.getName(), "description", p.getDetail(),
                "price", p.getPrice(), "stock", p.getStock(), "read_at", LocalDateTime.now().toString());
    }

    public Map<String, Object> tickets(AgentRun supplied, int page, int size) {
        AgentRun run = readable(supplied); page = page(page); size = size(size);
        var count = store.one("SELECT COUNT(*) AS total FROM cs_ticket WHERE user_id = ?", run.userId());
        var records = store.rows("SELECT id,summary,priority,status,created_at,updated_at FROM cs_ticket WHERE user_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
                run.userId(), size, (page - 1) * size);
        return pageResult(records, count.get("total"), page, size);
    }

    public Map<String, Object> ticket(AgentRun supplied, String id) {
        AgentRun run = readable(supplied);
        var result = store.one("SELECT id,summary,details,priority,status,created_at,updated_at FROM cs_ticket WHERE id = ? AND user_id = ?", id, run.userId());
        if (result == null) throw new AgentException(404, "工单不存在或无权访问");
        return result;
    }

    public Map<String, Object> action(AgentRun supplied, Map<String, Object> request) {
        String operationId = text(request, "operation_id", 100);
        String kind = text(request, "kind", 32);
        Map<String, Object> args = object(request.get("arguments"));
        if (!Set.of("ticket_create", "ticket_update", "order_cancel").contains(kind))
            throw new AgentException(400, "尚未支持该业务操作");
        String fingerprint = payloadHash(map("kind", kind, "arguments", args));
        return store.transaction(() -> {
            AgentRun run = store.lockRun(supplied.id());
            verifyIdentity(supplied, run);
            store.requireSession(run.sessionId(), run.userId());
            userContext(run.userId());
            var prior = store.one("SELECT * FROM cs_agent_operation WHERE id = ?", operationId);
            if (prior != null) {
                if (!run.id().equals(prior.get("run_id")) || !fingerprint.equals(prior.get("payload_hash")))
                    throw new AgentException(409, "操作标识已经用于其他内容");
                return object(read((String) prior.get("result_json"), Map.of()));
            }
            if (!"running".equals(run.status())) throw new AgentException(409, "当前运行不允许执行业务操作");
            if (number(request, "expected_revision") != run.revision()) throw new AgentException(409, "运行版本已变化");
            // The program policy matches Python: creating a requested ticket is low risk;
            // changing an existing ticket or cancelling an order requires a bound human approval.
            if (!"ticket_create".equals(kind)) verifyApproval(run, request.get("approval_task_id"), fingerprint);
            // Reserve the unique operation before any business work, including cache updates
            // made by an existing service. A competing run then fails before its side effects.
            store.update("INSERT INTO cs_agent_operation(id,run_id,payload_hash,kind,result_json) VALUES (?,?,?,?,?)",
                    operationId, run.id(), fingerprint, kind, "{}");
            Map<String, Object> result = switch (kind) {
                case "ticket_create" -> createTicket(run, args);
                case "ticket_update" -> updateTicket(run, args);
                case "order_cancel" -> cancelOrder(run, args);
                default -> throw new AgentException(400, "不支持的操作");
            };
            store.update("UPDATE cs_agent_operation SET result_json=? WHERE id=?", write(result), operationId);
            return result;
        });
    }

    private void verifyApproval(AgentRun run, Object id, String fingerprint) {
        if (!(id instanceof String taskId)) throw new AgentException(403, "该操作需要人工批准");
        var task = store.one("SELECT * FROM cs_human_task WHERE id = ? AND run_id = ? FOR UPDATE", taskId, run.id());
        if (task == null || Boolean.TRUE.equals(task.get("action_stale"))
                || ((Number) task.get("run_revision")).intValue() != run.revision()
                || !Set.of("applying", "decision_recorded").contains(task.get("status")))
            throw new AgentException(409, "人工批准已失效");
        Map<String, Object> decision = object(read((String) task.get("decision_json"), Map.of()));
        Map<String, Object> proposal = object(read((String) task.get("action_json"), Map.of()));
        if (!"approve_action".equals(decision.get("action"))
                || number(decision, "version") != ((Number) task.get("version")).intValue()
                || !fingerprint.equals(payloadHash(proposal)))
            throw new AgentException(403, "人工批准与当前操作不匹配");
    }

    private Map<String, Object> createTicket(AgentRun run, Map<String, Object> args) {
        String summary = text(args, "summary", 200), details = text(args, "details", 10000);
        String priority = priority(args.getOrDefault("priority", "medium"));
        String id = "TK-" + UUID.randomUUID();
        store.update("INSERT INTO cs_ticket(id,user_id,session_id,run_id,summary,details,priority,status) VALUES (?,?,?,?,?,?,?,?)",
                id, run.userId(), run.sessionId(), run.id(), summary, details, priority, "created");
        return map("ticket_id", id, "status", "created", "summary", summary, "priority", priority);
    }

    private Map<String, Object> updateTicket(AgentRun run, Map<String, Object> args) {
        String id = text(args, "ticket_id", 64);
        var ticket = store.one("SELECT priority,status FROM cs_ticket WHERE id = ? AND user_id = ? FOR UPDATE", id, run.userId());
        if (ticket == null || "closed".equals(ticket.get("status"))) throw new AgentException(404, "工单不存在、已关闭或无权操作");
        if (!args.containsKey("priority") && !args.containsKey("status")) throw new AgentException(400, "请指定工单优先级或状态");
        String priority = args.containsKey("priority") ? priority(args.get("priority")) : (String) ticket.get("priority");
        Object rawStatus = args.getOrDefault("status", ticket.get("status"));
        if (!(rawStatus instanceof String status) || !Set.of("created", "processing", "pending_review", "resolved", "closed", "escalated").contains(status))
            throw new AgentException(400, "工单状态不合法");
        store.update("UPDATE cs_ticket SET priority = ?,status = ?,updated_at = ? WHERE id = ? AND user_id = ?",
                priority, status, LocalDateTime.now(), id, run.userId());
        return map("ticket_id", id, "priority", priority, "status", status);
    }

    private Map<String, Object> cancelOrder(AgentRun run, Map<String, Object> args) {
        int id = number(args, "order_id");
        var order = store.one("SELECT id FROM oms_order WHERE id = ? AND user_id = ? AND is_deleted = 0 FOR UPDATE", id, run.userId());
        if (order == null) throw new AgentException(404, "订单不存在或无权操作");
        orders.cancel(id);
        return map("order_id", id, "status", "cancelled");
    }

    AgentRun fresh(AgentRun supplied) {
        AgentRun run = store.run(supplied.id()); verifyIdentity(supplied, run);
        store.requireSession(run.sessionId(), run.userId());
        return run;
    }

    private AgentRun readable(AgentRun supplied) {
        AgentRun run = fresh(supplied);
        if (!"running".equals(run.status())) throw new AgentException(409, "当前运行不允许读取业务信息");
        return run;
    }

    static void verifyIdentity(AgentRun supplied, AgentRun run) {
        if (run == null || supplied.userId() != run.userId() || supplied.sessionId() != run.sessionId())
            throw new AgentException(403, "运行身份不匹配");
    }

    private String priority(Object value) {
        if (!(value instanceof String text) || !Set.of("low", "medium", "high", "urgent").contains(text))
            throw new AgentException(400, "工单优先级不合法");
        return text;
    }

    static int page(int page) { if (page < 1 || page > 10000) throw new AgentException(400, "页码不合法"); return page; }
    static int size(int size) { if (size < 1 || size > 50) throw new AgentException(400, "每页数量应为 1 至 50"); return size; }
    static Map<String, Object> pageResult(Object records, Object total, int page, int size) {
        return map("items", records, "total", total, "page", page, "size", size, "read_at", LocalDateTime.now().toString());
    }
    private String orderStatus(Object value) {
        int code = ((Number) value).intValue();
        return code >= 0 && code < 5 ? List.of("pending_pay", "paid", "shipped", "completed", "cancelled").get(code) : "unknown";
    }
}
