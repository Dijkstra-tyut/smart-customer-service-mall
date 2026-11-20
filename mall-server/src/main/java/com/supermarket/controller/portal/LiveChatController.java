package com.supermarket.controller.portal;

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
 * 门户端人工实时客服 REST 接口。实时收发走 /ws/cs，这里只做历史补偿与会话管理。
 */
@RestController
@RequestMapping("/portal/customer/live")
@RequiredArgsConstructor
@Tag(name = "门户端-人工实时客服")
public class LiveChatController {

    private final LiveChatService liveChatService;

    @Operation(summary = "获取当前进行中的实时会话")
    @GetMapping("/current")
    public ResponseDTO<LiveSession> current() {
        return Response.success(liveChatService.currentSession(StpUtil.getLoginIdAsInt()));
    }

    @Operation(summary = "创建（或复用）实时会话")
    @PostMapping("/sessions")
    public ResponseDTO<LiveSession> create() {
        return Response.success(liveChatService.createSession(StpUtil.getLoginIdAsInt()));
    }

    @Operation(summary = "我的历史实时会话")
    @GetMapping("/sessions")
    public ResponseDTO<List<LiveSession>> sessions(@RequestParam(defaultValue = "20") int limit) {
        return Response.success(liveChatService.userSessions(StpUtil.getLoginIdAsInt(), limit));
    }

    @Operation(summary = "增量拉取会话消息（断线重连补偿）")
    @GetMapping("/sessions/{id}/messages")
    public ResponseDTO<List<LiveMessage>> messages(@PathVariable("id") long id,
                                                   @RequestParam(required = false) Long afterId,
                                                   @RequestParam(defaultValue = "50") Integer size) {
        return Response.success(liveChatService.history(id, afterId, size,
                LiveIdentity.ROLE_USER, StpUtil.getLoginIdAsInt()));
    }

    @Operation(summary = "上报已读")
    @PostMapping("/sessions/{id}/read")
    public ResponseDTO<Map<String, Object>> read(@PathVariable("id") long id,
                                                 @RequestParam(required = false) Long upToId) {
        return Response.success(liveChatService.markRead(id, LiveIdentity.ROLE_USER,
                StpUtil.getLoginIdAsInt(), upToId));
    }

    @Operation(summary = "结束实时会话")
    @PostMapping("/sessions/{id}/close")
    public ResponseDTO<LiveSession> close(@PathVariable("id") long id,
                                          @RequestParam(required = false) String reason) {
        return Response.success(liveChatService.close(id, LiveIdentity.ROLE_USER,
                StpUtil.getLoginIdAsInt(), reason));
    }
}
