package com.aicabinet.jiangyi.api;

import com.aicabinet.jiangyi.client.TradeInternalClient;
import com.aicabinet.jiangyi.client.TradeInternalClient.JiangyiDeviceView;
import com.aicabinet.jiangyi.auth.DeviceTokenService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 将邑鉴权与透传 IT（CB-022，方案 §12：401 负向 + 回执透传）。
 * <p>Spring 上下文 + MockMvc；trade 与 tracker 依赖均以 {@code @MockBean} 短路。
 * tracker 必须短路而非连真 Redis：lock-state success 会走
 * {@code CommandTracker.complete}（AU-060/061 面），而 dev 栈 Redis 带 requirepass、
 * IT 侧 {@code REDIS_PASSWORD} 为空 ⇒ 依赖真连接会让本类随环境凭据漂移（假红）。
 * tracker 自身的键安全由 {@code CommandTrackerTest} 覆盖。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "aicabinet.jiangyi.jwt-secret=jiangyi-it-secret-0123456789abcdef!!",
        "aicabinet.jiangyi.token-ttl-seconds=3600",
        "aicabinet.trade-service.url=http://localhost:59999"
})
class DeviceAuthIT {

    private static final SecretKey TEST_KEY =
            Keys.hmacShaKeyFor("jiangyi-it-secret-0123456789abcdef!!".getBytes(StandardCharsets.UTF_8));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DeviceTokenService deviceTokenService;

    @MockBean
    private TradeInternalClient tradeInternalClient;

    @MockBean
    private com.aicabinet.jiangyi.tracker.CommandTracker commandTracker;

    @BeforeEach
    void resetMocks() {
        Mockito.reset(tradeInternalClient);
    }

    private static JiangyiDeviceView boundDevice() {
        return new JiangyiDeviceView("D1", "sn-abc", "CQYB11253", "model76", null,
                "BOUND", 1L, Instant.now(), null, true);
    }

    // ---------- token 签发（免鉴权入口） ----------

    @Test
    void tokenIssueReturnsEnvelopeWithTokenAndReceipts() throws Exception {
        when(tradeInternalClient.deviceBySn("sn-abc")).thenReturn(Optional.of(boundDevice()));

        mockMvc.perform(post("/jiangyi/api/token/openDoorDeviceStatus")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceSn\":\"sn-abc\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.identifier").value("CQYB11253"));

