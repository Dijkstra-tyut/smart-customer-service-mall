package com.supermarket.agent;

import java.util.List;
import java.util.Set;

/** 模型只能建议这三个固定按钮，具体接口和权限由程序确定。 */
public final class CustomerActions {
    private static final Set<String> ALLOWED = Set.of("handoff", "view_orders", "request_after_sales");
    private CustomerActions() { }

    public static List<String> normalize(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list) || list.size() > 3 || list.stream().anyMatch(item -> !(item instanceof String) || !ALLOWED.contains(item)))
            throw new AgentException(400, "不支持该客服按钮");
        return list.stream().map(String.class::cast).distinct().toList();
    }
}
