package com.supermarket.service.impl;

import com.supermarket.agent.AgentRuntimeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.supermarket.entity.Message;
import com.supermarket.entity.Session;
import com.supermarket.exception.ServiceException;
import com.supermarket.mapper.MessageMapper;
import com.supermarket.service.MessageService;
import com.supermarket.service.SessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MessageServiceImpl extends ServiceImpl<MessageMapper, Message> implements MessageService {

    private final SessionService sessionService;
    private final AgentRuntimeService agentRuntime;
    private final com.supermarket.agent.AgentStore agentStore;

    @Override
    public List<Message> listMessagesBySessionId(Long sessionId, Integer userId) {
        Session session = sessionService.getById(sessionId);
        if (session == null || !session.getUserId().equals(userId) || Integer.valueOf(1).equals(session.getIsDeleted())) {
            throw new ServiceException(403, "无权查看该会话或会话不存在");
        }
        List<Message> messages = lambdaQuery()
                .eq(Message::getSessionId, sessionId)
                .orderByAsc(Message::getId)
                .list();
        Map<Long, String> origins = new HashMap<>();
        for (Map<String, Object> row : agentStore.rows("SELECT a.message_id,a.author_type FROM cs_agent_message a JOIN cs_message m ON m.id=a.message_id WHERE m.session_id=?", sessionId)) {
            origins.put(((Number) row.get("message_id")).longValue(), (String) row.get("author_type"));
        }
        for (Message message : messages) message.setOrigin(origins.getOrDefault(message.getId(), message.getType()));
        return messages;
    }

    @Override
    public Flux<String> chat(Long sessionId, String message, Integer userId) {
        return agentRuntime.chat(sessionId, message, userId);
    }
}
