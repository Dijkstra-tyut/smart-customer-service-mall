package com.supermarket.live;

import com.supermarket.exception.ServiceException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class LiveChatServiceTest {

    private JdbcTemplate jdbc;
    private LiveChatStore store;
    private LiveConnectionRegistry registry;
    private LiveChatService service;

    @BeforeEach
    void setUp() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:live_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE sys_user(id INT PRIMARY KEY,name VARCHAR(32),is_deleted INT DEFAULT 0,"
                + "status INT DEFAULT 1,role INT DEFAULT 0)");
        jdbc.update("INSERT INTO sys_user(id,name) VALUES (1,'小林'),(2,'客服A'),(3,'客服B')");
        new ResourceDatabasePopulator(new FileSystemResource("../db/migrations/20261113_cs_live_chat.sql")).execute(source);
        store = new LiveChatStore(jdbc, new DataSourceTransactionManager(source));
        registry = mock(LiveConnectionRegistry.class);
        service = new LiveChatService(store, registry, List.of());
    }

    @Test
    void userMessageCreatesWaitingSessionAndUnread() {
        LiveChatService.SendOutcome outcome = service.sendUserMessage(1, null, "client-1", "text", "我的订单什么时候发货");

        assertThat(outcome.duplicate()).isFalse();
        assertThat(outcome.message().id()).isPositive();

        LiveSession session = service.currentSession(1);
        assertThat(session).isNotNull();
        assertThat(session.status()).isEqualTo(LiveStatus.WAITING.code());
        assertThat(session.userId()).isEqualTo(1);
        assertThat(session.unreadAdmin()).isEqualTo(1);
        assertThat(session.lastMessagePreview()).isEqualTo("我的订单什么时候发货");
        verify(registry, atLeastOnce()).sendToUser(eq(1), anyString());
        verify(registry, atLeastOnce()).broadcastAdmins(anyString());
    }

    @Test
    void duplicateClientMessageIdIsIdempotent() {
        LiveChatService.SendOutcome first = service.sendUserMessage(1, null, "client-1", "text", "在吗");
        LiveChatService.SendOutcome second = service.sendUserMessage(1, null, "client-1", "text", "在吗");

        assertThat(second.duplicate()).isTrue();
        assertThat(second.message().id()).isEqualTo(first.message().id());
        assertThat(store.session(first.message().sessionId()).unreadAdmin()).isEqualTo(1);
        assertThat(store.messages(first.message().sessionId(), 0, 10)).hasSize(1);
    }

    @Test
    void claimIsExclusiveAndAddsSystemGreeting() {
        long sessionId = service.sendUserMessage(1, null, "client-1", "text", "人工客服").message().sessionId();

        LiveSession claimed = service.claim(2, sessionId);
        assertThat(claimed.status()).isEqualTo(LiveStatus.SERVING.code());
        assertThat(claimed.adminId()).isEqualTo(2);
        assertThat(claimed.adminName()).isEqualTo("客服A");
        assertThat(store.latestMessage(sessionId).senderType()).isEqualTo(LiveSender.SYSTEM.code());

        assertThatThrownBy(() -> service.claim(3, sessionId))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("已被其他客服接入");

        assertThat(service.claim(2, sessionId).adminId()).isEqualTo(2);
    }

    @Test
    void adminReplyIncrementsUserUnreadAndReachesUser() {
        long sessionId = service.sendUserMessage(1, null, "client-1", "text", "请问运费多少").message().sessionId();
        service.claim(2, sessionId);

        LiveChatService.SendOutcome outcome = service.sendAdminMessage(2, sessionId, "admin-1", "text", "满 99 包邮");
        assertThat(outcome.duplicate()).isFalse();

        LiveSession session = store.session(sessionId);
        assertThat(session.unreadUser()).isEqualTo(2);
        assertThat(session.lastMessagePreview()).isEqualTo("满 99 包邮");
        verify(registry, atLeastOnce()).sendToUser(eq(1), anyString());
    }

    @Test
    void adminCannotReplyBeforeClaiming() {
        long sessionId = service.sendUserMessage(1, null, "client-1", "text", "有人吗").message().sessionId();

        assertThatThrownBy(() -> service.sendAdminMessage(2, sessionId, "admin-1", "text", "在的"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("请先接入");
    }

    @Test
    void closedSessionRejectsNewMessages() {
        long sessionId = service.sendUserMessage(1, null, "client-1", "text", "结束吧").message().sessionId();
        service.close(sessionId, LiveIdentity.ROLE_USER, 1, "user_closed");

        assertThatThrownBy(() -> service.sendUserMessage(1, sessionId, "client-2", "text", "还在吗"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("会话已结束");
    }

    @Test
    void historyIsScopedToOwnerAndAssignedAdmin() {
        long sessionId = service.sendUserMessage(1, null, "client-1", "text", "你好").message().sessionId();
        service.claim(2, sessionId);

        assertThat(service.history(sessionId, null, 20, LiveIdentity.ROLE_USER, 1)).isNotEmpty();
        assertThatThrownBy(() -> service.history(sessionId, null, 20, LiveIdentity.ROLE_USER, 9))
                .isInstanceOf(ServiceException.class).hasMessageContaining("无权访问");
        assertThatThrownBy(() -> service.history(sessionId, null, 20, LiveIdentity.ROLE_ADMIN, 3))
                .isInstanceOf(ServiceException.class).hasMessageContaining("不属于");
    }

    @Test
    void markReadClearsUnreadAndNotifiesCounterpart() {
        long sessionId = service.sendUserMessage(1, null, "client-1", "text", "你好").message().sessionId();
        service.claim(2, sessionId);
        assertThat(store.session(sessionId).unreadAdmin()).isEqualTo(1);

        service.markRead(sessionId, LiveIdentity.ROLE_ADMIN, 2, null);

        assertThat(store.session(sessionId).unreadAdmin()).isZero();
        verify(registry, atLeastOnce()).sendToUser(eq(1), anyString());
    }

    @Test
    void agentHandoffCreatesWaitingSessionOnce() {
        LiveSession handedOff = service.handoffFromAgent(1, "refund_request");

        assertThat(handedOff.status()).isEqualTo(LiveStatus.WAITING.code());
        assertThat(handedOff.source()).isEqualTo("agent");
        int messages = store.messages(handedOff.id(), 0, 50).size();
        assertThat(messages).isEqualTo(1);

        LiveSession repeated = service.handoffFromAgent(1, "refund_request");
        assertThat(repeated.id()).isEqualTo(handedOff.id());
        assertThat(store.messages(handedOff.id(), 0, 50)).hasSize(messages);
    }

    @Test
    void agentMessageIsStoredAndPushedToUser() {
        LiveSession session = service.createSession(1);

        LiveMessage message = service.sendAgentMessage(1, session.id(), "已为您查询到订单状态");

        assertThat(message.senderType()).isEqualTo(LiveSender.AGENT.code());
        assertThat(store.session(session.id()).unreadUser()).isEqualTo(1);
        verify(registry, atLeastOnce()).sendToUser(eq(1), anyString());
    }

    @Test
    void rejectingOversizedOrEmptyContent() {
        assertThatThrownBy(() -> service.sendUserMessage(1, null, "c-1", "text", "   "))
                .isInstanceOf(ServiceException.class).hasMessageContaining("不能为空");
        assertThatThrownBy(() -> service.sendUserMessage(1, null, "c-1", "text", "x".repeat(2001)))
                .isInstanceOf(ServiceException.class).hasMessageContaining("2000");
        assertThatThrownBy(() -> service.sendUserMessage(1, null, "bad id!", "text", "hi"))
                .isInstanceOf(ServiceException.class).hasMessageContaining("clientMsgId");
    }

    @Test
    void statsAndOfflineInvariant() {
        long sessionId = service.sendUserMessage(1, null, "c-1", "text", "hi").message().sessionId();
        service.claim(2, sessionId);

        assertThat(service.stats().get("serving")).isEqualTo(1);
        assertThat(service.adminSessions("serving", true, 2, 1, 20).get("total")).isEqualTo(1);
        assertThat(service.adminSessions("waiting", false, 2, 1, 20).get("total")).isEqualTo(0);
    }
}