        // 回执透传：签发后必须通知 trade 记 token_issued_at（方案 §3）
        verify(tradeInternalClient).tokenIssued("D1");
    }

    @Test
    void tokenIssueRejectsUnknownSnAs404() throws Exception {
        when(tradeInternalClient.deviceBySn("sn-ghost")).thenReturn(Optional.empty());
        mockMvc.perform(post("/jiangyi/api/token/openDoorDeviceStatus")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceSn\":\"sn-ghost\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void tokenIssueRejectsUnboundDeviceAs403() throws Exception {
        JiangyiDeviceView unbound = new JiangyiDeviceView("D1", "sn-abc", "CQYB11253", null, null,
                "UNBOUND", 0L, null, null, false);
        when(tradeInternalClient.deviceBySn("sn-abc")).thenReturn(Optional.of(unbound));
        mockMvc.perform(post("/jiangyi/api/token/openDoorDeviceStatus")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceSn\":\"sn-abc\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void tokenIssueRejectsUnactivatedDeviceAs403AndAlerts() throws Exception {
        // 审计 A1/P0-3 激活制：BOUND 但未激活（auth_enabled=false）→ 拒发 + HIGH 告警
        JiangyiDeviceView cold = new JiangyiDeviceView("D2", "sn-cold", "CQYB20001", null, null,
                "BOUND", 0L, null, null, false);
        when(tradeInternalClient.deviceBySn("sn-cold")).thenReturn(Optional.of(cold));

        mockMvc.perform(post("/jiangyi/api/token/openDoorDeviceStatus")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceSn\":\"sn-cold\"}"))
                .andExpect(status().isForbidden());

        verify(tradeInternalClient).postOpsAlert(
                org.mockito.ArgumentMatchers.eq("JIANGYI_TOKEN_UNACTIVATED"),
                org.mockito.ArgumentMatchers.eq("D2"), anyString());
    }

    @Test
    void tokenIssueBurstTriggersAlertButStillIssues() throws Exception {
        // 审计 A1/P0-3 突发检测：同 SN 10 分钟窗口 ≥3 次签发 → 告警（不拒发，防误伤营业）
        when(tradeInternalClient.deviceBySn("sn-burst")).thenReturn(Optional.of(boundDevice()));

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/jiangyi/api/token/openDoorDeviceStatus")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"deviceSn\":\"sn-burst\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200));
        }
        verify(tradeInternalClient).postOpsAlert(
                org.mockito.ArgumentMatchers.eq("JIANGYI_TOKEN_ISSUE_BURST"),
                org.mockito.ArgumentMatchers.eq("D1"), anyString());
    }

    // ---------- 设备面 401 负向 ----------

    @Test
    void reportWithoutTokenIs401() throws Exception {
        mockMvc.perform(post("/jiangyi/api/device/uploadLockState")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"S1\",\"lockStatus\":\"success\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reportWithForgedTokenIs401() throws Exception {
        // 用不同密钥签发 = 伪造
        String forged = Jwts.builder().subject("D1").compact();
        mockMvc.perform(post("/jiangyi/api/device/uploadLockState")
                        .header("Authorization", "Bearer " + forged)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"S1\",\"lockStatus\":\"success\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reportForUnknownDeviceIs401() throws Exception {
        String token = deviceTokenService.issue(boundDevice());
        when(tradeInternalClient.device("D1")).thenReturn(Optional.empty());
        mockMvc.perform(post("/jiangyi/api/device/uploadLockState")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"S1\",\"lockStatus\":\"success\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reportWithRevokedTokenIs401() throws Exception {
        // claims.ver=0 < 表 ver=1 → 已吊销（方案 §3 吊销语义）
        JiangyiDeviceView revoked = new JiangyiDeviceView("D1", "sn-abc", "CQYB11253", null, null,
                "BOUND", 2L, Instant.now(), null, true);
        String token = deviceTokenService.issue(boundDevice());
        when(tradeInternalClient.device("D1")).thenReturn(Optional.of(revoked));
        mockMvc.perform(post("/jiangyi/api/device/uploadLockState")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"S1\",\"lockStatus\":\"success\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reportWithRetiredDeviceIs401() throws Exception {
        String token = deviceTokenService.issue(boundDevice());
        JiangyiDeviceView retired = new JiangyiDeviceView("D1", "sn-abc", "CQYB11253", null, null,
                "RETIRED", 1L, Instant.now(), null, true);
        when(tradeInternalClient.device("D1")).thenReturn(Optional.of(retired));
        mockMvc.perform(post("/jiangyi/api/device/uploadLockState")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"S1\",\"lockStatus\":\"success\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ---------- 鉴权通过后的透传 ----------

    @Test
    void lockFailIsForwardedToTradeOpenFailed() throws Exception {
        String token = deviceTokenService.issue(boundDevice());
        when(tradeInternalClient.device("D1")).thenReturn(Optional.of(boundDevice()));

        mockMvc.perform(post("/jiangyi/api/device/uploadLockState")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"S1\",\"lockStatus\":\"fail\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200));

        // 透传：开锁失败必须通知 trade open-failed（markOpenDoorFailed 幂等侧）
        verify(tradeInternalClient).postOpenFailed(
                org.mockito.ArgumentMatchers.eq("S1"), anyString(),
                org.mockito.ArgumentMatchers.eq("D1"));
    }

    @Test
    void idempotentDoubleLockReportStill200() throws Exception {
        // 同一 token 连续两次上报（模拟设备重发）：第二次鉴权仍通过（token 未吊销）
        String token = deviceTokenService.issue(boundDevice());
        when(tradeInternalClient.device("D1")).thenReturn(Optional.of(boundDevice()));

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/jiangyi/api/device/uploadLockState")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orderNo\":\"S1\",\"lockStatus\":\"success\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200));
        }
        assertFalse(token.isBlank());
    }
}
