package com.supermarket.live;

/**
 * 消息发送方类型。user=商城用户，admin=人工客服，agent=智能客服，system=系统提示。
 */
public enum LiveSender {

    USER("user"),
    ADMIN("admin"),
    AGENT("agent"),
    SYSTEM("system");

    private final String code;

    LiveSender(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static LiveSender parse(String value) {
        for (LiveSender sender : values()) {
            if (sender.code.equals(value)) {
                return sender;
            }
        }
        return null;
    }
}
