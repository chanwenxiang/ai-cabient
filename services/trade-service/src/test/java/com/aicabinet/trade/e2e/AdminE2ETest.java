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
    @DisplayName("运营场景6：查看争议处理")
    void scenario6_ManageDisputes() throws Exception {
        mockMvc.perform(get("/api/v2/ops/disputes")
                .header("Authorization", "Bearer " + adminToken)
                .param("status", "OPEN")
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @Order(7)
    @DisplayName("运营场景7：查看财务统计")
    void scenario7_GetFinanceStatistics() throws Exception {
        mockMvc.perform(get("/api/v2/ops/admin/finance/stats")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @Order(8)
    @DisplayName("运营场景8：系统参数配置")
    void scenario8_ManageSystemConfig() throws Exception {
        mockMvc.perform(get("/api/v2/ops/admin/system-configs")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @Order(9)
    @DisplayName("运营场景9：异常中心")
    void scenario9_ListExceptions() throws Exception {
        mockMvc.perform(get("/api/v2/ops/admin/exceptions")
                .header("Authorization", "Bearer " + adminToken)
                .param("status", "OPEN")
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @Order(10)
    @DisplayName("运营场景10：设备报表")
    void scenario10_DeviceReports() throws Exception {
        mockMvc.perform(get("/api/v2/ops/admin/reports/devices")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }
}
