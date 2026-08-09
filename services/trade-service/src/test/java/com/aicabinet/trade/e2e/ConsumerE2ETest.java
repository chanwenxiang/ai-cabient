package com.aicabinet.trade.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.aicabinet.trade.client.DeviceServiceClient;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E2E - 消费者端核心流程 + 跨域隔离。
 *
 * 微信小程序未配置时（测试 profile）登录走 mock openId（dev-only）；
 * 断言消费者可浏览柜机/商品/会员/订单，且消费者账号访问商家门户被 403 拒绝。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConsumerE2ETest {

    @Container
    @SuppressWarnings("resource") // lifecycle owned by Testcontainers JUnit extension
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aicabinet_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    @SuppressWarnings("resource") // lifecycle owned by Testcontainers JUnit extension
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void containerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> String.valueOf(redis.getMappedPort(6379)));
        // 沙箱重力兜底：视觉服务不可用时按购物车/重力信号结算（见 SettlementService.tryStagingGravitySettle）
        registry.add("aicabinet.gravity-fallback-settle", () -> "true");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${aicabinet.internal-api.key}")
    private String internalApiKey;

    /** 设备服务未运行：开门指令直接 mock 成功，避免会话创建时远程调用失败回滚。 */
    @MockBean
    private DeviceServiceClient deviceServiceClient;

    private static String consumerToken;

    @Test
    @DisplayName("微信 mock 登录并获取 token")
    void consumer_wxLogin() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/v2/auth/wx-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", "mock-e2e-code"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        consumerToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .path("data").path("token").asText();
    }

    @Test
    @DisplayName("消费者可浏览柜机状态")
    void consumer_deviceStatus() throws Exception {
        mockMvc.perform(get("/api/v2/devices/CAB-001/status")
                        .header("Authorization", "Bearer " + consumerToken()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("消费者可浏览柜机商品")
    void consumer_deviceProducts() throws Exception {
        mockMvc.perform(get("/api/v2/devices/CAB-001/products")
                        .header("Authorization", "Bearer " + consumerToken()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("消费者可查看会员资料")
    void consumer_memberProfile() throws Exception {
        mockMvc.perform(get("/api/v2/member/profile")
                        .header("Authorization", "Bearer " + consumerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("消费者可查看订单列表")
    void consumer_orders() throws Exception {
        mockMvc.perform(get("/api/v2/orders")
                        .header("Authorization", "Bearer " + consumerToken())
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("消费者账号访问商家门户 → 403（跨域隔离）")
    void consumer_cannotAccessMerchantPortal() throws Exception {
        mockMvc.perform(get("/api/v2/merchant/me")
                        .header("Authorization", "Bearer " + consumerToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("消费者完整购买链路：实名→充值→开柜→购物车→关门结算→支付")
    void consumer_fullPurchaseFlow() throws Exception {
        String token = consumerToken();

        // 1) 实名（演示仅姓名+身份证后 4 位）
        mockMvc.perform(post("/api/v2/account/verify")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("realName", "E2E用户", "idCardLast4", "1234"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 2) mock 充值 100 元并确认到账
        String idempotencyKey = "e2e-recharge-" + System.currentTimeMillis();
        MvcResult prepayResult = mockMvc.perform(post("/api/v2/payment/recharge/prepay")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "channel", "WECHAT",
                                "amountCents", 10000,
                                "idempotencyKey", idempotencyKey))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        String rechargeOrderId = objectMapper.readTree(prepayResult.getResponse().getContentAsString())
                .path("data").path("orderId").asText();

        mockMvc.perform(post("/api/v2/payment/recharge/" + rechargeOrderId + "/mock-success")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 3) 设备心跳置为在线（种子数据 CAB-001 为 OFFLINE，开柜校验要求在线）
        mockMvc.perform(post("/internal/v1/devices/CAB-001/heartbeat")
                        .header("X-Internal-Api-Key", internalApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        // 4) 开柜（设备开门指令已被 @MockBean 短路）
        MvcResult sessionResult = mockMvc.perform(post("/api/v2/sessions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "deviceId", "CAB-001",
                                "idempotencyKey", "e2e-session-" + System.currentTimeMillis()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        String sessionId = objectMapper.readTree(sessionResult.getResponse().getContentAsString())
                .path("data").path("sessionId").asText();

        // 5) 购物车（mock 结算按购物车扣款）
        mockMvc.perform(put("/api/v2/sessions/" + sessionId + "/cart")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"skuId\":\"SKU-WATER-001\",\"qty\":1}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 6) 关门事件（设备侧内部接口）→ 触发结算生成订单
        mockMvc.perform(post("/internal/v1/sessions/door-event")
                        .header("X-Internal-Api-Key", internalApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sessionId", sessionId,
                                "deviceId", "CAB-001",
                                "doorState", "CLOSED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 7) 取订单：余额充足应直接 PAID；若为 PENDING 则补一次支付
        MvcResult orderResult = mockMvc.perform(get("/api/v2/sessions/" + sessionId + "/order")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        String orderId = objectMapper.readTree(orderResult.getResponse().getContentAsString())
                .path("data").path("orderId").asText();
        String status = objectMapper.readTree(orderResult.getResponse().getContentAsString())
                .path("data").path("status").asText();
        if ("PENDING".equalsIgnoreCase(status)) {
            mockMvc.perform(post("/api/v2/orders/" + orderId + "/pay")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }

        // 8) 最终断言订单已支付
        mockMvc.perform(get("/api/v2/orders/" + orderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("PAID"));
    }

    private String consumerToken() throws Exception {
        if (consumerToken == null) {
            MvcResult loginResult = mockMvc.perform(post("/api/v2/auth/wx-login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("code", "mock-e2e-code"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andReturn();
            consumerToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                    .path("data").path("token").asText();
        }
        return consumerToken;
    }
}
