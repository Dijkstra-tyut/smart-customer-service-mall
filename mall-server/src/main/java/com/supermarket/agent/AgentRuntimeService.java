package com.supermarket.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.supermarket.agent.AgentJson.*;

/** Durable Java coordination. Database transactions never include HTTP or model calls. */
@Service
public class AgentRuntimeService {
    private static final Set<String> REASONS = Set.of("sensitive_action", "user_requested", "review_failed");
    private static final Set<String> DECISIONS = Set.of("approve_action", "reject_action", "send_reply", "request_info");
    // Keep these deterministic rules aligned with Python text_violations. Validation
    // must happen before recording an immutable human decision, not after delivery.
    private static final List<Pattern> HUMAN_TEXT_RULES = List.of(
            Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)", Pattern.UNICODE_CHARACTER_CLASS),
            Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+", Pattern.UNICODE_CHARACTER_CLASS),
            Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)", Pattern.UNICODE_CHARACTER_CLASS));
    private final AgentStore store;
    private final AgentDataService data;
    private final WebClient python;
    private final String serviceToken;
    private final long tokenSeconds;
    private final long leaseSeconds;
    private final SecureRandom random = new SecureRandom();

    public AgentRuntimeService(AgentStore store, AgentDataService data, WebClient customerWebClient,
                               @Value("${mall.customer.service-token:}") String serviceToken,
                               @Value("${mall.customer.token-ttl-seconds:3600}") long tokenSeconds,
                               @Value("${mall.customer.resume-lease-seconds:300}") long leaseSeconds) {
        this.store = store; this.data = data; this.python = customerWebClient;
        this.serviceToken = serviceToken; this.tokenSeconds = tokenSeconds; this.leaseSeconds = leaseSeconds;
    }

    public Flux<String> chat(long sessionId, String message, int userId) {
        requireConfigured();
        if (message == null || message.isBlank() || message.length() > 10000) throw new AgentException(400, "消息不能为空且不能超过一万字");
        Map<String, Object> request = beginChat(sessionId, message, userId);
        if (Boolean.TRUE.equals(request.get("supplement"))) {
            return Flux.just(write(map("type", "interrupted", "run_id", request.get("run_id"), "status", "waiting_human",
                    "human_task_id", request.get("human_task_id"), "revision", request.get("revision"),
                    "content", "已将补充信息交给人工处理，请等待回复。")),
                    write(map("type", "done", "run_id", request.get("run_id"), "status", "waiting_human")));
        }
        String runId = (String) request.get("run_id");
        // autoConnect deliberately retains the upstream subscription after a browser disconnect.
        // Python publishes to /outcome independently; no message is persisted from stream completion.
        return python.post().uri("/api/chat")
                .header("X-Agent-Service-Token", serviceToken).contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM).bodyValue(request).retrieve().bodyToFlux(String.class)
                .filter(piece -> !piece.isBlank() && !"[DONE]".equals(piece.trim()))
                .map(this::validateEvent)
                .onErrorResume(error -> {
                    noteTransportError(runId);
                    return Flux.just(write(map("type", "error", "run_id", runId, "status", "failed",
                            "code", "AGENT_UNAVAILABLE", "content", "客服处理连接异常，请刷新查看处理结果。")),
                            write(map("type", "done", "run_id", runId, "status", "failed")));
                }).subscribeOn(Schedulers.boundedElastic()).replay(32).autoConnect(0);
    }

    public Map<String, Object> beginChat(long sessionId, String message, int userId) {
        requireConfigured();
        return store.transaction(() -> {
            var session = store.lockSession(sessionId, userId);
            if (store.one("SELECT id FROM cs_live_session WHERE agent_session_id=? AND status IN ('waiting','serving') AND is_deleted=0", sessionId) != null)
                throw new AgentException(409, "当前处于人工服务，请结束人工会话后再使用智能客服");
            var activeRow = store.one("SELECT * FROM cs_agent_run WHERE active_session_id = ? FOR UPDATE", sessionId);
            AgentRun active = AgentStore.toRun(activeRow);
            if (active != null && "running".equals(active.status())) throw new AgentException(409, "当前请求仍在处理中，请稍后再发送");
            if (active != null && "waiting_human".equals(active.status())) {
                var task = store.one("SELECT status FROM cs_human_task WHERE id = ? FOR UPDATE", active.humanTaskId());
                if (task == null || !"pending".equals(task.get("status"))) throw new AgentException(409, "人工决定正在处理中，请稍后补充");
            }
            long messageId = store.insertMessage(sessionId, "user", message);
            String title = "新对话".equals(session.get("title")) ? message.substring(0, Math.min(message.length(), 20)) : (String) session.get("title");
            store.update("UPDATE cs_session SET title = ?,update_time = ? WHERE id = ?", title, LocalDateTime.now(), sessionId);
            if (active != null && "waiting_human".equals(active.status())) {
                store.update("UPDATE cs_agent_run SET revision = revision + 1,updated_at = ? WHERE id = ?", LocalDateTime.now(), active.id());
                store.update("UPDATE cs_human_task SET version = version + 1,action_stale = TRUE,updated_at = ? WHERE id = ? AND status = 'pending'",
                        LocalDateTime.now(), active.humanTaskId());
                return map("supplement", true, "run_id", active.id(), "human_task_id", active.humanTaskId(), "revision", active.revision() + 1);
            }
            String runId = active == null ? UUID.randomUUID().toString() : active.id();
            int revision = active == null ? 1 : active.revision() + 1;
            String token = token();
            if (active == null) {
                store.update("INSERT INTO cs_agent_run(id,session_id,user_id,input_message_id,active_session_id,status,revision,token_hash,token_expires_at) VALUES (?,?,?,?,?,?,?,?,?)",
                        runId, sessionId, userId, messageId, sessionId, "running", revision, hash(token), LocalDateTime.now().plusSeconds(tokenSeconds));
            } else {
                store.update("UPDATE cs_agent_run SET status = 'running',input_message_id = ?,revision = ?,token_hash = ?,token_expires_at = ?,human_task_id = NULL,reason = NULL,last_error = NULL,updated_at = ? WHERE id = ?",
                        messageId, revision, hash(token), LocalDateTime.now().plusSeconds(tokenSeconds), LocalDateTime.now(), runId);
            }
            return requestFor(new AgentRun(runId, sessionId, userId, "running", revision, null), token,
                    map("message", message, "message_id", String.valueOf(messageId), "request_id", "message:" + messageId));
        });
    }

    private String validateEvent(String event) {
        Map<String, Object> parsed = object(read(event, Map.of()));
        if (!Set.of("status", "message", "interrupted", "error", "done").contains(parsed.get("type")))
            throw new IllegalArgumentException("智能体返回未知事件");
        // Only the defined event envelope crosses into the browser.
        return write(map("type", parsed.get("type"), "run_id", parsed.get("run_id"), "status", parsed.get("status"),
                "content", parsed.get("content"), "message_id", parsed.get("message_id"),
                "human_task_id", parsed.get("human_task_id"), "revision", parsed.get("revision"), "code", parsed.get("code"),
                "actions", CustomerActions.normalize(parsed.get("actions"))));
    }

    private void noteTransportError(String runId) {
        store.update("UPDATE cs_agent_run SET last_error = ?,updated_at = ? WHERE id = ? AND status = 'running'",
                "调用智能体失败；请核对运行结果后重试", LocalDateTime.now(), runId);
    }

    public Map<String, Object> portalRun(long sessionId, int userId) {
        store.requireSession(sessionId, userId);
        // created_at 可能只有秒精度，UUID 的字典顺序也不能表达运行先后。
        var run = store.one("SELECT * FROM cs_agent_run WHERE session_id = ? ORDER BY (input_message_id IS NOT NULL) DESC,input_message_id DESC,created_at DESC,id DESC LIMIT 1", sessionId);
        if (run == null) return map("status", "idle", "sessionId", String.valueOf(sessionId));
        var message = store.one("SELECT am.message_key,m.content FROM cs_agent_message am JOIN cs_message m ON m.id = am.message_id WHERE am.run_id = ? ORDER BY m.id DESC LIMIT 1", run.get("id"));
        return map("runId", run.get("id"), "sessionId", String.valueOf(sessionId), "status", run.get("status"),
                "revision", run.get("revision"), "humanTaskId", run.get("human_task_id"), "reason", run.get("reason"),
                "messageId", message == null ? null : message.get("message_key"), "reply", message == null ? null : message.get("content"),
                "actions", CustomerActions.normalize(read((String) run.get("actions_json"), List.of())),
                "updatedAt", String.valueOf(run.get("updated_at")));
    }

    public Map<String, Object> cancel(long sessionId, int userId) {
        return store.transaction(() -> {
            store.lockSession(sessionId, userId);
            var row = store.one("SELECT * FROM cs_agent_run WHERE active_session_id = ? FOR UPDATE", sessionId);
            if (row == null) return map("status", "idle");
            AgentRun run = AgentStore.toRun(row);
            store.update("UPDATE cs_agent_run SET status = 'cancelled',active_session_id = NULL,revision = revision + 1,updated_at = ? WHERE id = ?", LocalDateTime.now(), run.id());
            store.update("UPDATE cs_human_task SET status = 'cancelled',action_stale = TRUE,version = version + 1,updated_at = ? WHERE run_id = ? AND status <> 'applied'", LocalDateTime.now(), run.id());
            return map("runId", run.id(), "status", "cancelled", "revision", run.revision() + 1);
        });
    }

    public Map<String, Object> prepareHuman(AgentRun supplied, Map<String, Object> request) {
        String id = text(request, "task_id", 80), reason = text(request, "reason", 40);
        if (!REASONS.contains(reason) || !supplied.id().equals(request.get("run_id"))) throw new AgentException(400, "人工任务原因或运行标识不合法");
        String draft = optionalText(request, "draft", 12000);
        Object proposal = request.get("action_proposal");
        if ("sensitive_action".equals(reason)) {
            var action = object(proposal);
            String kind = text(action, "kind", 32);
            if (!Set.of("order_cancel", "ticket_update").contains(kind)) throw new AgentException(400, "不支持该敏感操作");
            object(action.get("arguments"));
        }
        String fingerprint = payloadHash(request);
        return store.transaction(() -> {
            AgentRun run = lockCurrent(supplied, number(request, "revision"));
            var prior = store.one("SELECT * FROM cs_human_task WHERE id = ?", id);
            if (prior != null) {
                if (!run.id().equals(prior.get("run_id")) || !fingerprint.equals(prior.get("payload_hash")))
                    throw new AgentException(409, "人工任务标识对应内容不一致");
                return map("task_id", id, "version", prior.get("version"));
            }
            if (!"running".equals(run.status())) throw new AgentException(409, "当前运行不允许创建人工任务");
            store.update("INSERT INTO cs_human_task(id,run_id,run_revision,version,reason,status,payload_hash,draft,review_json,evidence_json,action_json) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    id, run.id(), run.revision(), 1, reason, "preparing", fingerprint, draft,
                    write(request.getOrDefault("review", Map.of())), write(request.getOrDefault("evidence", List.of())), proposal == null ? null : write(proposal));
            store.update("UPDATE cs_agent_run SET human_task_id = ?,reason = ?,updated_at = ? WHERE id = ?", id, reason, LocalDateTime.now(), run.id());
            return map("task_id", id, "version", 1);
        });
    }

    public Map<String, Object> activateHuman(AgentRun supplied, String id, Map<String, Object> request) {
        return store.transaction(() -> {
            AgentRun run = store.lockRun(supplied.id()); AgentDataService.verifyIdentity(supplied, run);
            var task = store.one("SELECT * FROM cs_human_task WHERE id = ? AND run_id = ? FOR UPDATE", id, run.id());
            if (task == null || number(request, "version") != ((Number) task.get("version")).intValue()
                    || ((Number) task.get("run_revision")).intValue() != run.revision()) throw new AgentException(409, "人工任务版本已变化");
            if ("pending".equals(task.get("status")) && "waiting_human".equals(run.status()))
                return map("task_id", id, "version", task.get("version"), "status", "pending");
            if (!"preparing".equals(task.get("status")) || !"running".equals(run.status())) throw new AgentException(409, "人工任务不能激活");
            store.update("UPDATE cs_human_task SET status = 'pending',updated_at = ? WHERE id = ?", LocalDateTime.now(), id);
            store.update("UPDATE cs_agent_run SET status = 'waiting_human',updated_at = ? WHERE id = ?", LocalDateTime.now(), run.id());
            return map("task_id", id, "version", task.get("version"), "status", "pending");
        });
    }

    public Map<String, Object> outcome(AgentRun supplied, String id, Map<String, Object> request) {
        String status = text(request, "status", 32);
        if (!Set.of("completed", "waiting_user", "waiting_human", "failed").contains(status)) throw new AgentException(400, "结果状态不合法");
        return store.transaction(() -> {
            AgentRun run = lockCurrent(supplied, number(request, "expected_revision"));
            if (!run.id().equals(id) || (request.get("run_id") != null && !run.id().equals(request.get("run_id")))) throw new AgentException(403, "运行标识不匹配");
            if ("cancelled".equals(run.status())) throw new AgentException(409, "运行已取消");
            String reply = optionalText(request, "reply", 12000);
            String messageId = reply.isBlank() ? null : text(request, "message_id", 160);
            String origin = "human".equals(request.get("author_type")) ? "human" : "assistant";
            List<String> actions = CustomerActions.normalize(request.get("actions"));
            if (run.humanTaskId() != null) {
                var human = store.one("SELECT decision_json FROM cs_human_task WHERE id = ?", run.humanTaskId());
                if (human != null && human.get("decision_json") != null) {
                    var decision = object(read((String) human.get("decision_json"), Map.of()));
                    if (Set.of("send_reply", "request_info").contains(decision.get("action"))) {
                        if (!reply.equals(decision.get("reply"))) throw new AgentException(409, "回复与人工确认终稿不一致");
                        origin = "human";
                    }
                }
            }
            var old = messageId == null ? null : store.one("SELECT am.run_id,m.content FROM cs_agent_message am JOIN cs_message m ON m.id = am.message_id WHERE message_key = ?", messageId);
            if (old != null && (!run.id().equals(old.get("run_id")) || !reply.equals(old.get("content"))))
                throw new AgentException(409, "回复标识对应内容不一致");
            if (run.terminal() && (!run.status().equals(status) || (messageId != null && old == null))) throw new AgentException(409, "运行已经结束");
            if (!reply.isBlank() && old == null) {
                long persisted = store.insertMessage(run.sessionId(), "assistant", reply);
                store.update("INSERT INTO cs_agent_message(message_key,run_id,message_id,author_type) VALUES (?,?,?,?)", messageId, run.id(), persisted, origin);
            }
            boolean terminal = Set.of("completed", "failed").contains(status);
            store.update("UPDATE cs_agent_run SET status = ?,active_session_id = ?,actions_json=?,last_error = NULL,updated_at = ? WHERE id = ?", status,
                    terminal ? null : run.sessionId(), write(actions), LocalDateTime.now(), run.id());
            if (run.humanTaskId() != null && !"waiting_human".equals(status))
                store.update("UPDATE cs_human_task SET status = 'applied',lease_until = NULL,last_error = NULL,updated_at = ? WHERE id = ? AND decision_id IS NOT NULL", LocalDateTime.now(), run.humanTaskId());
            return map("run_id", run.id(), "status", status, "reply", reply.isBlank() ? null : reply,
                    "message_id", messageId, "human_task_id", run.humanTaskId(), "revision", run.revision(), "reason", request.get("reason"), "actions", actions);
        });
    }

    public Map<String, Object> tasks(String status, int page, int size) {
        AgentDataService.page(page); AgentDataService.size(size);
        if (status != null && !Set.of("preparing", "pending", "decision_recorded", "applying", "resume_failed", "applied", "cancelled").contains(status))
            throw new AgentException(400, "人工任务状态不合法");
        String where = status == null ? "" : " WHERE t.status = ?";
        Object[] countArgs = status == null ? new Object[]{} : new Object[]{status};
        Object total = store.one("SELECT COUNT(*) AS total FROM cs_human_task t" + where, countArgs).get("total");
        Object[] args = status == null ? new Object[]{size, (page - 1) * size} : new Object[]{status, size, (page - 1) * size};
        var records = store.rows("SELECT t.*,r.session_id,r.user_id FROM cs_human_task t JOIN cs_agent_run r ON r.id=t.run_id" + where + " ORDER BY t.created_at DESC LIMIT ? OFFSET ?", args)
                .stream().map(row -> taskDto(row, false)).toList();
        return map("records", records, "total", total, "page", page, "size", size);
    }

    public Map<String, Object> task(String id) {
        var row = store.one("SELECT t.*,r.session_id,r.user_id FROM cs_human_task t JOIN cs_agent_run r ON r.id=t.run_id WHERE t.id = ?", id);
        if (row == null) throw new AgentException(404, "人工任务不存在");
        return taskDto(row, true);
    }

    private Map<String, Object> taskDto(Map<String, Object> row, boolean detail) {
        var result = map("id", row.get("id"), "runId", row.get("run_id"), "sessionId", String.valueOf(row.get("session_id")),
                "userId", row.get("user_id"), "reason", row.get("reason"), "status", row.get("status"), "version", row.get("version"),
                "draft", row.get("draft"), "review", read((String) row.get("review_json"), Map.of()),
                "evidence", read((String) row.get("evidence_json"), List.of()), "actionProposal", read((String) row.get("action_json"), null),
                "actionStale", row.get("action_stale"), "decision", read((String) row.get("decision_json"), null),
                "error", row.get("last_error"), "createdAt", String.valueOf(row.get("created_at")), "updatedAt", String.valueOf(row.get("updated_at")));
        if (detail) result.put("conversation", store.history(((Number) row.get("session_id")).longValue(), 100));
        return result;
    }

    public Map<String, Object> decide(String id, Map<String, Object> request, int operator) {
        String decisionId = text(request, "decisionId", 80), action = text(request, "action", 32);
        if (!DECISIONS.contains(action)) throw new AgentException(400, "不支持的人工决定");
        String reply = optionalText(request, "reply", 12000), comment = optionalText(request, "comment", 2000);
        if (Set.of("send_reply", "request_info").contains(action) && reply.isBlank()) throw new AgentException(400, "请填写人工回复");
        if (!reply.isBlank() && !Set.of("send_reply", "request_info").contains(action)) throw new AgentException(400, "批准或拒绝动作不能同时发送回复");
        if (HUMAN_TEXT_RULES.stream().anyMatch(rule -> rule.matcher(reply).find()))
            throw new AgentException(400, "回复包含未脱敏的手机号、邮箱或身份证号，请修改后提交");
        return store.transaction(() -> {
            var existing = store.one("SELECT * FROM cs_human_task WHERE id = ?", id);
            if (existing == null) throw new AgentException(404, "人工任务不存在");
            AgentRun preliminary = store.run((String) existing.get("run_id"));
            store.lockSession(preliminary.sessionId(), preliminary.userId());
            AgentRun run = store.lockRun(preliminary.id());
            var task = store.one("SELECT * FROM cs_human_task WHERE id = ? FOR UPDATE", id);
            var decision = map("decision_id", decisionId, "task_id", id, "version", number(request, "version"), "action", action, "reply", reply, "comment", comment);
            if (task.get("decision_id") != null) {
                if (decisionId.equals(task.get("decision_id")) && payloadHash(decision).equals(payloadHash(read((String) task.get("decision_json"), Map.of())))) return task(id);
                throw new AgentException(409, "该任务已经由其他决定处理");
            }
            if (!"pending".equals(task.get("status")) || !"waiting_human".equals(run.status())
                    || number(request, "version") != ((Number) task.get("version")).intValue()) throw new AgentException(409, "任务状态或版本已变化，请刷新");
            if ("approve_action".equals(action) && (task.get("action_json") == null || Boolean.TRUE.equals(task.get("action_stale"))))
                throw new AgentException(409, "当前操作提案已失效或不存在，请要求补充信息或直接回复");
            if ("reject_action".equals(action) && task.get("action_json") == null) throw new AgentException(400, "该任务没有可拒绝的操作");
            store.update("UPDATE cs_human_task SET status='decision_recorded',decision_id=?,decision_json=?,decided_by=?,next_attempt_at=?,last_error=NULL,updated_at=? WHERE id=?",
                    decisionId, write(decision), operator, LocalDateTime.now(), LocalDateTime.now(), id);
            return task(id);
        });
    }

    public Map<String, Object> retry(String id) {
        return store.transaction(() -> {
            var task = store.one("SELECT * FROM cs_human_task WHERE id=? FOR UPDATE", id);
            if (task == null) throw new AgentException(404, "人工任务不存在");
            if (!Set.of("decision_recorded", "resume_failed").contains(task.get("status")) || task.get("decision_id") == null)
                throw new AgentException(409, "当前任务不能重试");
            // Respect the previous attempt's lease even on a manual retry: an HTTP
            // timeout does not prove that the remote graph has stopped executing.
            Object next = task.get("next_attempt_at");
            if (next instanceof java.sql.Timestamp stamp && stamp.toLocalDateTime().isAfter(LocalDateTime.now()))
                throw new AgentException(409, "上次恢复仍在确认结果，请稍后重试");
            store.update("UPDATE cs_human_task SET status='decision_recorded',attempts=0,next_attempt_at=?,lease_until=NULL,last_error=NULL,updated_at=? WHERE id=?",
                    LocalDateTime.now(), LocalDateTime.now(), id);
            return task(id);
        });
    }

    public List<String> dueDecisions() {
        return store.rows("SELECT id FROM cs_human_task WHERE attempts < 5 AND decision_id IS NOT NULL AND ((status IN ('decision_recorded','resume_failed') AND (next_attempt_at IS NULL OR next_attempt_at <= ?)) OR (status='applying' AND lease_until < ?)) ORDER BY created_at LIMIT 10",
                LocalDateTime.now(), LocalDateTime.now()).stream().map(row -> (String) row.get("id")).toList();
    }

    public List<String> preparingTasks() {
        return store.rows("SELECT t.id FROM cs_human_task t JOIN cs_agent_run r ON r.id=t.run_id WHERE t.status='preparing' AND r.status='running' AND t.created_at < ? ORDER BY t.created_at LIMIT 10",
                LocalDateTime.now().minusSeconds(10)).stream().map(row -> (String) row.get("id")).toList();
    }

    /** Repairs the crash window after checkpoint commit and before the activation callback. */
    public void reconcilePreparing(String id) {
        requireConfigured();
        var task = store.one("SELECT * FROM cs_human_task WHERE id=? AND status='preparing'", id);
        if (task == null) return;
        AgentRun run = store.run((String) task.get("run_id"));
        if (run == null || !"running".equals(run.status())) return;
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = python.get()
                .uri(builder -> builder.path("/api/runs/{id}/status").queryParam("session_id", run.sessionId()).build(run.id()))
                .header("X-Agent-Service-Token", serviceToken).retrieve().bodyToMono(Map.class).block(Duration.ofSeconds(10));
        if (snapshot != null && Boolean.TRUE.equals(snapshot.get("checkpoint_paused"))
                && run.id().equals(snapshot.get("run_id")) && id.equals(snapshot.get("human_task_id"))
                && snapshot.get("revision") instanceof Number revision && revision.intValue() == run.revision()) {
            activateHuman(run, id, map("version", task.get("version")));
        }
    }

    public void deliverDecision(String id) {
        Map<String, Object> body = claimDecision(id);
        if (body == null) return;
        int attempt = ((Number) body.remove("_delivery_attempt")).intValue();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = python.post().uri("/api/runs/{id}/resume", body.get("run_id"))
                    .header("X-Agent-Service-Token", serviceToken).bodyValue(body).retrieve().bodyToMono(Map.class)
                    .block(Duration.ofSeconds(180));
            if (result == null || !Set.of("completed", "waiting_user", "waiting_human", "failed").contains(result.get("status")))
                throw new IllegalStateException("恢复结果不合法");
            if (!body.get("run_id").equals(result.get("run_id"))) throw new IllegalStateException("恢复运行标识不匹配");
            AgentRun observed = store.run((String) body.get("run_id"));
            if ("waiting_human".equals(result.get("status"))) {
                if (!"waiting_human".equals(observed.status())) throw new IllegalStateException("新人工任务尚未激活");
            } else {
                // The callback normally persisted this already. Applying the authenticated
                // normalized response is an idempotent fallback if its HTTP acknowledgement was lost.
                var published = new java.util.LinkedHashMap<String, Object>(result);
                published.put("expected_revision", body.get("revision"));
                outcome(observed, observed.id(), published);
            }
            store.update("UPDATE cs_human_task SET status='applied',lease_until=NULL,last_error=NULL,updated_at=? WHERE id=? AND decision_id=? AND status='applying' AND attempts=?",
                    LocalDateTime.now(), id, object(body.get("decision")).get("decision_id"), attempt);
        } catch (Exception error) {
            store.update("UPDATE cs_human_task SET status='resume_failed',lease_until=NULL,next_attempt_at=?,last_error=?,updated_at=? WHERE id=? AND status='applying' AND attempts=?",
                    LocalDateTime.now().plusSeconds(leaseSeconds), "恢复交付失败，可重试；已执行操作将按操作标识去重", LocalDateTime.now(), id, attempt);
        }
    }

    private Map<String, Object> claimDecision(String id) {
        requireConfigured();
        return store.transaction(() -> {
            var first = store.one("SELECT * FROM cs_human_task WHERE id = ?", id);
            if (first == null) return null;
            AgentRun preliminary = store.run((String) first.get("run_id"));
            store.lockSession(preliminary.sessionId(), preliminary.userId());
            AgentRun run = store.lockRun(preliminary.id());
            var task = store.one("SELECT * FROM cs_human_task WHERE id = ? FOR UPDATE", id);
            String status = (String) task.get("status");
            if (!Set.of("decision_recorded", "resume_failed", "applying").contains(status) || ((Number) task.get("attempts")).intValue() >= 5 || run.terminal()) return null;
            if ("applying".equals(status) && task.get("lease_until") instanceof java.sql.Timestamp stamp && stamp.toLocalDateTime().isAfter(LocalDateTime.now())) return null;
            String token = token();
            store.update("UPDATE cs_human_task SET status='applying',attempts=attempts+1,lease_until=?,updated_at=? WHERE id=?",
                    LocalDateTime.now().plusSeconds(leaseSeconds), LocalDateTime.now(), id);
            store.update("UPDATE cs_agent_run SET status='running',token_hash=?,token_expires_at=?,updated_at=? WHERE id=?",
                    hash(token), LocalDateTime.now().plusSeconds(tokenSeconds), LocalDateTime.now(), run.id());
            var body = requestFor(new AgentRun(run.id(), run.sessionId(), run.userId(), "running", run.revision(), run.humanTaskId()), token,
                    map("decision", read((String) task.get("decision_json"), Map.of()), "request_id", task.get("decision_id")));
            body.put("_delivery_attempt", ((Number) task.get("attempts")).intValue() + 1);
            return body;
        });
    }

    private Map<String, Object> requestFor(AgentRun run, String token, Map<String, Object> extra) {
        // 完整原文留在 MySQL；每次只发送最近且有长度预算的上下文。
        var recent = store.history(run.sessionId(), 100);
        var history = new java.util.ArrayList<Map<String, Object>>();
        int remaining = 24000;
        for (int i = recent.size() - 1; i >= 0; i--) {
            var message = recent.get(i);
            String content = (String) message.get("content");
            if (content.length() > remaining) break;
            // AI 回复沿用 Python 发布时的编号，合并检查点历史时才能正确去重。
            Object historyId = message.get("agent_message_key") != null ? message.get("agent_message_key") : message.get("id");
            history.add(0, map("id", String.valueOf(historyId), "database_id", String.valueOf(message.get("id")),
                    "role", "user".equals(message.get("type")) ? "user" : "assistant", "content", content));
            remaining -= content.length();
        }
        Map<String, Object> request = map("run_id", run.id(), "session_id", String.valueOf(run.sessionId()), "user_id", String.valueOf(run.userId()),
                "revision", run.revision(), "run_token", token, "user_context", data.userContext(run.userId()), "history", history,
                "long_term_memory", data.memory(run));
        request.putAll(extra);
        return request;
    }

    private AgentRun lockCurrent(AgentRun supplied, int revision) {
        AgentRun run = store.lockRun(supplied.id()); AgentDataService.verifyIdentity(supplied, run);
        if (run.revision() != revision) throw new AgentException(409, "运行版本已变化");
        store.requireSession(run.sessionId(), run.userId());
        return run;
    }

    private String optionalText(Map<String, Object> body, String key, int max) {
        Object value = body.get(key);
        if (value == null) return "";
        if (!(value instanceof String text) || text.length() > max) throw new AgentException(400, key + " 超过允许长度");
        return text;
    }

    private String token() {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void requireConfigured() {
        if (serviceToken == null || serviceToken.isBlank()) throw new AgentException(503, "客服服务身份尚未配置");
    }
}
