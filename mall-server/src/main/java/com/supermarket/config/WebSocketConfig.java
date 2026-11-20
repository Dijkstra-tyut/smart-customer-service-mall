package com.supermarket.config;

import com.supermarket.live.LiveHandshakeInterceptor;
import com.supermarket.live.LiveWsHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * 人工实时客服 WebSocket 配置。握手鉴权在 {@link LiveHandshakeInterceptor} 内完成，
 * 因此这里放开 Origin（由拦截器按 mall.live.allowed-origins 再做一次白名单校验）。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final LiveWsHandler liveWsHandler;
    private final LiveHandshakeInterceptor liveHandshakeInterceptor;

    public WebSocketConfig(LiveWsHandler liveWsHandler, LiveHandshakeInterceptor liveHandshakeInterceptor) {
        this.liveWsHandler = liveWsHandler;
        this.liveHandshakeInterceptor = liveHandshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(liveWsHandler, "/ws/cs")
                .addInterceptors(liveHandshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }

    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(64 * 1024);
        container.setMaxBinaryMessageBufferSize(64 * 1024);
        container.setMaxSessionIdleTimeout(300_000L);
        return container;
    }
}
