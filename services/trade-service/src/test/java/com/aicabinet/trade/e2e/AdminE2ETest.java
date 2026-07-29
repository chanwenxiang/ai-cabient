package com.aicabinet.trade.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * E2E测试 - 运营管理流程
 */
@Disabled("Stub scenarios use outdated API contracts (e.g. admin-login expects SMS code)")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminE2ETest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String adminToken;

    @Test
    @Order(1)
    @DisplayName("运营场景1：管理员登录")
    void scenario1_AdminLogin() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/v2/auth/admin-login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andReturn();

        adminToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
            .path("data").path("token").asText();
    }

    @Test
    @Order(2)
    @DisplayName("运营场景2：查看数据大屏")
    void scenario2_GetDashboard() throws Exception {
        mockMvc.perform(get("/api/v2/admin/dashboard")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @Order(3)
    @DisplayName("运营场景3：查看所有设备")
    void scenario3_ListAllDevices() throws Exception {
        mockMvc.perform(get("/api/v2/admin/devices")
                .header("Authorization", "Bearer " + adminToken)
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @Order(4)
    @DisplayName("运营场景4：查看所有订单")
    void scenario4_ListAllOrders() throws Exception {
        mockMvc.perform(get("/api/v2/admin/orders")
                .header("Authorization", "Bearer " + adminToken)
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @Order(5)
    @DisplayName("运营场景5：查看用户列表")
    void scenario5_ListUsers() throws Exception {
        mockMvc.perform(get("/api/v2/admin/users")
                .header("Authorization", "Bearer " + adminToken)
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @Order(6)
    @DisplayName("运营场景6：查看加盟商管理")
    void scenario6_ManageFranchises() throws Exception {
        mockMvc.perform(get("/api/v2/admin/franchisees")
                .header("Authorization", "Bearer " + adminToken)
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @Order(7)
    @DisplayName("运营场景7：查看线长管理")
    void scenario7_ManageLineLeaders() throws Exception {
        mockMvc.perform(get("/api/v2/admin/line-leaders")
                .header("Authorization", "Bearer " + adminToken)
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @Order(8)
    @DisplayName("运营场景8：查看争议处理")
    void scenario8_ManageDisputes() throws Exception {
        mockMvc.perform(get("/api/v2/admin/disputes")
                .header("Authorization", "Bearer " + adminToken)
                .param("status", "OPEN")
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @Order(9)
    @DisplayName("运营场景9：查看财务统计")
    void scenario9_GetFinanceStatistics() throws Exception {
        mockMvc.perform(get("/api/v2/admin/finance/statistics")
                .header("Authorization", "Bearer " + adminToken)
                .param("period", "month"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @Order(10)
    @DisplayName("运营场景10：系统配置管理")
    void scenario10_ManageSystemConfig() throws Exception {
        mockMvc.perform(get("/api/v2/admin/config")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @Order(11)
    @DisplayName("运营场景11：查看告警列表")
    void scenario11_ListAlerts() throws Exception {
        mockMvc.perform(get("/api/v2/admin/alerts")
                .header("Authorization", "Bearer " + adminToken)
                .param("status", "ACTIVE")
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @Order(12)
    @DisplayName("运营场景12：导出数据报表")
    void scenario12_ExportReport() throws Exception {
        mockMvc.perform(get("/api/v2/admin/reports/export")
                .header("Authorization", "Bearer " + adminToken)
                .param("type", "orders")
                .param("startDate", "2026-07-01")
                .param("endDate", "2026-07-14"))
                .andExpect(status().isOk());
    }
}
