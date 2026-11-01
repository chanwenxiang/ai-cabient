package com.aicabinet.trade.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
import com.aicabinet.trade.domain.JiangyiDevice;
import com.aicabinet.trade.mapper.JiangyiDeviceMapper;
import com.aicabinet.trade.service.DemoDataService;
import com.aicabinet.trade.service.JiangyiDeviceDirectory;
import com.aicabinet.trade.support.DemoFixture;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R11 段 1（2026-11-02）：会员单全链路四层互证——真 HTTP 链 + 真库 + 分账 + 钱包，逐段对金额。
 *
 * <p>链路：实名 → 充值（dev mock）→ 开门 → 关门停 RECOGNIZING → 端侧识别上报（edge-results）
 * → 结算扣款 → 分账入账（0 抽成档 = LEDGER_ONLY）→ 商户钱包 SPLIT_CREDIT。
 *
 * <p>本用例同时是 <b>MA-029 方案 A 的端到端腿</b>（单测/`MemberDiscountLedgerSqlIT` 证的是写入口，
 * 这里证「从消费者点单到商户钱包」整条链上会员折扣只生效一次）：
 * <ol>
 *   <li>非零前置：把消费用户置成 SILVER 2% ⇒ 若档位是 0，①「只折一次」与②「折两次」在数字上
 *       不可区分，本用例就会假绿（所以先断言 {@code member > 0}）；</li>
 *   <li>恒等式：{@code gross − member − activity − coupon = total}；</li>
 *   <li>应付规则：{@code Σ 行金额 − 券 = total}（会员/活动都只在单价里减过一次）；</li>
 *   <li>读面一致：consumer 与 merchant 两端从同一 DTO 读到同样的 member/gross/total；</li>
 *   <li>资金闭合：分账 {@code platform + merchant = gross = 实付}，且钱包台账 SPLIT_CREDIT
 *       金额 = merchant_cents、账户余额 = 台账 balance_after。</li>
 * </ol>
 *
 * <p>🔴 为什么在 Testcontainers 里跑而不在活栈跑：值守期活栈 {@code :80} 上有真实浏览器会话在轮询
 * admin（2026-11-02 18:32 nginx access log 实证），重建/重启 trade 容器会打断他人在用的共享栈
 * （交接书 §4「不碰别的会话的容器/栈」）⇒ 本段以「可重复、随 CI 跑」的 E2E 形态挣得，
 * 活栈复走留在 R11 段 2（届时须先确认无人在用，见 AUTO-RUN-HANDOFF §5）。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("R11 段1：会员单全链路（结算→分账→钱包）四层互证")
