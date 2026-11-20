package com.supermarket.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

final class AgentJson {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private AgentJson() { }

    static String write(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("无法编码任务数据", e); }
    }

    static Object read(String value, Object fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return JSON.readValue(value, Object.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("持久化任务数据损坏", e); }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) throw new AgentException(400, "缺少结构化参数");
        return (Map<String, Object>) value;
    }

    static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    static String text(Map<String, ?> data, String key, int maximum) {
        Object value = data.get(key);
        if (!(value instanceof String text) || text.isBlank() || text.length() > maximum)
            throw new AgentException(400, key + " 缺失或长度不合法");
        return text;
    }

    static int number(Map<String, ?> data, String key) {
        try {
            String value = String.valueOf(data.get(key));
            int number = Integer.parseInt(value);
            if (number < 0) throw new NumberFormatException();
            return number;
        } catch (NumberFormatException e) { throw new AgentException(400, key + " 不合法"); }
    }

    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static String payloadHash(Object value) {
        try {
            return hash(JSON.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                    .writeValueAsString(value));
        } catch (JsonProcessingException e) { throw new IllegalArgumentException(e); }
    }
}
