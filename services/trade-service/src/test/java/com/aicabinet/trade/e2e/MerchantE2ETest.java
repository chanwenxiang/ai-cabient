package com.aicabinet.trade.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E2E - 商家小程序 RBAC。
 *
 * 演示账号（Flyway 种子）：
 *   13800138006 / 123456  店长 merchant_store_manager，绑定 MCH-DEFAULT
 *   13800138007 / 123456  补货员 merchant_replenisher，绑定 MCH-DEFAULT（V161 修复后可用）
 *
 * 正向断言：店长可访问其角色授予的商家接口；
 * 负向断言：店长访问钱包 / 线长钱包 / 改柜机设置、补货员访问订单，均应被 403 拒绝。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MerchantE2ETest {

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
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static String storeManagerToken;
    private static String replenisherToken;

    @Test
    @Order(1)
    @DisplayName("店长登录，me 返回 merchant:portal:access 权限")
    void storeManager_loginAndPortalAccess() throws Exception {
        MvcResult meResult = mockMvc.perform(get("/api/v2/merchant/me")
                        .header("Authorization", "Bearer " + storeManagerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.permissions").isArray())
                .andReturn();

        JsonNode permissions = objectMapper.readTree(meResult.getResponse().getContentAsString())
                .path("data").path("permissions");
        List<String> codes = new ArrayList<>();
        permissions.forEach(node -> codes.add(node.asText()));
        assertTrue(codes.contains("merchant:portal:access"),
                "店长应持有 merchant:portal:access，实际权限: " + codes);
    }

    @Test
    @Order(2)
    @DisplayName("店长可查看柜机列表")
    void storeManager_listDevices() throws Exception {
        mockMvc.perform(get("/api/v2/merchant/devices")
                        .header("Authorization", "Bearer " + storeManagerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @Order(3)
    @DisplayName("店长可查看柜机详情")
    void storeManager_deviceDetail() throws Exception {
        mockMvc.perform(get("/api/v2/merchant/devices/CAB-001")
                        .header("Authorization", "Bearer " + storeManagerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @Order(4)
    @DisplayName("店长可查看订单列表")
    void storeManager_listOrders() throws Exception {
        mockMvc.perform(get("/api/v2/merchant/orders")
                        .header("Authorization", "Bearer " + storeManagerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @Order(5)
    @DisplayName("店长可查看结算概览")
    void storeManager_settlementOverview() throws Exception {
        mockMvc.perform(get("/api/v2/merchant/settlements/overview")
                        .header("Authorization", "Bearer " + storeManagerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @Order(6)
    @DisplayName("店长可查看补货任务")
    void storeManager_replenishmentTasks() throws Exception {
        mockMvc.perform(get("/api/v2/merchant/replenishment/tasks")
                        .header("Authorization", "Bearer " + storeManagerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @Order(7)
    @DisplayName("店长无权查看商家钱包 → 403")
    void storeManager_walletForbidden() throws Exception {
        mockMvc.perform(get("/api/v2/merchant/wallet")
                        .header("Authorization", "Bearer " + storeManagerToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(8)
    @DisplayName("店长无权查看线长钱包 → 403（回归 GET /line-wallet 权限注解）")
    void storeManager_lineWalletForbidden() throws Exception {
        mockMvc.perform(get("/api/v2/merchant/line-wallet")
                        .header("Authorization", "Bearer " + storeManagerToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(9)
    @DisplayName("店长无权修改柜机设置 → 403")
    void storeManager_updateDeviceSettingsForbidden() throws Exception {
        mockMvc.perform(patch("/api/v2/merchant/devices/CAB-001/settings")
                        .header("Authorization", "Bearer " + storeManagerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(10)
    @DisplayName("补货员登录并可查看柜机列表")
    void replenisher_loginAndListDevices() throws Exception {
        mockMvc.perform(get("/api/v2/merchant/devices")
                        .header("Authorization", "Bearer " + replenisherToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @Order(11)
    @DisplayName("补货员无权查看订单列表 → 403")
    void replenisher_ordersForbidden() throws Exception {
        mockMvc.perform(get("/api/v2/merchant/orders")
                        .header("Authorization", "Bearer " + replenisherToken()))
                .andExpect(status().isForbidden());
    }

    private String storeManagerToken() throws Exception {
        if (storeManagerToken == null) {
            storeManagerToken = login("13800138006", "123456");
        }
        return storeManagerToken;
    }

    private String replenisherToken() throws Exception {
        if (replenisherToken == null) {
            replenisherToken = login("13800138007", "123456");
        }
        return replenisherToken;
    }

    private String login(String phone, String password) throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/v2/auth/merchant-password-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("phoneNumber", phone, "password", password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .path("data").path("token").asText();
    }
}
