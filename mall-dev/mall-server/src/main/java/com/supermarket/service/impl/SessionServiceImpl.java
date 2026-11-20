package com.supermarket.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.supermarket.entity.Session;
import com.supermarket.agent.AgentRuntimeService;
import com.supermarket.exception.ServiceException;
import com.supermarket.mapper.SessionMapper;
import com.supermarket.service.SessionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SessionServiceImpl extends ServiceImpl<SessionMapper, Session> implements SessionService {
    private final AgentRuntimeService agentRuntime;

    @Override
    public List<Session> listUserSessions(Integer userId) {
        return lambdaQuery()
                .eq(Session::getUserId, userId)
                .orderByDesc(Session::getUpdateTime)
                .list();
    }

    @Override
    public Session create(Integer userId) {
        Session session = new Session();
        session.setUserId(userId);
        session.setTitle("新对话");
        save(session);
        return session;
    }

    @Override
    @Transactional
    public void delete(Long sessionId, Integer userId) {
        Session session = getById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new ServiceException(403, "无权操作该会话或会话不存在");
        }
        // Cancellation and logical deletion share a short transaction. A deleted
        // conversation cannot leave a pending human task authorized to act later.
        agentRuntime.cancel(sessionId, userId);
        removeById(sessionId);
    }
}
