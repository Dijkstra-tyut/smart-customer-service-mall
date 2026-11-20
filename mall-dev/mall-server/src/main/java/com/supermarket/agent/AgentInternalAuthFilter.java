package com.supermarket.agent;

import com.supermarket.dto.ResponseDTO;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
@Order(-100)
public class AgentInternalAuthFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE = "trustedAgentRun";
    private final AgentStore store;
    private final String serviceToken;

    public AgentInternalAuthFilter(AgentStore store, @Value("${mall.customer.service-token:}") String token) {
        this.store = store;
        this.serviceToken = token;
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith("/internal/agent/");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws ServletException, IOException {
        String service = request.getHeader("X-Agent-Service-Token");
        String runToken = request.getHeader("X-Agent-Run-Token");
        if (serviceToken == null || serviceToken.isBlank()) { deny(response, 503, "客服服务身份尚未配置"); return; }
        if (service == null || !MessageDigest.isEqual(serviceToken.getBytes(StandardCharsets.UTF_8), service.getBytes(StandardCharsets.UTF_8))) {
            deny(response, 401, "无效的服务身份"); return;
        }
        if (runToken == null || runToken.length() > 200) { deny(response, 401, "缺少运行授权"); return; }
        AgentRun run = store.authenticate(runToken);
        if (run == null) { deny(response, 401, "运行授权失效"); return; }
        request.setAttribute(ATTRIBUTE, run);
        chain.doFilter(request, response);
    }

    private void deny(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(AgentJson.write(ResponseDTO.error(status, message)));
    }
}
