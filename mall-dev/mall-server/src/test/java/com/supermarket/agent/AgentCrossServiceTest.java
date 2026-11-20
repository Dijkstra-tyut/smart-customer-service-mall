package com.supermarket.agent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.supermarket.dto.ResponseDTO;
import com.supermarket.entity.Product;
import com.supermarket.entity.Order;
import com.supermarket.enums.OrderStatusEnum;
import com.supermarket.entity.User;
import com.supermarket.enums.ProductStatusEnum;
import com.supermarket.service.OrderService;
import com.supermarket.service.ProductSearchService;
import com.supermarket.service.ProductViewService;
import com.supermarket.service.UserService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.supermarket.agent.AgentJson.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Java controllers/SQL/auth + real Python graph and HTTP client; only the LLM is scripted. */
@EnabledIfEnvironmentVariable(named = "AGENT_TEST_PYTHON", matches = ".+")
class AgentCrossServiceTest {
    @Test
    void actualHttpContractSurvivesSpecialistLoopsAndColdHumanResume() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:cross_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE cs_session(id BIGINT PRIMARY KEY,user_id INT NOT NULL,title VARCHAR(100),create_time TIMESTAMP,"
                + "is_deleted INT DEFAULT 0,update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE cs_message(id BIGINT AUTO_INCREMENT PRIMARY KEY,session_id BIGINT NOT NULL,"
                + "type VARCHAR(16),content LONGTEXT,create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE oms_order(id INT PRIMARY KEY,user_id INT,order_sn VARCHAR(100),"
                + "total_amount DECIMAL(12,2),status INT,create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,is_deleted INT DEFAULT 0)");
        new ResourceDatabasePopulator(new FileSystemResource("../db/migrations/20261110_customer_agent.sql")).execute(source);
        new ResourceDatabasePopulator(new FileSystemResource("../db/migrations/20261115_customer_memory.sql")).execute(source);
        new ResourceDatabasePopulator(new FileSystemResource("../db/migrations/20261113_cs_live_chat.sql")).execute(source);
        jdbc.update("INSERT INTO cs_session(id,user_id,title) VALUES (11,1,'新对话')");
        jdbc.update("INSERT INTO oms_order(id,user_id,order_sn,total_amount,status) VALUES "
                + "(101,1,'ALICE-ORDER',29.90,1),(102,1,'ALICE-PENDING',20,0),(201,2,'BOB-PRIVATE-ORDER',88,0)");
        AgentStore store = new AgentStore(jdbc, new DataSourceTransactionManager(source));
        UserService users = mock(UserService.class);
        when(users.getById(1)).thenReturn(new User().setId(1).setName("小林").setPassword("private-password"));
        ProductViewService products = mock(ProductViewService.class);
        when(products.detail(7)).thenReturn(new Product().setId(7).setName("测试商品")
                .setPrice(new BigDecimal("29.90")).setStock(3).setStatus(ProductStatusEnum.ON_SHELF));
        OrderService orders = mock(OrderService.class);
        when(orders.detail(1,102)).thenReturn(new Order().setId(102).setUserId(1).setStatus(OrderStatusEnum.PENDING_PAY).setOrderSn("ALICE-PENDING"));
        AgentDataService data = new AgentDataService(store, users, orders, products, mock(ProductSearchService.class), new AgentMemoryService(store));
        String credential = UUID.randomUUID().toString() + UUID.randomUUID();
        AgentRuntimeService runtime = new AgentRuntimeService(store, data, WebClient.create("http://127.0.0.1:1"), credential, 3600, 300);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AgentInternalController(data, runtime))
                .setControllerAdvice(new AgentExceptionHandler())
                .addFilters(new AgentInternalAuthFilter(store, credential)).build();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try {
                if (exchange.getRequestURI().getPath().startsWith("/test/")) {
                    if (!credential.equals(exchange.getRequestHeaders().getFirst("X-Agent-Service-Token"))) {
                        respond(exchange, 401, write(ResponseDTO.error(401, "测试服务身份无效"))); return;
                    }
                    Map<String, Object> body = object(read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), Map.of()));
                    Object result = switch (exchange.getRequestURI().getPath()) {
                        case "/test/begin" -> runtime.beginChat(11, text(body, "message", 10000), 1);
                        case "/test/decision" -> runtime.decide(text(body, "task_id", 80), object(body.get("decision")), 99);
                        case "/test/claim" -> {
                            var claim = AgentRuntimeService.class.getDeclaredMethod("claimDecision", String.class);
                            claim.setAccessible(true);
                            yield claim.invoke(runtime, text(body, "task_id", 80));
                        }
                        case "/test/view" -> map("run", runtime.portalRun(11, 1), "messages", store.history(11, 100));
                        default -> throw new IllegalArgumentException("未知测试路径");
                    };
                    respond(exchange, 200, write(ResponseDTO.success(result)));
                } else {
                    var request = MockMvcRequestBuilders.request(HttpMethod.valueOf(exchange.getRequestMethod()), exchange.getRequestURI())
                            .servletPath(exchange.getRequestURI().getPath())
                            .content(exchange.getRequestBody().readAllBytes());
                    exchange.getRequestHeaders().forEach((key, values) -> request.header(key, values.toArray()));
                    var response = mvc.perform(request).andReturn().getResponse();
                    // The wire JSON is UTF-8 even when MockMvc omits an explicit
                    // charset. Its legacy no-argument decoder otherwise corrupts Chinese.
                    respond(exchange, response.getStatus(), response.getContentAsString(StandardCharsets.UTF_8));
                }
            } catch (Exception error) {
                respond(exchange, 500, write(map("code", 500, "message", error.toString())));
            }
        });
        server.start();
        try {
            Path cwd = Path.of("").toAbsolutePath();
            Path artifacts = cwd.resolve("target/agent-cross-service");
            java.nio.file.Files.createDirectories(artifacts);
            ProcessBuilder builder = new ProcessBuilder(System.getenv("AGENT_TEST_PYTHON"),
                    cwd.resolve("src/test/resources/agent_runtime_contract.py").toString(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), artifacts.toString());
            builder.environment().put("PYTHONPATH", cwd.resolve("../mall-customer").normalize() + java.io.File.pathSeparator + cwd.resolve("../mall-customer/.test-deps").normalize());
            builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
            builder.environment().put("PYTHONUTF8", "1");
            builder.redirectErrorStream(true);
            builder.redirectOutput(artifacts.resolve("python-result.txt").toFile());
            Process process = builder.start();
            process.getOutputStream().write(credential.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            boolean completed = process.waitFor(90, TimeUnit.SECONDS);
            if (!completed) process.destroyForcibly();
            String output = java.nio.file.Files.readString(artifacts.resolve("python-result.txt"));
            assertThat(completed).as("跨服务 Python 测试应及时结束: %s", output).isTrue();
            assertThat(process.exitValue()).as("Python 输出: %s", output).isZero();
            assertThat(output).contains("CROSS_SERVICE_OK");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cs_human_task WHERE status='applied'", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cs_agent_message WHERE author_type='human'", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cs_agent_operation", Integer.class)).isZero();
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static void respond(HttpExchange exchange, int status, String content) throws java.io.IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
