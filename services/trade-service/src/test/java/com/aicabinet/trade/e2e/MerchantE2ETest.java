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
 * E2E测试 - 商户管理流程
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MerchantE2ETest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String merchantToken;
    private String deviceId = "MERCHANT-DEVICE-001";

    @Test
    @Order(1)
    @DisplayName("商户场景1：商户登录")
    void scenario1_MerchantLogin() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/v2/auth/merchant-login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"13900139000\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andReturn();

        merchantToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
            .path("data").path("token").asText();
    }

    @Test
    @Order(2)
    @DisplayName("商户场景2：查看我的设备列表")
    void scenario2_ListMyDevices() throws Exception {
        mockMvc.perform(get("/api/v2/merchant/devices")
                .header("Authorization", "Bearer " + merchantToken)
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @Order(3)
    @DisplayName("商户场景3：查看设备详情")
    void scenario3_GetDeviceDetail() throws Exception {
        mockMvc.perform(get("/api/v2/merchant/devices/" + deviceId)
                .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @Order(4)
    @DisplayName("商户场景4：查看销售统计")
    void scenario4_GetSalesStatistics() throws Exception {
        mockMvc.perform(get("/api/v2/merchant/stats")
                .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @Order(5)
    @DisplayName("商户场景5：查看结算概览")
    void scenario5_GetRevenueShare() throws Exception {
        mockMvc.perform(get("/api/v2/merchant/settlements/overview")
                .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @Order(6)
    @DisplayName("商户场景6：查看订单列表")
    void scenario6_ListOrders() throws Exception {
        mockMvc.perform(get("/api/v2/merchant/orders")
                .header("Authorization", "Bearer " + merchantToken)
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @Order(7)
    @DisplayName("商户场景7：查看结算批次（无自主提现）")
    void scenario7_RequestWithdraw() throws Exception {
        mockMvc.perform(get("/api/v2/merchant/settlements/batches")
                .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @Order(8)
    @DisplayName("商户场景8：查看补货任务")
    void scenario8_GetReplenishmentTasks() throws Exception {
        mockMvc.perform(get("/api/v2/merchant/replenishment/tasks")
                .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @Order(9)
    @DisplayName("商户场景9：查看争议订单")
    void scenario9_ListDisputes() throws Exception {
        mockMvc.perform(get("/api/v2/merchant/disputes")
                .header("Authorization", "Bearer " + merchantToken)
                .param("status", "OPEN")
                .param("page", "0")
                .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @Order(10)
    @DisplayName("商户场景10：查看日结算汇总")
    void scenario10_GetFinanceReport() throws Exception {
        mockMvc.perform(get("/api/v2/merchant/settlements/daily")
                .header("Authorization", "Bearer " + merchantToken)
                .param("from", "2026-07-01")
                .param("to", "2026-07-14"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }
}
