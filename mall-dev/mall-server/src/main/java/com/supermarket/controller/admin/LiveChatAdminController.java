package com.supermarket.controller.admin;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import com.supermarket.dto.Response;
import com.supermarket.dto.ResponseDTO;
import com.supermarket.live.LiveChatService;
import com.supermarket.live.LiveIdentity;
import com.supermarket.live.LiveMessage;
import com.supermarket.live.LiveSession;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 管理后台人工实时客服。列表/接入/关闭走 REST，实时消息走 /ws/cs（管理员 token 握手）。
 */
@SaCheckRole("admin")
@RestController
@RequestMapping("/admin/customer/live")
@RequiredArgsConstructor
@Tag(name = "管理端-人工实时客服")
public class LiveChatAdminController {

    private final LiveChatService liveChatService;
    private final com.supermarket.agent.CustomerInteractionService interaction;

    @GetMapping("/sessions/{id}/context")
    public ResponseDTO<?> context(@PathVariable long id) {
        return Response.success(interaction.context(id, StpUtil.getLoginIdAsInt()));
    }

    @Operation(summary = "实时会话列表；status=waiting/serving/closed/active")
    @GetMapping("/sessions")
    public ResponseDTO<Map<String, Object>> sessions(@RequestParam(required = false) String status,
                                                     @RequestParam(defaultValue = "false") boolean mine,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        return Response.success(liveChatService.adminSessions(status, mine, StpUtil.getLoginIdAsInt(), page, size));
    }

    @Operation(summary = "会话详情")
    @GetMapping("/sessions/{id}")
    public ResponseDTO<LiveSession> session(@PathVariable("id") long id) {
        return Response.success(liveChatService.session(id, LiveIdentity.ROLE_ADMIN, StpUtil.getLoginIdAsInt()));
    }

    @Operation(summary = "会话消息（增量）")
    @GetMapping("/sessions/{id}/messages")
    public ResponseDTO<List<LiveMessage>> messages(@PathVariable("id") long id,
                                                   @RequestParam(required = false) Long afterId,
                                                   @RequestParam(defaultValue = "50") Integer size) {
        return Response.success(liveChatService.history(id, afterId, size,
                LiveIdentity.ROLE_ADMIN, StpUtil.getLoginIdAsInt()));
    }

    @Operation(summary = "接入会话（抢单）")
    @PostMapping("/sessions/{id}/claim")
    public ResponseDTO<LiveSession> claim(@PathVariable("id") long id) {
        return Response.success(liveChatService.claim(StpUtil.getLoginIdAsInt(), id));
    }

    @Operation(summary = "客服上报已读")
    @PostMapping("/sessions/{id}/read")
    public ResponseDTO<Map<String, Object>> read(@PathVariable("id") long id,
                                                 @RequestParam(required = false) Long upToId) {
        return Response.success(liveChatService.markRead(id, LiveIdentity.ROLE_ADMIN,
                StpUtil.getLoginIdAsInt(), upToId));
    }

    @Operation(summary = "结束会话")
    @PostMapping("/sessions/{id}/close")
    public ResponseDTO<LiveSession> close(@PathVariable("id") long id,
                                          @RequestParam(required = false) String reason) {
        return Response.success(liveChatService.close(id, LiveIdentity.ROLE_ADMIN,
                StpUtil.getLoginIdAsInt(), reason));
    }

    @Operation(summary = "客服工作台概览：各状态会话数与在线客服数")
    @GetMapping("/stats")
    public ResponseDTO<Map<String, Object>> stats() {
        return Response.success(liveChatService.stats());
    }
}
