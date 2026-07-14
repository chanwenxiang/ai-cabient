package com.aicabinet.trade.integration;

import com.aicabinet.trade.domain.RechargeOrder;
import com.aicabinet.trade.domain.UserAccount;
import com.aicabinet.trade.payment.WeChatPayTestKeys;
import com.aicabinet.trade.payment.WeChatPayV3Aead;
import com.aicabinet.trade.payment.WeChatPayV3Signer;
import com.aicabinet.trade.repository.RechargeOrderRepository;
import com.aicabinet.trade.repository.UserAccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WeChatNotifyIntegrationTest {

    private static final String API_V3_KEY = "01234567890123456789012345678901";
    private static WeChatPayTestKeys.Material keys;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aicabinet_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        keys = WeChatPayTestKeys.generate();
        registry.add("aicabinet.wechat-pay.enabled", () -> "true");
        registry.add("aicabinet.wechat-pay.app-id", () -> "wx-test-app");
        registry.add("aicabinet.wechat-pay.mch-id", () -> "1900000109");
        registry.add("aicabinet.wechat-pay.notify-url", () -> "https://example.com/notify");
        registry.add("aicabinet.wechat-pay.api-v3-key", () -> API_V3_KEY);
        registry.add("aicabinet.wechat-pay.merchant-serial-no", () -> "TEST-MCH-SERIAL");
        registry.add("aicabinet.wechat-pay.private-key", keys::merchantPrivateKeyPem);
        registry.add("aicabinet.wechat-pay.platform-cert", keys::platformCertPem);
        registry.add("aicabinet.wechat-pay.platform-cert-auto-fetch", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RechargeOrderRepository rechargeOrderRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private final WeChatPayV3Signer signer = new WeChatPayV3Signer();
    private final WeChatPayV3Aead aead = new WeChatPayV3Aead();

    @BeforeAll
    static void requireKeys() {
        if (keys == null) {
            keys = WeChatPayTestKeys.generate();
        }
    }

    @Test
    void wechatNotify_creditsPendingRechargeOrder() throws Exception {
        String orderId = "R" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        RechargeOrder order = new RechargeOrder();
        order.setOrderId(orderId);
        order.setUserId(10001L);
        order.setAmountCents(500);
        order.setChannel("WECHAT");
        order.setStatus("PENDING");
        order.setIdempotencyKey("it-wechat-notify-" + orderId);
        order.setWxPrepayId("prepay-test");
        rechargeOrderRepository.save(order);

        int balanceBefore = userAccountRepository.findById(10001L).orElseThrow().getBalanceCents();

        String notifyBody = buildNotifyBody(orderId);
        String timestamp = "1700000000";
        String nonce = "notify-nonce-abc123";
        String signature = signer.sign(
                timestamp + "\n" + nonce + "\n" + notifyBody + "\n",
                keys.platformPrivateKeyPem()
        );

        mockMvc.perform(post("/api/v2/payment/wechat/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Wechatpay-Timestamp", timestamp)
                        .header("Wechatpay-Nonce", nonce)
                        .header("Wechatpay-Signature", signature)
                        .header("Wechatpay-Serial", "TEST-PLATFORM-SERIAL")
                        .content(notifyBody))
                .andExpect(status().isNoContent());

        RechargeOrder paid = rechargeOrderRepository.findById(orderId).orElseThrow();
        assertEquals("PAID", paid.getStatus());

        UserAccount account = userAccountRepository.findById(10001L).orElseThrow();
        assertEquals(balanceBefore + 500, account.getBalanceCents());
    }

    private String buildNotifyBody(String orderId) throws Exception {
        ObjectNode transaction = objectMapper.createObjectNode();
        transaction.put("out_trade_no", orderId);
        transaction.put("trade_state", "SUCCESS");
        String plain = objectMapper.writeValueAsString(transaction);

        String resourceNonce = "resource-nonce";
        String ciphertext = encryptResource(plain, resourceNonce);

        ObjectNode resource = objectMapper.createObjectNode();
        resource.put("original_type", "transaction");
        resource.put("algorithm", "AEAD_AES_256_GCM");
        resource.put("ciphertext", ciphertext);
        resource.put("associated_data", "transaction");
        resource.put("nonce", resourceNonce);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("id", UUID.randomUUID().toString());
        root.put("create_time", "2024-06-01T12:00:00+08:00");
        root.put("resource_type", "encrypt-resource");
        root.put("event_type", "TRANSACTION.SUCCESS");
        root.put("summary", "支付成功");
        root.set("resource", resource);
        return objectMapper.writeValueAsString(root);
    }

    private String encryptResource(String plain, String nonce) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKeySpec key = new SecretKeySpec(API_V3_KEY.getBytes(StandardCharsets.UTF_8), "AES");
        GCMParameterSpec spec = new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8));
        cipher.init(Cipher.ENCRYPT_MODE, key, spec);
        cipher.updateAAD("transaction".getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
    }
}
