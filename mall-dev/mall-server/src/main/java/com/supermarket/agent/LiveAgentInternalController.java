package com.supermarket.agent;

import com.supermarket.dto.Response;
import com.supermarket.dto.ResponseDTO;
import com.supermarket.exception.ServiceException;
import com.supermarket.live.LiveChatService;
import com.supermarket.live.LiveIdentity;
import com.supermarket.live.LiveMessage;
import com.supermarket.live.LiveSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Agent 侧人工客服接入点，复用 /internal/agent 服务身份 + run token 鉴权。
 * <p>
 * 智能客服编排时：先 POST /handoff 转入人工队列，再用 POST /messages 以 agent 身份推送消息，
 * GET /session 读取当前实时会话。人工客服回复后，Agent 可通过用户消息桥（LiveAgentBridge）被动感知。
 */
@RestController
@RequestMapping("/internal/agent/live")
public class LiveAgentInternalController {

    private final LiveChatService liveChatService;

    public LiveAgentInternalController(LiveChatService liveChatService) {
        this.liveChatService = liveChatService;
    }

    @GetMapping("/session")
    public ResponseDTO<?> session(HttpServletRequest request) {
        return Response.success(liveChatService.agentSession(run(request).userId()));
    }

    @GetMapping("/messages")
    public ResponseDTO<?> messages(HttpServletRequest request,
                                   @RequestParam(required = false) Long afterId,
                                   @RequestParam(defaultValue = "50") int size) {
        AgentRun run = run(request);
        LiveSession session = liveChatService.agentSession(run.userId());
        if (session == null) {
            return Response.success(List.of());
        }
        List<LiveMessage> messages = liveChatService.history(session.id(), afterId, size,
                LiveIdentity.ROLE_USER, run.userId());
        return Response.success(messages);
    }

    /** 转为人工：会话不存在则创建，进入 waiting 队列等待客服抢单。 */
    @PostMapping("/handoff")
    public ResponseDTO<?> handoff(HttpServletRequest request, @RequestBody(required = false) Map<String, Object> body) {
        AgentRun run = run(request);
        Object reason = body == null ? null : body.get("reason");
        return Response.success(liveChatService.handoffFromAgent(run.userId(), reason == null ? null : reason.toString()));
    }

    /** 以智能客服身份推送消息到实时会话。 */
    @PostMapping("/messages")
    public ResponseDTO<?> send(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        AgentRun run = run(request);
        Object sessionId = body.get("session_id");
        if (sessionId == null) {
            throw new ServiceException(400, "session_id 不能为空");
        }
        Object content = body.get("content");
        if (content == null) {
            throw new ServiceException(400, "content 不能为空");
        }
        long id;
        try {
            id = Long.parseLong(sessionId.toString());
        } catch (NumberFormatException e) {
            throw new ServiceException(400, "session_id 不合法");
        }
        return Response.success(liveChatService.sendAgentMessage(run.userId(), id, content.toString()));
    }

    private AgentRun run(HttpServletRequest request) {
        if (!(request.getAttribute(AgentInternalAuthFilter.ATTRIBUTE) instanceof AgentRun run)) {
            throw new AgentException(401, "缺少可信运行身份");
        }
        return run;
    }
}
