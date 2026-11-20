package com.supermarket.agent;

import com.supermarket.entity.Order;
import com.supermarket.entity.User;
import com.supermarket.enums.OrderStatusEnum;
import com.supermarket.live.*;
import com.supermarket.service.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomerInteractionServiceTest {
    JdbcTemplate jdbc;
    AgentStore store;
    AgentRuntimeService runtime;
    LiveChatService live;
    CustomerInteractionService interaction;
    OrderService orders;

    @BeforeEach void setup() {
        var source=new JdbcDataSource();
        source.setURL("jdbc:h2:mem:interaction_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE sys_user(id INT PRIMARY KEY,name VARCHAR(100),is_deleted INT DEFAULT 0,status INT DEFAULT 1,role INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE cs_session(id BIGINT PRIMARY KEY,user_id INT,title VARCHAR(100),is_deleted INT DEFAULT 0,create_time TIMESTAMP,update_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE cs_message(id BIGINT AUTO_INCREMENT PRIMARY KEY,session_id BIGINT,type VARCHAR(16),content LONGTEXT,create_time TIMESTAMP)");
        for(String name:List.of("20261110_customer_agent.sql","20261115_customer_memory.sql","20261113_cs_live_chat.sql"))
            new ResourceDatabasePopulator(new FileSystemResource("../db/migrations/"+name)).execute(source);
        jdbc.update("INSERT INTO sys_user(id,name,role) VALUES (1,'用户甲',0),(2,'客服',1),(3,'用户乙',0)");
        jdbc.update("INSERT INTO cs_session(id,user_id,title) VALUES (11,1,'甲的会话'),(33,3,'乙的会话')");
        var manager=new DataSourceTransactionManager(source);
        store=new AgentStore(jdbc,manager);
        orders=mock(OrderService.class);
        var users=mock(UserService.class);
        when(users.getById(1)).thenReturn(new User().setId(1).setName("用户甲"));
        var data=new AgentDataService(store,users,orders,mock(ProductViewService.class),mock(ProductSearchService.class),new AgentMemoryService(store));
        runtime=new AgentRuntimeService(store,data,WebClient.create("http://127.0.0.1:1"),"test-key",3600,300);
        live=new LiveChatService(new LiveChatStore(jdbc,manager),mock(LiveConnectionRegistry.class),List.of());
        interaction=new CustomerInteractionService(store,runtime,live,orders);
    }

    @Test void clickStartsHandoffBlocksAiAndMirrorsMessagesExactlyOnce() {
        var first=runtime.beginChat(11,"希望真人处理",1);
        var session=interaction.handoff(11,1);
        assertThat(session.agentSessionId()).isEqualTo(11);
        assertThat(interaction.handoff(11,1).id()).isEqualTo(session.id());
        assertThat(store.run((String)first.get("run_id")).status()).isEqualTo("cancelled");
        assertThatThrownBy(()->runtime.beginChat(11,"继续让AI回答",1)).isInstanceOf(AgentException.class);
        live.sendUserMessage(1,session.id(),"user-msg","text","请人工核实");
        live.sendUserMessage(1,session.id(),"user-msg","text","请人工核实");
        live.claim(2,session.id());
        live.sendAdminMessage(2,session.id(),"admin-msg","text","已核实您的问题");
        live.sendAdminMessage(2,session.id(),"admin-msg","text","已核实您的问题");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cs_message WHERE content='请人工核实'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT type FROM cs_message WHERE content='已核实您的问题'",String.class)).isEqualTo("human");
        assertThat(interaction.context(session.id(),2)).hasSize(1);
        interaction.close(11,1);
        var next=runtime.beginChat(11,"继续咨询",1);
        assertThat(AgentJson.write(next.get("history"))).contains("已核实您的问题");
    }

    @Test void handoffNeverAcceptsAnotherUsersConversation() {
        assertThatThrownBy(()->interaction.handoff(11,3)).isInstanceOf(AgentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cs_live_session",Integer.class)).isZero();
    }

    @Test void afterSalesIsOwnedExplicitAndIdempotent() {
        when(orders.detail(1,101)).thenReturn(new Order().setId(101).setUserId(1).setStatus(OrderStatusEnum.PAID));
        var body=Map.<String,Object>of("order_id",101,"reason","包装破损，申请核实","request_id","request-1");
        assertThat(interaction.afterSales(11,1,body).get("ticket_id")).isEqualTo(interaction.afterSales(11,1,body).get("ticket_id"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cs_ticket",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cs_message",Integer.class)).isEqualTo(2);
        assertThatThrownBy(()->interaction.afterSales(11,3,body)).isInstanceOf(AgentException.class);
        when(orders.detail(1,102)).thenReturn(new Order().setId(102).setUserId(3).setStatus(OrderStatusEnum.PAID));
        assertThatThrownBy(()->interaction.afterSales(11,1,Map.of("order_id",102,"reason","查询","request_id","request-2"))).isInstanceOf(AgentException.class);
    }

    @Test void onlyKnownUiActionsMayReachTheBrowser() {
        assertThat(CustomerActions.normalize(List.of("handoff","view_orders"))).containsExactly("handoff","view_orders");
        assertThatThrownBy(()->CustomerActions.normalize(List.of("https://untrusted.test"))).isInstanceOf(AgentException.class);
    }
}
