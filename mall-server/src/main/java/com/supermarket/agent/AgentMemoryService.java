package com.supermarket.agent;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.supermarket.agent.AgentJson.*;

/** MySQL 是对话与用户记忆的唯一写入方。这里不访问 Python checkpoint。 */
@Service
public class AgentMemoryService {
    private static final Set<String> ATTRIBUTES = Set.of("preferred_name", "language", "response_style", "product_preference", "service_preference");
    private static final Pattern PRIVATE_DATA = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)|[\\w.+-]+@[\\w-]+\\.[\\w.-]+|(?<!\\d)\\d{17}[\\dXx](?!\\d)");
    private static final Pattern DURABLE_INTENT = Pattern.compile("以后|今后|往后|每次|长期|一直|通常|平时|习惯|偏好");
    private static final Pattern ONE_OFF_INTENT = Pattern.compile("这次|本次|当前|今天|暂时|这回");
    private final AgentStore store;

    public AgentMemoryService(AgentStore store) { this.store = store; }

    public Map<String, Object> conversations(int userId, int page, int size) {
        AgentDataService.page(page); AgentDataService.size(size);
        var rows = store.rows("SELECT id,title,create_time,update_time FROM cs_session WHERE user_id=? AND is_deleted=0 ORDER BY update_time DESC,id DESC LIMIT ? OFFSET ?",
                userId, size, (page - 1) * size);
        return AgentDataService.pageResult(rows, store.one("SELECT COUNT(*) AS total FROM cs_session WHERE user_id=? AND is_deleted=0", userId).get("total"), page, size);
    }

    public Map<String, Object> messages(int userId, long sessionId, Long beforeId, int size, boolean forAgent) {
        store.requireSession(sessionId, userId);
        if (size < 1 || size > 20 || (beforeId != null && beforeId <= 0)) throw new AgentException(400, "消息分页参数不合法");
        String cursor = beforeId == null ? "" : " AND m.id < ?";
        List<Object> args = new ArrayList<>(List.of(sessionId));
        if (beforeId != null) args.add(beforeId);
        args.add(size + 1);
        var rows = store.rows("SELECT m.id,m.type,m.content,m.create_time,COALESCE(a.author_type,m.type) AS origin FROM cs_message m LEFT JOIN cs_agent_message a ON a.message_id=m.id WHERE m.session_id=?" + cursor + " ORDER BY m.id DESC LIMIT ?", args.toArray());
        boolean more = rows.size() > size;
        var page = new ArrayList<>(rows.subList(0, Math.min(size, rows.size())));
        Long next = page.isEmpty() ? null : ((Number) page.get(page.size() - 1).get("id")).longValue();
        // Agent 每条最多读取两千字；面向用户的接口返回完整原文。
        for (var row : page) {
            String content = (String) row.get("content");
            boolean truncated = forAgent && content.length() > 2000;
            row.put("content", truncated ? content.substring(0, 2000) : content);
            row.put("truncated", truncated);
        }
        Collections.reverse(page);
        return map("session_id", String.valueOf(sessionId), "items", page, "has_more", more,
                "next_before_id", more ? next : null, "historical", true);
    }

    public Map<String, Object> load(int userId, long sessionId) {
        store.requireSession(sessionId, userId);
        var summary = store.one("SELECT summary,through_message_id,updated_at FROM cs_conversation_summary WHERE session_id=? AND user_id=?", sessionId, userId);
        return map("profile", profile(userId), "conversation_summary", summary == null ? Map.of() : summary);
    }

    public List<Map<String, Object>> profile(int userId) {
        var profile = store.rows("SELECT p.attribute,p.preference_value,p.source_session_id,p.source_message_id,p.updated_at FROM cs_user_memory p JOIN cs_session s ON s.id=p.source_session_id AND s.user_id=p.user_id WHERE p.user_id=? AND s.is_deleted=0 ORDER BY p.attribute", userId);
        for (var fact : profile) fact.put("value", fact.remove("preference_value"));
        return profile;
    }

    public Map<String, Object> save(AgentRun supplied, Map<String, Object> body) {
        return store.transaction(() -> {
            AgentRun run = store.lockRun(supplied.id());
            AgentDataService.verifyIdentity(supplied, run);
            store.lockSession(run.sessionId(), run.userId());
            if (!Set.of("completed", "waiting_user").contains(run.status()) || number(body, "expected_revision") != run.revision())
                throw new AgentException(409, "当前运行尚未生成正式回复或版本已变化");
            long through = positiveLong(body.get("through_message_id"));
            var source = store.one("SELECT id FROM cs_message WHERE id=? AND session_id=? AND type='user'", through, run.sessionId());
            if (source == null) throw new AgentException(400, "摘要来源消息不属于当前用户输入");
            Object rawFacts = body.get("facts");
            if (!(rawFacts instanceof List<?> facts) || facts.size() > 5) throw new AgentException(400, "记忆条目不合法");
            int saved = 0;
            for (Object item : facts) {
                var fact = object(item);
                String attribute = text(fact, "attribute", 40), value = text(fact, "value", 200), quote = text(fact, "quote", 500);
                long messageId = positiveLong(fact.get("source_message_id"));
                if (!ATTRIBUTES.contains(attribute) || messageId != through || PRIVATE_DATA.matcher(value).find() || PRIVATE_DATA.matcher(quote).find()
                        || Pattern.compile("密码|口令|密钥|银行卡|身份证|家庭住址", Pattern.CASE_INSENSITIVE).matcher(quote).find())
                    throw new AgentException(400, "用户记忆字段或来源不合法");
                var message = store.one("SELECT content FROM cs_message WHERE id=? AND session_id=? AND type='user'", messageId, run.sessionId());
                if (message == null || !((String) message.get("content")).contains(quote) || !quote.contains(value))
                    throw new AgentException(400, "偏好必须引用当前用户的明确原话");
                // 模型只能提交候选：一次办理诉求不能成为跨会话用户画像。
                if (!durableFact(attribute, value, (String) message.get("content"))) continue;
                // 不允许较早会话的异步提炼覆盖用户后来的表达；相同来源重复写入无副作用。
                // 使用唯一键原子合并，避免两个会话首次写入同一偏好时发生先查后插竞争。
                int changed = store.update("INSERT INTO cs_user_memory(user_id,attribute,preference_value,source_session_id,source_message_id,source_quote,updated_at) VALUES (?,?,?,?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE preference_value=CASE WHEN source_message_id < ? THEN ? ELSE preference_value END,"
                        + "source_session_id=CASE WHEN source_message_id < ? THEN ? ELSE source_session_id END,"
                        + "source_quote=CASE WHEN source_message_id < ? THEN ? ELSE source_quote END,"
                        + "updated_at=CASE WHEN source_message_id < ? THEN ? ELSE updated_at END,"
                        + "source_message_id=GREATEST(source_message_id,?)",
                        run.userId(), attribute, value, run.sessionId(), messageId, quote, LocalDateTime.now(),
                        messageId, value, messageId, run.sessionId(), messageId, quote, messageId, LocalDateTime.now(), messageId);
                if (changed > 0) saved++;
            }
            String summary = text(body, "summary", 3000);
            if (PRIVATE_DATA.matcher(summary).find()) throw new AgentException(400, "摘要包含不必要的个人信息");
            var old = store.one("SELECT through_message_id FROM cs_conversation_summary WHERE session_id=? FOR UPDATE", run.sessionId());
            if (old == null) store.update("INSERT INTO cs_conversation_summary(session_id,user_id,summary,through_message_id,updated_at) VALUES (?,?,?,?,?)",
                    run.sessionId(), run.userId(), summary, through, LocalDateTime.now());
            else if (((Number) old.get("through_message_id")).longValue() < through)
                store.update("UPDATE cs_conversation_summary SET summary=?,through_message_id=?,updated_at=? WHERE session_id=? AND user_id=?",
                        summary, through, LocalDateTime.now(), run.sessionId(), run.userId());
            return map("saved_facts", saved, "through_message_id", String.valueOf(through));
        });
    }

    private long positiveLong(Object value) {
        try { long id = Long.parseLong(String.valueOf(value)); if (id > 0) return id; }
        catch (NumberFormatException ignored) { }
        throw new AgentException(400, "消息编号不合法");
    }

    private boolean durableFact(String attribute, String value, String message) {
        for (String clause : message.split("[，,。！？!?；;\\n]")) {
            if (!clause.contains(value) || ONE_OFF_INTENT.matcher(clause).find()) continue;
            if (DURABLE_INTENT.matcher(clause).find()) return true;
            if ("preferred_name".equals(attribute) && Pattern.compile("叫我|称呼我|我叫|名字是").matcher(clause).find()) return true;
            if ("product_preference".equals(attribute) && Pattern.compile("喜欢|偏爱").matcher(clause).find()) return true;
        }
        return false;
    }
}