class MemberOrderChainE2ETest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aicabinet_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    @SuppressWarnings("resource")
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
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DemoDataService demoDataService;
    @Autowired
    private JiangyiDeviceMapper jiangyiDeviceMapper;
    @Autowired
    private JiangyiDeviceDirectory jiangyiDeviceDirectory;

    /** 设备服务未运行：开门指令 mock 成功，避免会话创建时远程调用失败回滚。 */
    @MockBean
    private DeviceServiceClient deviceServiceClient;

    @Value("${aicabinet.internal-api.key}")
    private String internalApiKey;

    @Test
    void memberOrder_settlesOnceAndMoneyClosesAcrossFourLayers() throws Exception {
        // ---------- 0) 非零前置：把消费者置成 SILVER 2%（一次性测试库，可安全校准） ----------
        JsonNode login = objectMapper.readTree(mockMvc.perform(post("/api/v2/auth/wx-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", "mock-e2e-code"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString());
        String token = login.path("data").path("token").asText();
        long userId = login.path("data").path("userId").asLong();
        assertTrue(userId > 0, "wx-login 必须回 userId，实际=" + userId);

        jdbc.update("INSERT INTO member (user_id, member_level) SELECT ?, 'SILVER' "
                + "WHERE NOT EXISTS (SELECT 1 FROM member WHERE user_id = ?)", userId, userId);
        jdbc.update("UPDATE member SET member_level = 'SILVER' WHERE user_id = ?", userId);
        jdbc.update("UPDATE member_level_rule SET price_discount_pct = 2.00 "
                + "WHERE level_code = 'SILVER' AND (price_discount_pct IS NULL OR price_discount_pct <= 0)");
        Double pct = jdbc.queryForObject(
                "SELECT price_discount_pct::float8 FROM member_level_rule WHERE level_code = 'SILVER'",
                Double.class);
        assertNotNull(pct);
        assertTrue(pct > 0, "SILVER 档位必须真的减钱，否则 ①/② 数字上不可区分（本用例将假绿）");

        // ---------- 1) 实名 + 充值（dev mock，钱只走 mock） ----------
        mockMvc.perform(post("/api/v2/account/verify")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("realName", "R11会员链路", "idCard", "110105194912310011"))))
                .andExpect(status().isOk());

        String rechargeKey = "r11-recharge-" + System.currentTimeMillis();
        MvcResult prepay = mockMvc.perform(post("/api/v2/payment/recharge/prepay")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "channel", "WECHAT", "amountCents", 20000,
                                "idempotencyKey", rechargeKey))))
                .andExpect(status().isOk()).andReturn();
        String rechargeOrderId = objectMapper.readTree(prepay.getResponse().getContentAsString())
                .path("data").path("orderId").asText();
        mockMvc.perform(post("/api/v2/dev/payment/recharge/" + rechargeOrderId + "/mock-success")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // ---------- 2) 开门 → 关门停 RECOGNIZING → 端侧识别上报（qty=2 让会员差额翻倍可辨） ----------
        DemoDataService.DemoContext demo = DemoFixture.requireDemo(demoDataService);
        String deviceId = demo.deviceId();
        String skuId = demo.fallbackSkuId();
        assertNotNull(skuId);

        mockMvc.perform(post("/internal/v1/devices/" + deviceId + "/heartbeat")
                        .header("X-Internal-Api-Key", internalApiKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        MvcResult sessionResult = mockMvc.perform(post("/api/v2/sessions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "deviceId", deviceId,
                                "idempotencyKey", "r11-session-" + System.currentTimeMillis()))))
                .andExpect(status().isOk()).andReturn();
        String sessionId = objectMapper.readTree(sessionResult.getResponse().getContentAsString())
                .path("data").path("sessionId").asText();

        registerDemoAsJiangyiBound(deviceId);

        mockMvc.perform(put("/api/v2/sessions/" + sessionId + "/cart")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "items", List.of(Map.of("skuId", skuId, "qty", 2))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        MvcResult doorResult = mockMvc.perform(post("/internal/v1/sessions/door-event")
                        .header("X-Internal-Api-Key", internalApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sessionId", sessionId, "deviceId", deviceId,
                                "doorState", "CLOSED"))))
                .andExpect(status().isOk()).andReturn();
        assertEquals("RECOGNIZING",
                objectMapper.readTree(doorResult.getResponse().getContentAsString())
                        .path("data").path("state").asText(),
                "将邑柜关门必须停在 RECOGNIZING 等端侧上报（结算绿只能由端侧链挣得）");

        MvcResult ingestResult = mockMvc.perform(post("/internal/v1/vision/edge-results")
                        .header("X-Internal-Api-Key", internalApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sessionId", sessionId, "deviceId", deviceId,
                                "taskId", "r11-edge-" + System.currentTimeMillis(),
                                "items", List.of(Map.of("skuId", skuId, "quantity", 2, "confidence", 0.98)),
                                "overallConfidence", 0.98, "needReview", false,
                                "modelVersion", "cabinet-skus-v1", "provider", "R11"))))
                .andExpect(status().isOk()).andReturn();
        assertTrue(objectMapper.readTree(ingestResult.getResponse().getContentAsString())
                        .path("data").path("accepted").asBoolean(),
                "端侧上报必须被采纳，实际回执=" + ingestResult.getResponse().getContentAsString());

        MvcResult orderResult = mockMvc.perform(get("/api/v2/sessions/" + sessionId + "/order")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        JsonNode orderDto = objectMapper.readTree(orderResult.getResponse().getContentAsString()).path("data");
        String orderId = orderDto.path("orderId").asText();
        if ("PENDING".equalsIgnoreCase(orderDto.path("status").asText())) {
            mockMvc.perform(post("/api/v2/orders/" + orderId + "/pay")
                    .header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        }

        // ---------- 3) 落库层：非零会员档 + 两条恒等式 ----------
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, total_amount_cents AS total, gross_amount_cents AS gross, "
                        + "member_discount_cents AS member, activity_discount_cents AS activity, "
                        + "coupon_discount_cents AS coupon "
                        + "FROM cabinet_order WHERE order_id = ?", orderId);
        assertEquals("PAID", String.valueOf(row.get("status")), "余额已充值，结算应直接 PAID");
        int total = asInt(row.get("total"));
        int gross = asInt(row.get("gross"));
        int member = asInt(row.get("member"));
        int activity = asInt(row.get("activity"));
        int coupon = asInt(row.get("coupon"));
        assertTrue(member > 0,
                "MA-029 入账必须非零（gross=" + gross + " total=" + total + " member=" + member
                        + "）——为 0 说明结算链没真的走会员价，本用例会退化成假绿");
        assertEquals(gross - member - activity - coupon, total,
                "恒等式 gross − member − activity − coupon = total");

        Integer lineSum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(line_amount_cents), 0) FROM cabinet_order_line WHERE order_id = ?",
                Integer.class, orderId);
        assertNotNull(lineSum);
        assertEquals(total, lineSum - coupon,
                "应付 = Σ 行金额 − 券；会员/活动都只在单价里减过一次（行合计=" + lineSum + "）");
        Integer minUnit = jdbc.queryForObject(
                "SELECT MIN(unit_price_cents) FROM cabinet_order_line WHERE order_id = ?",
                Integer.class, orderId);
        assertNotNull(minUnit);
        assertTrue(gross > total, "有会员折扣时毛额必须严格大于应付（gross=" + gross + " total=" + total + "）");
        assertTrue(minUnit >= 1, "行单价不得被折到 0（防 0 元白拿）：" + minUnit);

        // ---------- 4) 读面层：consumer 与 merchant 从同一 DTO 读到同一组数 ----------
        JsonNode consumerView = objectMapper.readTree(mockMvc.perform(get("/api/v2/orders/" + orderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andReturn().getResponse().getContentAsString()).path("data");
        assertEquals(member, consumerView.path("memberDiscountCents").asInt(), "消费者端应看到会员优惠留档");
        assertEquals(gross, consumerView.path("grossAmountCents").asInt(), "消费者端应看到原价毛额");
        assertEquals(total, consumerView.path("totalAmountCents").asInt());

        String managerLogin = mockMvc.perform(post("/api/v2/auth/merchant-password-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("phoneNumber", "13800138006", "password", "123456"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String managerToken = objectMapper.readTree(managerLogin).path("data").path("token").asText();
        assertTrue(!managerToken.isBlank(), "店长登录必须回 token（三端读面一致性的第二端）");
        JsonNode merchantView = objectMapper.readTree(mockMvc.perform(
                        get("/api/v2/merchant/orders/" + orderId)
                                .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString()).path("data");
        assertEquals(member, merchantView.path("memberDiscountCents").asInt(),
                "商户端与消费者端的会员优惠必须是同一个数（同一端点三端字段口径）");
        assertEquals(gross, merchantView.path("grossAmountCents").asInt(), "商户端原价毛额必须与落库一致");
        assertEquals(total, merchantView.path("totalAmountCents").asInt(), "商户端实收金额必须与落库一致");

        // ---------- 5) 资金层：分账闭合 + 钱包入账（0 抽成档 = LEDGER_ONLY） ----------
        Map<String, Object> split = jdbc.queryForMap(
                "SELECT split_id, gross_cents, platform_cents, merchant_cents, status, merchant_id "
                        + "FROM order_revenue_split WHERE order_id = ?", orderId);
        int splitGross = asInt(split.get("gross_cents"));
        int platform = asInt(split.get("platform_cents"));
        int merchantShare = asInt(split.get("merchant_cents"));
        assertEquals(total, splitGross,
                "分账基数必须等于实付（冻结实付口径），否则商户份额跟着错");
        assertEquals(splitGross, platform + merchantShare, "分账闭合：platform + merchant = gross");
        assertEquals(0, platform, "默认档位 platform_rate_bps=0 ⇒ 平台不抽成（V382 拍板）");
        String splitStatus = String.valueOf(split.get("status"));
        assertTrue("LEDGER_ONLY".equals(splitStatus) || "SETTLED".equals(splitStatus),
                "0 抽成且不发分账请求 ⇒ 应为账本型入账，实际=" + splitStatus);

        // 钱包入账按 splitId 幂等（ref_type='SPLIT'、ref_id=split_id）——见 RevenueSplitService
        // #creditWalletIfLedgerOnly，别拿 merchantId 当 ref_id 去查（会 0 行、误判成「没入账」）
        Map<String, Object> credit = jdbc.queryForMap(
                "SELECT amount_cents, balance_after FROM wallet_ledger "
                        + "WHERE entry_type = 'SPLIT_CREDIT' AND ref_type = 'SPLIT' AND ref_id = ? "
                        + "ORDER BY ledger_id DESC LIMIT 1",
                String.valueOf(split.get("split_id")));
        assertEquals(merchantShare, asInt(credit.get("amount_cents")),
                "钱包台账 SPLIT_CREDIT 金额必须等于商户份额");
        Integer walletBalance = jdbc.queryForObject(
                "SELECT balance_cents FROM wallet_account WHERE domain = 'MERCHANT' AND owner_id = ?",
                Integer.class, String.valueOf(split.get("merchant_id")));
        assertNotNull(walletBalance);
        assertEquals(asInt(credit.get("balance_after")), walletBalance.intValue(),
                "台账最后一笔的 balance_after 必须等于账户当前余额（账↔户互证）");

        // 留痕：资金链数字要能在轮报告里逐层复算（「全是 0」不算验证）
        System.out.println("R11-LAYER order=" + orderId
                + " qty=2 gross=" + gross + " member=" + member
                + " activity=" + activity + " coupon=" + coupon + " total=" + total
                + " lineSum=" + lineSum + " split[status=" + splitStatus
                + " gross=" + splitGross + " platform=" + platform + " merchant=" + merchantShare
                + "] walletLedger=" + asInt(credit.get("amount_cents"))
                + " walletBalance=" + walletBalance);

        // ---------- 6) 独立对账 SQL：本库全量不得出现「会员档 > 0 却被折后式判不一致」 ----------
        Integer flagged = jdbc.queryForObject(
                "SELECT count(*) FROM ("
                        + "  SELECT o.order_id, o.total_amount_cents, "
                        + "         COALESCE(SUM(ol.line_amount_cents), 0) AS line_subtotal, "
                        + "         COALESCE(o.coupon_discount_cents, 0) AS coupon, "
                        + "         COALESCE(o.member_discount_cents, 0) AS member "
                        + "  FROM cabinet_order o LEFT JOIN cabinet_order_line ol ON ol.order_id = o.order_id "
                        + "  WHERE o.status IN ('PAID','PARTIAL_REFUNDED') "
                        + "  GROUP BY o.order_id, o.total_amount_cents, o.coupon_discount_cents, o.member_discount_cents "
                        + "  HAVING o.total_amount_cents <> COALESCE(SUM(ol.line_amount_cents), 0) "
                        + "         - COALESCE(o.coupon_discount_cents, 0) "
                        + ") bad WHERE member > 0",
                Integer.class);
        assertEquals(0, flagged, "会员档 > 0 的单不得被巡检折后式判成不一致（MA-029 检测式的核心断言）");
    }

    private static int asInt(Object v) {
        return v == null ? 0 : ((Number) v).intValue();
    }

    /** 演示柜登记为将邑 BOUND（幂等）：判定与 ConsumerE2ETest 同一套，evict 防 10s 负缓存。 */
    private void registerDemoAsJiangyiBound(String deviceId) {
        if (jiangyiDeviceMapper.selectById(deviceId) == null) {
            JiangyiDevice device = new JiangyiDevice();
            device.setDeviceId(deviceId);
            device.setDeviceSn("r11" + Math.abs(deviceId.hashCode()));
            device.setStatus("BOUND");
            jiangyiDeviceMapper.insert(device);
        }
        jiangyiDeviceDirectory.evict(deviceId);
    }
}
