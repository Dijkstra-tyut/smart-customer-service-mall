package com.supermarket.live;

import java.time.LocalDateTime;

/**
 * 人工实时客服会话视图，对应 cs_live_session；userName / adminName 来自 sys_user 关联查询。
 */
public record LiveSession(
        long id,
        int userId,
        Integer adminId,
        String status,
        String source,
        String closeReason,
        String lastMessagePreview,
        LocalDateTime lastMessageAt,
        int unreadUser,
        int unreadAdmin,
        LocalDateTime createTime,
        LocalDateTime updateTime,
        String userName,
        String adminName,
        Long agentSessionId
) {

    public boolean isClosed() {
        return LiveStatus.CLOSED.code().equals(status);
    }

    public boolean isWaiting() {
        return LiveStatus.WAITING.code().equals(status);
    }

    public boolean isServing() {
        return LiveStatus.SERVING.code().equals(status);
    }
}
