package com.supermarket.live;

/**
 * 实时客服会话状态机：waiting（待接入）→ serving（服务中）→ closed（已结束）。
 */
public enum LiveStatus {

    WAITING("waiting"),
    SERVING("serving"),
    CLOSED("closed");

    private final String code;

    LiveStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static LiveStatus parse(String value) {
        for (LiveStatus status : values()) {
            if (status.code.equals(value)) {
                return status;
            }
        }
        return null;
    }
}
