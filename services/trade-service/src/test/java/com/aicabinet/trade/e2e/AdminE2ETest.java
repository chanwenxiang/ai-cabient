package com.aicabinet.trade.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E2E - 运营后台 RBAC。
 *
 * 演示账号（Flyway 种子）：
 *   13900000001 / 123456  运营超管 admin（全部权限）
 *   13800138006 / 123456  商户店长 merchant_store_manager（仅商家权限）
 *
 * 正向断言：超管可访问运营接口并返回运营权限码；
 * 负向断言：商户账号访问运营接口 403；全局超管访问商家门户 403（跨域隔离）。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminE2ETest {

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

    private static String adminToken;
    private static String merchantToken;

    @Test
    @DisplayName("超管登录，rbac/me/permissions 返回运营权限码")
    void adminLogin_andRbacPermissions() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v2/ops/admin/rbac/me/permissions")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray())
                .andReturn();

        JsonNode permissions = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data");
        List<String> codes = new ArrayList<>();
        permissions.forEach(node -> codes.add(node.asText()));
        assertFalse(codes.isEmpty(), "超管应持有运营权限码");
        assertTrue(codes.contains("ops:dashboard:view"),
                "超管应持有 ops:dashboard:view，实际权限: " + codes);
    }

    @Test
    @DisplayName("超管可访问运营看板统计")
    void admin_stats() throws Exception {
        mockMvc.perform(get("/api/v2/ops/admin/stats")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("超管可获取运营导航")
    void admin_rbacNav() throws Exception {
        mockMvc.perform(get("/api/v2/ops/admin/rbac/me/nav")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("商户账号访问运营接口 → 403（跨域隔离）")
    void merchant_cannotAccessOpsAdmin() throws Exception {
        mockMvc.perform(get("/api/v2/ops/admin/stats")
                        .header("Authorization", "Bearer " + merchantToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("全局超管访问商家门户 → 403（商家门户需绑定商家账号）")
    void admin_cannotAccessMerchantPortal() throws Exception {
        mockMvc.perform(get("/api/v2/merchant/me")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isForbidden());
    }

    private String adminToken() throws Exception {
        if (adminToken == null) {
            adminToken = login("13900000001", "123456");
        }
        return adminToken;
    }

    private String merchantToken() throws Exception {
        if (merchantToken == null) {
            merchantToken = login("13800138006", "123456");
        }
        return merchantToken;
    }

    private String login(String phone, String password) throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/v2/auth/admin-password-login")
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
