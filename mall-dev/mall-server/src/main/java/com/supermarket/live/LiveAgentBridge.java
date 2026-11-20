package com.supermarket.live;

/**
 * 用户消息 → Agent 的预留扩展点。
 * <p>
 * 默认没有任何实现（等价 no-op）。后续在 Agent 编排人工客服时，新增一个
 * {@code @Component} 实现即可接管：例如判断是否需要智能客服先应答、识别转人工意图、
 * 或者在客服离线时触发自动回复。实现方运行在主线程，务必自行异步化耗时逻辑。
 */
public interface LiveAgentBridge {

    /** 用户发送了一条实时消息，会话已落库。 */
    default void onUserMessage(LiveSession session, LiveMessage message) {
    }

    /** 会话结束。 */
    default void onSessionClosed(LiveSession session) {
    }
}
