package com.supermarket.controller.portal;

import cn.dev33.satoken.stp.StpUtil;
import com.supermarket.agent.AgentRuntimeService;
import com.supermarket.dto.Response;
import com.supermarket.dto.ResponseDTO;
import com.supermarket.entity.Message;
import com.supermarket.entity.Session;
import com.supermarket.service.MessageService;
import com.supermarket.service.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/portal/customer")
@RequiredArgsConstructor
@Tag(name = "门户端-智能客服")
public class CustomerController {

    private final SessionService sessionService;
    private final MessageService messageService;
    private final AgentRuntimeService agentRuntime;
    private final com.supermarket.agent.AgentMemoryService memory;
    private final com.supermarket.agent.CustomerInteractionService interaction;

    @GetMapping("/{sessionId}/live")
    public ResponseDTO<?> live(@PathVariable long sessionId) {
        return Response.success(interaction.current(sessionId, StpUtil.getLoginIdAsInt()));
    }

    @PostMapping("/{sessionId}/handoff")
    public ResponseDTO<?> handoff(@PathVariable long sessionId) {
        return Response.success(interaction.handoff(sessionId, StpUtil.getLoginIdAsInt()));
    }

    @PostMapping("/{sessionId}/after-sales")
    public ResponseDTO<?> afterSales(@PathVariable long sessionId, @RequestBody Map<String, Object> body) {
        return Response.success(interaction.afterSales(sessionId, StpUtil.getLoginIdAsInt(), body));
    }

    @Operation(summary = "查看本人可追溯的用户资料及偏好")
    @GetMapping("/memory")
    public ResponseDTO<?> userMemory() {
        return Response.success(memory.profile(StpUtil.getLoginIdAsInt()));
    }

    @Operation(summary = "游标分页读取本人完整历史消息")
    @GetMapping("/{sessionId}/messages/page")
    public ResponseDTO<?> messagePage(@PathVariable long sessionId,
                                     @RequestParam(required=false) Long beforeId,
                                     @RequestParam(defaultValue="20") int size) {
        return Response.success(memory.messages(StpUtil.getLoginIdAsInt(), sessionId, beforeId, size, false));
    }

    @Operation(summary = "获取当前用户的客服会话列表")
    @GetMapping
    public ResponseDTO<List<Session>> listSessions() {
        Integer userId = StpUtil.getLoginIdAsInt();
        return Response.success(sessionService.listUserSessions(userId));
    }

    @Operation(summary = "创建新会话")
    @PostMapping
    public ResponseDTO<Session> createSession() {
        Integer userId = StpUtil.getLoginIdAsInt();
        return Response.success(sessionService.create(userId));
    }

    @Operation(summary = "删除会话")
    @DeleteMapping("/{id}")
    public ResponseDTO<Void> deleteSession(@PathVariable("id") Long id) {
        Integer userId = StpUtil.getLoginIdAsInt();
        interaction.close(id, userId);
        sessionService.delete(id, userId);
        return Response.ok("删除会话成功");
    }

    @Operation(summary = "获取指定会话的历史消息")
    @GetMapping("/{sessionId}/messages")
    public ResponseDTO<List<Message>> listMessages(@PathVariable("sessionId") Long sessionId) {
        Integer userId = StpUtil.getLoginIdAsInt();
        return Response.success(messageService.listMessagesBySessionId(sessionId, userId));
    }

    @GetMapping("/{sessionId}/run")
    public ResponseDTO<?> run(@PathVariable Long sessionId) {
        return Response.success(agentRuntime.portalRun(sessionId, StpUtil.getLoginIdAsInt()));
    }

    @PostMapping("/{sessionId}/cancel")
    public ResponseDTO<?> cancel(@PathVariable Long sessionId) {
        return Response.success(agentRuntime.cancel(sessionId, StpUtil.getLoginIdAsInt()));
    }

    @Operation(summary = "发送消息并流式返回回复")
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@RequestBody Map<String, Object> body) {
        Integer userId = StpUtil.getLoginIdAsInt();
        Long sessionId = Long.valueOf(body.get("sessionId").toString());
        String message = body.get("message").toString();

        return messageService.chat(sessionId, message, userId)
                .map(content -> ServerSentEvent.<String>builder()
                        .data(content)
                        .build());
    }
}
