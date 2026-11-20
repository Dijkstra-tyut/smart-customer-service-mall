package com.supermarket.live;

import cn.dev33.satoken.stp.StpUtil;
import com.supermarket.entity.User;
import com.supermarket.enums.RoleEnum;
import com.supermarket.enums.UserStatusEnum;
import com.supermarket.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

/**
 * WebSocket 握手鉴权。前端通过本站登录 Cookie 连接 {@code /ws/cs}。
 * 同时兼容查询参数、{@code mall} 请求头与 {@code Authorization: Bearer}。
 */
@Component
public class LiveHandshakeInterceptor implements HandshakeInterceptor {

    public static final String IDENTITY = "liveIdentity";

    private static final Logger log = LoggerFactory.getLogger(LiveHandshakeInterceptor.class);

    private final UserMapper userMapper;
    private final String allowedOrigins;

    public LiveHandshakeInterceptor(UserMapper userMapper,
                                    @Value("${mall.live.allowed-origins:*}") String allowedOrigins) {
        this.userMapper = userMapper;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!originAllowed(request)) {
            log.info("实时客服拒绝跨域握手, origin={}", request.getHeaders().getFirst("Origin"));
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        String token = token(request);
        if (token == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        Object loginId = StpUtil.getLoginIdByToken(token);
        if (loginId == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        int userId;
        try {
            userId = Integer.parseInt(loginId.toString());
        } catch (NumberFormatException e) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        User user = userMapper.selectById(userId);
        if (user == null || (user.getDeleted() != null && user.getDeleted() != 0)
                || user.getStatus() == UserStatusEnum.DISABLED) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        String role = user.getRole() == RoleEnum.ADMIN ? LiveIdentity.ROLE_ADMIN : LiveIdentity.ROLE_USER;
        attributes.put(IDENTITY, new LiveIdentity(userId, role, user.getName()));
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }

    private boolean originAllowed(ServerHttpRequest request) {
        String origin = request.getHeaders().getFirst("Origin");
        if (origin == null || origin.isBlank()) {
            return true;
        }
        return Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .anyMatch(value -> value.isEmpty() || "*".equals(value) || value.equalsIgnoreCase(origin));
    }

    private String token(ServerHttpRequest request) {
        String query = request.getURI().getRawQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                int separator = pair.indexOf('=');
                if (separator <= 0 || !"token".equals(pair.substring(0, separator))) {
                    continue;
                }
                String value = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        String header = request.getHeaders().getFirst("mall");
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        String authorization = request.getHeaders().getFirst("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring(7).trim();
        }
        // 浏览器 WebSocket 会自动携带本站登录 Cookie，无需把 Token 放进 URL。
        String cookies = request.getHeaders().getFirst("Cookie");
        if (cookies != null) for (String cookie : cookies.split(";")) {
            String[] pair = cookie.trim().split("=", 2);
            if (pair.length == 2 && "mall".equals(pair[0]) && !pair[1].isBlank()) return pair[1];
        }
        return null;
    }
}
