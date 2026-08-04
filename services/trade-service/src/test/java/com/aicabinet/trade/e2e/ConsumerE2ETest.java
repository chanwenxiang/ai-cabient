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
 * E2E测试 - 消费者完整购物流程
 */
@Disabled("Stub scenarios use outdated API contracts / paths")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumerE2ETest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String consumerToken;
    private String deviceId = "E2E-DEVICE-001";
    private String sessionId;
    private String orderId;

    @Test
    @Order(1)
    @DisplayName("E2E场景1：新用户注册登录")
    void scenario1_NewUserRegistration() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/v2/auth/wechat-login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"mock-wechat-code-13800138000\"}"))
                .andExpect(status().isOk())
                .andReturn();

        consumerToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
            .path("data").path("token").asText();
    }

    @Test
    @Order(2)
    @DisplayName("E2E场景2：浏览设备商品")
    void scenario2_BrowseDeviceProducts() throws Exception {
        mockMvc.perform(get("/api/v2/devices/" + deviceId + "/status"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v2/devices/" + deviceId + "/products"))
                .andExpect(status().isOk());
    }

    @Test
    @Order(3)
    @DisplayName("E2E场景3：扫码开门购物")
    void scenario3_ScanAndShop() throws Exception {
        MvcResult sessionResult = mockMvc.perform(post("/api/v2/sessions")
                .header("Authorization", "Bearer " + consumerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + deviceId + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        sessionId = objectMapper.readTree(sessionResult.getResponse().getContentAsString())
            .path("data").path("sessionId").asText();

        MvcResult orderResult = mockMvc.perform(post("/api/v2/sessions/" + sessionId + "/confirm")
                .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isOk())
                .andReturn();

        orderId = objectMapper.readTree(orderResult.getResponse().getContentAsString())
            .path("data").path("orderId").asText();
    }

    @Test
    @Order(4)
    @DisplayName("E2E场景4：支付订单")
    void scenario4_PayOrder() throws Exception {
        mockMvc.perform(post("/api/v2/orders/" + orderId + "/pay")
                .header("Authorization", "Bearer " + consumerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"BALANCE\",\"idempotencyKey\":\"e2e-pay-001\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @Order(5)
    @DisplayName("E2E场景5：查看会员信息")
    void scenario5_CheckMemberInfo() throws Exception {
        mockMvc.perform(get("/api/v2/member/profile")
                .header("Authorization", "Bearer " + consumerToken))
                .andExpect(status().isOk());
    }

    @Test
    @Order(6)
    @DisplayName("E2E场景6：查看订单历史")
    void scenario6_ViewOrderHistory() throws Exception {
        mockMvc.perform(get("/api/v2/orders")
                .header("Authorization", "Bearer " + consumerToken)
                .param("page", "0")
                .param("size", "10"))
                .andExpect(status().isOk());
    }
}
