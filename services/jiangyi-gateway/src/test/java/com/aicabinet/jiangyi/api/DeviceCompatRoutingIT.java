package com.aicabinet.jiangyi.api;

import com.aicabinet.jiangyi.auth.DeviceTokenService;
import com.aicabinet.jiangyi.client.TradeInternalClient;
import com.aicabinet.jiangyi.client.TradeInternalClient.JiangyiDeviceView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 设备面路由归属 IT（2026-10-19 补，配 DeviceCompatController / DeviceUnknownPathController）。
 *
 * <p>要验的是两件事，单测做不到：
 * ① {@code /jiangyi/api/**} catch-all 不遮蔽任何已注册端点（Spring PathPattern  specificity
 * 比较器的真实行为，不是我们的推测）；
 * ② 未注册路径走的是「HTTP 200 + body status 404」而不是默认 404 错误页——设备侧按 body
 * status 判成败，这一点错了整条失败语义就白设计。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "aicabinet.jiangyi.jwt-secret=jiangyi-it-secret-0123456789abcdef!!",
        "aicabinet.jiangyi.token-ttl-seconds=3600",
        "aicabinet.jiangyi.public-base-url=https://it-gw.example.com",
        "aicabinet.trade-service.url=http://localhost:59999",
        "aicabinet.jiangyi.ws-container.enabled=false"
})
class DeviceCompatRoutingIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DeviceTokenService deviceTokenService;

    @MockBean
    private TradeInternalClient tradeInternalClient;

    @MockBean
    private com.aicabinet.jiangyi.tracker.CommandTracker commandTracker;

    private static JiangyiDeviceView boundDevice() {
        return new JiangyiDeviceView("D1", "sn-abc", "CQYB10720", "model76", null,
                "BOUND", 1L, Instant.now(), null, true);
    }

    private String authHeader() {
        when(tradeInternalClient.device("D1")).thenReturn(Optional.of(boundDevice()));
        return deviceTokenService.issue(boundDevice());
    }

    @Test
    void catchAllDoesNotShadowRegisteredDeviceEndpoints() throws Exception {
        mockMvc.perform(post("/jiangyi/api/deviceInfo/getDetail")
                        .header("Authorization", "Bearer " + authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.doorNum").value(1));
    }

    @Test
    void compatEndpointsAnswerWithMeasuredDefaults() throws Exception {
        String bearer = "Bearer " + authHeader();
        mockMvc.perform(post("/jiangyi/api/tenantOrder/needCheck")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(false));

        mockMvc.perform(post("/jiangyi/api/deviceInfo/getOtgStatus")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.data").value("open"));

        mockMvc.perform(post("/jiangyi/api/dictionary/getPicSwitch")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.data").value("on"));

        mockMvc.perform(post("/jiangyi/api/tenant/getDeviceBasicConfig")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"CQYB10720\",\"doorNum\":0}"))
                .andExpect(jsonPath("$.data.dictValue").value("NORMAL"))
                .andExpect(jsonPath("$.data.isUseTts").value(1));

        // 重审商品黑名单（每次开门 1 次）：固件按 String[] 反序列化 ⇒ 端到端必须回数组，
        // 落回 catch-all 的 data:"" 会让设备 Gson 抛 Expected BEGIN_ARRAY。
        mockMvc.perform(post("/jiangyi/api/tenant/getOrderRecheckProduct")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"CQYB10720\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(content().string(containsString("\"data\":[]")));
    }

    @Test
    void unregisteredDevicePathFailsInsideEnvelopeBody() throws Exception {
        // 特征包轮询（固件 FeaturePackPuller 5min 一次）：未实现 ⇒ 显式失败，不掩盖成空配置
        mockMvc.perform(post("/jiangyi/api/feature/getFeatureByDevice")
                        .header("Authorization", "Bearer " + authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPackVersion\":0}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.data").value(""));
    }

    @Test
    void moneyPrefixedUnknownPathAlsoFailsInsteadOfSilentlySucceeding() throws Exception {
        // 资金前缀宁可失败：设备侧必须知道「这单没被收下」，绝不能被吞成 200
        mockMvc.perform(post("/jiangyi/api/order/uploadSomeFutureMoneyEndpoint")
                        .header("Authorization", "Bearer " + authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void domainConfigSelfPinsOurOwnBaseInsteadOfLettingFirmwareGuess() throws Exception {
        // 设计文档 §5 域名自钉（拍板 A 第 1 条）：设备切到我方后每次开机仍会拉 domainConfig——
        // 由我方回自己的 base，把「拉取失败回落什么」这个未实测分支彻底消除。
        // env 故意配成不带 /jiangyi/api，验端到端补尾段。
        String bearer = "Bearer " + authHeader();
        mockMvc.perform(post("/jiangyi/api/domainConfig/getConfigStatusByIdentifier")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"CQYB10720\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"status\":null")))
                .andExpect(content().string(containsString("\"msg\":null")))
                .andExpect(jsonPath("$.data").value(1));

        mockMvc.perform(post("/jiangyi/api/domainConfig/getConfigDomainByIdentifier")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"doorNum\":0,\"huodaoNum\":0,\"identifier\":\"CQYB10720\",\"modelNum\":0}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"status\":null")))
                .andExpect(jsonPath("$.data").value("https://it-gw.example.com/jiangyi/api"));

        // 第三端点（APK 开机链 getOtherSocketUrl）：失败会无限重试卡死后续开机链，必须在线
        mockMvc.perform(post("/jiangyi/api/domainConfig/getConfigSocketUrlByIdentifier")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"CQYB10720\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"status\":null")))
                .andExpect(jsonPath("$.data").value("wss://it-gw.example.com/websocket/device/"));
    }

    @Test
    void domainConfigAnswersWithoutTokenForBootstrapRecovery() throws Exception {
        // 2026-10-20 11:15:57 实证：设备 token 过期（TTL 3600s）后固件以「无 Authorization」
        // 探测配置状态——这是它丢配置/换 token 前的自举问路；401 会把自愈链掐死
        // （当时设备因此静默）。三端点免鉴权后仍回自钉值；带 token 的形态由
        // domainConfigSelfPinsOurOwnBaseInsteadOfLettingFirmwareGuess 覆盖。
        mockMvc.perform(post("/jiangyi/api/domainConfig/getConfigStatusByIdentifier")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(1));
        mockMvc.perform(post("/jiangyi/api/domainConfig/getConfigDomainByIdentifier")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("https://it-gw.example.com/jiangyi/api"));
        mockMvc.perform(post("/jiangyi/api/domainConfig/getConfigSocketUrlByIdentifier")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("wss://it-gw.example.com/websocket/device/"));
    }

    @Test
    void unregisteredPathWithoutTokenStillRejectedAs401() throws Exception {
        // catch-all 不得绕过鉴权（探测面不收窄也不行）
        mockMvc.perform(post("/jiangyi/api/feature/reportPackApplied")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
