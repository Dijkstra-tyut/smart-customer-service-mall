package com.supermarket.live;

/**
 * WebSocket 连接身份。role 只有 user / admin 两种，由 Sa-Token 登录用户 + sys_user.role 判定。
 */
public record LiveIdentity(int userId, String role, String displayName) {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ADMIN = "admin";

    public boolean isAdmin() {
        return ROLE_ADMIN.equals(role);
    }
}
