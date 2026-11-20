package com.supermarket.live;

import java.time.LocalDateTime;

/**
 * 实时客服消息，对应 cs_live_message。clientMsgId 由客户端生成，用于断线重发幂等。
 */
public record LiveMessage(
        long id,
        long sessionId,
        String senderType,
        Integer senderId,
        String contentType,
        String content,
        String clientMsgId,
        boolean read,
        LocalDateTime createTime,
        String senderName
) {
}
