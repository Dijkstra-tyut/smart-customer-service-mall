package com.supermarket.live;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supermarket.exception.ServiceException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WebSocket 协议编解码。信封格式：{"type":"...","requestId":"...","ts":毫秒,"data":{...}}
 */
public final class LiveJson {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private LiveJson() {
    }

    public static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("实时消息序列化失败", e);
        }
    }

    public static String envelope(String type, String requestId, Map<String, Object> data) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", type);
        envelope.put("requestId", requestId);
        envelope.put("ts", System.currentTimeMillis());
        envelope.put("data", data == null ? Map.of() : data);
        return write(envelope);
    }

    public static JsonNode parse(String payload) {
        try {
            return JSON.readTree(payload);
        } catch (JsonProcessingException e) {
            throw new ServiceException(400, "消息体不是合法 JSON");
        }
    }

    public static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put((String) pairs[i], pairs[i + 1]);
        }
        return result;
    }

    public static String text(JsonNode node, String field, int maxLength, boolean required) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            if (required) {
                throw new ServiceException(400, field + " 不能为空");
            }
            return null;
        }
        String text = value.asText();
        if (required && text.isBlank()) {
            throw new ServiceException(400, field + " 不能为空");
        }
        if (text.length() > maxLength) {
            throw new ServiceException(400, field + " 超出长度限制");
        }
        return text;
    }

    public static Long longOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.asText());
        } catch (NumberFormatException e) {
            throw new ServiceException(400, field + " 必须是数字");
        }
    }

    public static long requiredLong(JsonNode node, String field) {
        Long value = longOrNull(node, field);
        if (value == null) {
            throw new ServiceException(400, field + " 不能为空");
        }
        return value;
    }
}
