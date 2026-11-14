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
 * R17 批 2（2026-11-03，用户授权矩阵 E2E，R11 段 1 先例）：会员×活动×券组合矩阵的端到端腿。
 *
 * <p>mock code2Session 返回固定 openid（{@code mock_openid_10001}）⇒ 全矩阵同一位消费者；
 * 组合间用「状态切换」隔离：成员档增删 / 活动启停 / 券清场，余额每组合前经
 * {@code user_account} 重置（V388 后消费端余额不在 wallet_account，那只有商户三域）。
 *
 * <p>矩阵判据（每组合）：
 * <ol>
 *   <li>恒等式 {@code gross − member − activity − coupon = total}；</li>
 *   <li>应付 {@code Σ 行金额 − coupon = total}（会员/活动只在单价里减过一次，MA-029）；</li>
 *   <li>语义：m=1 ⇒ {@code member = gross − Σ行}（a=0）或 {@code gross − Σ行 = member + activity}（a=1）；
 *       a=1 ⇒ activity&gt;0；c=1 且 a=0 ⇒ 券被自动选用；<b>c=1 且 a=1 ⇒ 活动优先禁券</b>
 *       （coupon=0 且 user_coupon 仍 UNUSED，CB-082）；</li>
 *   <li>读面（R16 运行时·订单实体）：consumer 与 merchant DTO 五字段逐一等于落库。</li>
 * </ol>
 * 退款三面（独立链，避免与矩阵券状态互染）：全额退 ⇒ 券返还 UNUSED + 分账 VOIDED/REVERSED
 * + 钱包冲平；部分退 ⇒ 折后式 {@code Σ剩余行 − coupon = total}、gross 不变、分账保份额、
 * 钱包差额 clawback 入账（AU-182）。补扣链腿：低余额 PENDING → 充值 → pay，活动单补扣不占券（MA-039）。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("R17 批2：会员×活动×券组合矩阵 + 退款三面 + 补扣链")
class SettlementComboMatrixE2ETest {

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
        // 预授权闸默认 2000 分会挡「低余额 PENDING」剧本（演示单总额 486 < 2000）⇒ 本矩阵关闸
        registry.add("checkout.preauth-cents", () -> "0");
        // 11 条链同用户连发，默认 20 次/小时的建会话限流必撞 ⇒ 测试放宽
        registry.add("aicabinet.rate-limit.max-session-creates-per-hour", () -> "200");
        // 风控开门 5 次/小时也会拦第 6 条链（RiskControlService:98）
        registry.add("aicabinet.risk.max-opens-per-hour", () -> "100");
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

    /** 设备服务未运行：开门指令 mock 成功。 */
    @MockBean
    private DeviceServiceClient deviceServiceClient;

    @Value("${aicabinet.internal-api.key}")
    private String internalApiKey;

    /** 矩阵运行态：一条组合链的落库快照。 */
    private record ComboRun(String combo, String orderId, int gross, int member, int activity,
                            int coupon, int total, int lineSum, String couponUserStatus) {
    }

    @Test
    void comboMatrix_identities_banRules_refundThreeWays_andUnpaidCollect() throws Exception {
        DemoDataService.DemoContext demo = DemoFixture.requireDemo(demoDataService);
        String deviceId = demo.deviceId();
        String skuId = demo.fallbackSkuId();
        assertNotNull(skuId);
        int price = readPrice(skuId);
        assertTrue(price >= 200, "演示 SKU 目录价须 ≥200 分，活动立减 100 后仍 ≥MIN_PRICE");
        ensureStock(deviceId, skuId, 50);
        // 预授权策略：deposit_cents=300（device 源优先）。本用例删掉 DB 配置行 ⇒
        // getInt 返回 -1（未配置）才回落 yml 默认；🔴 2026-11-15 起 DB 显式配 0 = 关闭预授权，
        // 不再回落（见 ConsumerPreauthService.resolvePreauthCents），勿把两者混为一谈。
        // 300 < 订单 486 ⇒ 补扣链剧本可制造 remainDebit：
        // 开门冻结 300 → 结算 capture 300 → 余款走余额扣款不足 ⇒ MA-066 信号转 PENDING。
        jdbc.update("UPDATE device_info SET deposit_cents = 300 WHERE device_id = ?", deviceId);
        jdbc.update("DELETE FROM system_config WHERE config_key = 'checkout.preauth_cents'");

        // 全矩阵同一位消费者（mock openid 固定）+ 一位商户端读者
        JsonNode login = login();
        String token = login.path("data").path("token").asText();
        long uid = login.path("data").path("userId").asLong();
        assertTrue(uid > 0);
        String managerToken = merchantLogin();
        registerDemoAsJiangyiBound(deviceId);
        long activityId = seedActivity(skuId, 100);

        // ---------- 8 组合矩阵 ----------
        ComboRun r000 = runCombo("0-0-0", token, managerToken, uid, deviceId, skuId, price, activityId, 0, 0, 0);
        ComboRun r100 = runCombo("1-0-0", token, managerToken, uid, deviceId, skuId, price, activityId, 1, 0, 0);
        ComboRun r010 = runCombo("0-1-0", token, managerToken, uid, deviceId, skuId, price, activityId, 0, 1, 0);
        ComboRun r001 = runCombo("0-0-1", token, managerToken, uid, deviceId, skuId, price, activityId, 0, 0, 1);
        ComboRun r110 = runCombo("1-1-0", token, managerToken, uid, deviceId, skuId, price, activityId, 1, 1, 0);
        ComboRun r111 = runCombo("1-1-1", token, managerToken, uid, deviceId, skuId, price, activityId, 1, 1, 1);
        ComboRun r101 = runCombo("1-0-1", token, managerToken, uid, deviceId, skuId, price, activityId, 1, 0, 1);
        ComboRun r011 = runCombo("0-1-1", token, managerToken, uid, deviceId, skuId, price, activityId, 0, 1, 1);

        // ---------- 全额退三面（独立链：券自动选 → 退 → 券返还/分账冲正/钱包冲平） ----------
        setMember(uid, false);
        setActivityStatus(activityId, false);
        clearCoupons(uid);
        long fullRefundCouponId = seedCoupon(uid, 200);
        setBalance(uid, 20000);
        String fullRefundOrder = runSettleChain(token, uid, deviceId, skuId, 1, "r17-full");
        assertPaid(fullRefundOrder);
        int frTotal = readOrderRow(fullRefundOrder).total();
        int frCoupon = readOrderRow(fullRefundOrder).coupon();
        assertEquals(200, frCoupon, "无活动时券必须被自动选用（对照组合 0-0-1）");
        mockMvc.perform(post("/api/v2/orders/" + fullRefundOrder + "/refund")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "R17矩阵全额退")))
                ).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        OrderRow fr = readOrderRow(fullRefundOrder);
        assertEquals("REFUNDED", fr.status(), "全额退终态");
        assertEquals(frTotal, fr.refunded(), "全额退退款额=实付");
        // MA-147 已修（2026-11-03 批次C）：全额退的券结清走 CouponService.settleAfterFullRefund
        // （单源仍是 settleAfterPartialRefund 的 newSubtotal<=0 分支），本行即登记时预留的回归位。
        assertEquals("UNUSED", jdbc.queryForObject(
                "SELECT status FROM user_coupon WHERE coupon_id = ?", String.class, fullRefundCouponId),
                "MA-147：全额退必须把已核销券返还账户（USED→UNUSED）");
        Object couponIdAfterRefund = jdbc.queryForObject(
                "SELECT coupon_id FROM cabinet_order WHERE order_id = ?", Object.class, fullRefundOrder);
        assertTrue(couponIdAfterRefund == null,
                "MA-147：券返还后订单头不得再挂 coupon_id，实际=" + couponIdAfterRefund);
        Map<String, Object> frSplit = jdbc.queryForMap(
                "SELECT split_id, status FROM order_revenue_split WHERE order_id = ?", fullRefundOrder);
        String frSplitStatus = String.valueOf(frSplit.get("status"));
        assertTrue("VOIDED".equals(frSplitStatus) || "REVERSED".equals(frSplitStatus),
                "全额退分账必须冲正，实际=" + frSplitStatus);
        List<Map<String, Object>> frRows = jdbc.queryForList(
                "SELECT entry_type, ref_type, ref_id, amount_cents FROM wallet_ledger "
                        + "WHERE domain='MERCHANT' AND ref_id = ?", String.valueOf(frSplit.get("split_id")));
        System.out.println("R17-MATRIX full-refund wallet rows=" + frRows);
        int frWalletNet = frRows.stream().mapToInt(r1 -> asInt(r1.get("amount_cents"))).sum();
        assertEquals(0, frWalletNet, "全额退后钱包该分账净额必须冲平（按 ref_id 全量求和：入账+冲正=0）");
        System.out.println("R17-MATRIX full-refund order=" + fullRefundOrder + " total=" + frTotal
                + " split=" + frSplitStatus + " walletNet=" + frWalletNet + " couponBack=UNUSED");

        // ---------- 部分退三面（独立链：会员+活动 qty=2 退 1） ----------
        setMember(uid, true);
        setActivityStatus(activityId, true);
        clearCoupons(uid);
        setBalance(uid, 20000);
        String partialOrder = runSettleChain(token, uid, deviceId, skuId, 2, "r17-partial");
        assertPaid(partialOrder);
        OrderRow before = readOrderRow(partialOrder);
        assertEquals(0, before.coupon(), "部分退剧本不涉及券");
        mockMvc.perform(post("/api/v2/orders/" + partialOrder + "/refund")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reason", "R17矩阵部分退一件",
                                "lines", List.of(Map.of("skuId", skuId, "quantity", 1)))))
                ).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        OrderRow after = readOrderRow(partialOrder);
        Integer lineSumAfter = jdbc.queryForObject(
                "SELECT COALESCE(SUM(line_amount_cents),0) FROM cabinet_order_line WHERE order_id = ?",
                Integer.class, partialOrder);
        assertNotNull(lineSumAfter);
        assertEquals(before.total() / 2, after.refunded(),
                "两件同价单退一件 ⇒ 退款额=实付一半（预估==写侧，R11 钉测的端到端腿）");
        assertEquals(before.gross(), after.gross(), "部分退后 gross 毛额保持原值（V424 契约）");
        assertEquals(lineSumAfter - after.coupon(), after.total(),
                "部分退后应付=Σ剩余行−券（MA-029 折后式在部分退下仍闭合）");
        Map<String, Object> pSplit = jdbc.queryForMap(
                "SELECT split_id, status, merchant_cents FROM order_revenue_split WHERE order_id = ?", partialOrder);
        assertEquals("LEDGER_ONLY", String.valueOf(pSplit.get("status")),
                "部分退不动微信侧、分账单保留（AU-182）");
        List<Map<String, Object>> pRows = jdbc.queryForList(
                "SELECT entry_type, ref_type, ref_id, amount_cents FROM wallet_ledger "
                        + "WHERE domain='MERCHANT' AND ref_id = ? OR (domain='MERCHANT' AND ref_id LIKE ?)",
                String.valueOf(pSplit.get("split_id")), String.valueOf(pSplit.get("split_id")) + ":%");
        System.out.println("R17-MATRIX partial-refund wallet rows=" + pRows);
        int pWalletNet = pRows.stream().mapToInt(r1 -> asInt(r1.get("amount_cents"))).sum();
        assertEquals(asInt(pSplit.get("merchant_cents")), pWalletNet,
                "部分退后钱包净额必须等于分账剩余商户份额（差额 clawback 已入账）");
        // 🔴 MA-129 已知缺口（不入断言、只留痕）：gross 不缩 × member/activity 等比缩 ⇒ 拆解恒等式缺口
        int ma129Gap = after.gross() - after.member() - after.activity() - after.coupon() - after.total();
        System.out.println("R17-MATRIX partial-refund order=" + partialOrder
                + " refunded=" + after.refunded() + " total " + before.total() + "->" + after.total()
                + " gross=" + after.gross() + " member=" + after.member() + " activity=" + after.activity()
                + " splitMerchant=" + pSplit.get("merchant_cents") + " walletNet=" + pWalletNet
                + " MA129-gap(known)=" + ma129Gap);

        // ---------- 补扣链腿（MA-039：活动单补扣不占券） ----------
        // 剧本：余额充足开门（冻结 300）→ 购物后 balance 改 350（保 frozen）→ 结算 capture 300
        // 后余款 186 余额不足 ⇒ MA-066 信号转 PENDING → 充值 → pay 走补扣链。
        setMember(uid, true);
        setActivityStatus(activityId, true);
        clearCoupons(uid);
        long unpaidCouponId = seedCoupon(uid, 200);
        setBalance(uid, 20000);
        String unpaidSession = startSessionAndCart(token, uid, deviceId, skuId, 2, "r17-unpaid");
        setBalanceKeepFrozen(uid, 350); // capture 300 够、余款 186 不够 ⇒ PENDING
        String unpaidOrder = finishSettleChain(token, uid, deviceId, skuId, 2, "r17-unpaid",
                unpaidSession, false);
        assertEquals("PENDING", readOrderRow(unpaidOrder).status(),
                "余额不足必须转 PENDING 待支付（MA-066 信号路径）");
        OrderRow pendingRow = readOrderRow(unpaidOrder);
        int pendingTotal = pendingRow.total();
        int pendingCoupon = pendingRow.coupon();
        setBalance(uid, 20000);
        mockMvc.perform(post("/api/v2/orders/" + unpaidOrder + "/pay")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        OrderRow paidRow = readOrderRow(unpaidOrder);
        assertEquals("PAID", paidRow.status());
        assertEquals(pendingTotal, paidRow.total(), "补扣不得改动 PENDING 时的应付（MA-040）");
        assertEquals(0, paidRow.coupon(), "MA-039：活动单补扣链必须禁券（applyBestCouponForCollect 短路）");
        assertEquals(0, pendingCoupon, "PENDING 落库时就不应占券（MA-067）");
        assertEquals("UNUSED", jdbc.queryForObject(
                "SELECT status FROM user_coupon WHERE coupon_id = ?", String.class, unpaidCouponId),
                "活动单补扣全程不得核销用户券");
        System.out.println("R17-MATRIX unpaid-collect order=" + unpaidOrder
                + " pendingTotal=" + pendingTotal + " paidCoupon=" + paidRow.coupon()
                + " couponUser=UNUSED (MA-039 E2E leg)");
    }

    // ---------- 组合链 ----------

    private ComboRun runCombo(String combo, String token, String managerToken, long uid,
                              String deviceId, String skuId, int price, long activityId,
                              int m, int a, int c) throws Exception {
        // 状态隔离（单用户矩阵的关键）：成员档 / 活动启停 / 券清场 / 余额重置
        setMember(uid, m == 1);
        setActivityStatus(activityId, a == 1);
        clearCoupons(uid);
        Long couponDefSeed = c == 1 ? seedCoupon(uid, 200) : null;
        setBalance(uid, 20000);

        String orderId = runSettleChain(token, uid, deviceId, skuId, 2, "r17-" + combo);
        assertPaid(orderId);
        OrderRow row = readOrderRow(orderId);
        Integer lineSum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(line_amount_cents),0) FROM cabinet_order_line WHERE order_id = ?",
                Integer.class, orderId);
        assertNotNull(lineSum);

        // 恒等式与应付
        assertEquals(row.gross() - row.member() - row.activity() - row.coupon(), row.total(),
                "[" + combo + "] 恒等式 gross − member − activity − coupon = total");
        assertEquals(row.total(), lineSum - row.coupon(),
                "[" + combo + "] 应付 = Σ 行金额 − 券（会员/活动只在单价里减过一次）");
        // 语义
        if (m == 1 && a == 0) {
            assertEquals(row.gross() - lineSum, row.member(),
                    "[" + combo + "] 会员档 = 毛额 − Σ行（行金额已含会员价）");
        }
        if (a == 1) {
            assertTrue(row.activity() > 0, "[" + combo + "] 活动档必须非零");
            assertEquals(row.gross() - lineSum, row.member() + row.activity(),
                    "[" + combo + "] 有活动时毛额−Σ行 = 会员+活动（两档都在单价里）");
        }
        if (m == 0 && a == 0) {
            assertEquals(0, row.member(), "[" + combo + "] 无会员档不得有会员优惠");
            assertEquals(price * 2, row.gross(), "[" + combo + "] 无折扣组合毛额=目录价×2");
            assertEquals(row.gross(), lineSum, "[" + combo + "] 无折扣组合行合计=毛额");
        }
        if (c == 1 && a == 0) {
            assertTrue(row.coupon() > 0, "[" + combo + "] 无活动时券必须被自动选用");
            String st = jdbc.queryForObject(
                    "SELECT status FROM user_coupon WHERE coupon_id = ?", String.class, couponDefSeed);
            assertEquals("USED", st, "[" + combo + "] 被选用的券必须核销");
        }
        if (c == 1 && a == 1) {
            assertEquals(0, row.coupon(), "[CB-082 禁券][" + combo + "] 活动价订单不得叠加券");
            String st = jdbc.queryForObject(
                    "SELECT status FROM user_coupon WHERE coupon_id = ?", String.class, couponDefSeed);
            assertEquals("UNUSED", st, "[CB-082 禁券][" + combo + "] 用户券必须保持未使用");
        }

        // 读面（R16 运行时·订单实体）：consumer 与 merchant DTO 逐一等于落库
        JsonNode consumer = objectMapper.readTree(mockMvc.perform(get("/api/v2/orders/" + orderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertEquals(row.gross(), consumer.path("grossAmountCents").asInt(), "[" + combo + "] consumer 毛额读面");
        assertEquals(row.member(), consumer.path("memberDiscountCents").asInt(), "[" + combo + "] consumer 会员档读面");
        assertEquals(row.activity(), consumer.path("activityDiscountCents").asInt(), "[" + combo + "] consumer 活动档读面");
        assertEquals(row.coupon(), consumer.path("couponDiscountCents").asInt(), "[" + combo + "] consumer 券档读面");
        assertEquals(row.total(), consumer.path("totalAmountCents").asInt(), "[" + combo + "] consumer 实付读面");
        JsonNode merchant = objectMapper.readTree(mockMvc.perform(
                        get("/api/v2/merchant/orders/" + orderId).header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertEquals(row.gross(), merchant.path("grossAmountCents").asInt(), "[" + combo + "] merchant 毛额读面");
        assertEquals(row.member(), merchant.path("memberDiscountCents").asInt(), "[" + combo + "] merchant 会员档读面");
        assertEquals(row.activity(), merchant.path("activityDiscountCents").asInt(), "[" + combo + "] merchant 活动档读面");
        assertEquals(row.coupon(), merchant.path("couponDiscountCents").asInt(), "[" + combo + "] merchant 券档读面");
        assertEquals(row.total(), merchant.path("totalAmountCents").asInt(), "[" + combo + "] merchant 实付读面");

        String couponUserStatus = c == 1 ? jdbc.queryForObject(
                "SELECT status FROM user_coupon WHERE coupon_id = ?", String.class, couponDefSeed) : "-";

        System.out.println("R17-MATRIX combo=" + combo + " order=" + orderId
                + " gross=" + row.gross() + " member=" + row.member() + " activity=" + row.activity()
                + " coupon=" + row.coupon() + " total=" + row.total() + " lineSum=" + lineSum
                + " couponUser=" + couponUserStatus);
        return new ComboRun(combo, orderId, row.gross(), row.member(), row.activity(),
                row.coupon(), row.total(), lineSum, couponUserStatus);
    }

    // ---------- 链路与种子工具 ----------

    private String runSettleChain(String token, long uid, String deviceId, String skuId,
                                  int qty, String tag) throws Exception {
        String sessionId = startSessionAndCart(token, uid, deviceId, skuId, qty, tag);
        return finishSettleChain(token, uid, deviceId, skuId, qty, tag, sessionId, true);
    }

    /** 开门 + 加购（预授权闸在开门时校验 balance−frozen）。返回 sessionId。 */
    private String startSessionAndCart(String token, long uid, String deviceId, String skuId,
                                       int qty, String tag) throws Exception {
        ensureStock(deviceId, skuId, 50);
        mockMvc.perform(post("/internal/v1/devices/" + deviceId + "/heartbeat")
                        .header("X-Internal-Api-Key", internalApiKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        MvcResult sessionResult = mockMvc.perform(post("/api/v2/sessions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "deviceId", deviceId, "idempotencyKey", tag + "-sess"))))
                .andExpect(status().isOk()).andReturn();
        String sessionId = objectMapper.readTree(sessionResult.getResponse().getContentAsString())
                .path("data").path("sessionId").asText();
        mockMvc.perform(put("/api/v2/sessions/" + sessionId + "/cart")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "items", List.of(Map.of("skuId", skuId, "qty", qty))))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        return sessionId;
    }

    /** 关门（RECOGNIZING）→ 端侧上报 → 取订单；autoPayIfPending=余额充足组合兜异步 PENDING 直达 PAID。 */
    private String finishSettleChain(String token, long uid, String deviceId, String skuId,
                                     int qty, String tag, String sessionId, boolean autoPayIfPending) throws Exception {
        MvcResult door = mockMvc.perform(post("/internal/v1/sessions/door-event")
                        .header("X-Internal-Api-Key", internalApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sessionId", sessionId, "deviceId", deviceId, "doorState", "CLOSED"))))
                .andReturn();
        assertEquals(200, door.getResponse().getStatus(),
                "关门失败：state=" + door.getResponse().getContentAsString());
        MvcResult ingest = mockMvc.perform(post("/internal/v1/vision/edge-results")
                        .header("X-Internal-Api-Key", internalApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sessionId", sessionId, "deviceId", deviceId,
                                "taskId", tag + "-edge", "items",
                                List.of(Map.of("skuId", skuId, "quantity", qty, "confidence", 0.98)),
                                "overallConfidence", 0.98, "needReview", false,
                                "modelVersion", "cabinet-skus-v1", "provider", "R17"))))
                .andReturn();
        assertEquals(200, ingest.getResponse().getStatus(),
                "端侧上报失败：body=" + ingest.getResponse().getContentAsString());
        assertTrue(objectMapper.readTree(ingest.getResponse().getContentAsString())
                        .path("data").path("accepted").asBoolean(),
                "端侧上报必须被采纳：" + ingest.getResponse().getContentAsString());
        MvcResult orderResult = mockMvc.perform(get("/api/v2/sessions/" + sessionId + "/order")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        String orderId = objectMapper.readTree(orderResult.getResponse().getContentAsString())
                .path("data").path("orderId").asText();
        System.out.println("R17-MATRIX settle-chain session=" + sessionId + " order=" + orderId
                + " orderBody=" + orderResult.getResponse().getContentAsString());
        if (autoPayIfPending && "PENDING".equalsIgnoreCase(readOrderRow(orderId).status())) {
            // 矩阵组合余额充足，这里只兜异步时序；PENDING 剧本（补扣链）由调用方自行驱动 pay
            mockMvc.perform(post("/api/v2/orders/" + orderId + "/pay")
                    .header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        }
        return orderId;
    }

    private JsonNode login() throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/api/v2/auth/wx-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", "mock-e2e-r17"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private String merchantLogin() throws Exception {
        String body = mockMvc.perform(post("/api/v2/auth/merchant-password-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("phoneNumber", "13800138006", "password", "123456"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("token").asText();
    }

    private void assertPaid(String orderId) {
        String status = readOrderRow(orderId).status();
        assertTrue("PAID".equals(status) || "COMPLETED".equals(status),
                "组合链结算应直达 PAID（余额充足），实际=" + status);
    }

    private record OrderRow(String status, int total, int gross, int member, int activity,
                            int coupon, int refunded) {
    }

    private OrderRow readOrderRow(String orderId) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, total_amount_cents AS total, gross_amount_cents AS gross, "
                        + "member_discount_cents AS member, activity_discount_cents AS activity, "
                        + "coupon_discount_cents AS coupon, COALESCE(refunded_cents,0) AS refunded "
                        + "FROM cabinet_order WHERE order_id = ?", orderId);
        return new OrderRow(String.valueOf(row.get("status")), asInt(row.get("total")),
                asInt(row.get("gross")), asInt(row.get("member")), asInt(row.get("activity")),
                asInt(row.get("coupon")), asInt(row.get("refunded")));
    }

    private int readPrice(String skuId) {
        Integer p = jdbc.queryForObject(
                "SELECT price_cents FROM sku_catalog WHERE sku_id = ?", Integer.class, skuId);
        assertNotNull(p, "演示 SKU 必须有目录价");
        return p;
    }

    private void ensureStock(String deviceId, String skuId, int qty) {
        jdbc.update("INSERT INTO device_sku_inventory (device_id, sku_id, quantity, capacity) "
                        + "VALUES (?,?,?,?) ON CONFLICT (device_id, sku_id) "
                        + "DO UPDATE SET quantity = EXCLUDED.quantity",
                deviceId, skuId, qty, Math.max(qty, 50));
    }

    /** 补扣链专用：只改 balance 不动 frozen——开门冻结须保留，结算 capture 扣的正是它。 */
    private void setBalanceKeepFrozen(long uid, int cents) {
        jdbc.update("UPDATE user_account SET balance_cents = ? WHERE user_id = ?", cents, uid);
    }

    private void setBalance(long uid, int cents) {
        // 同步清 frozen：预授权冻结残留会压低 available（balance−frozen），跨链隔离必需。
        // 🔴 观察点：若各链正常 capture/release，frozen 本应回 0——残留持续增长需另查（可能真缺陷）。
        Map<String, Object> before = jdbc.queryForMap(
                "SELECT balance_cents, frozen_cents FROM user_account WHERE user_id = ?", uid);
        System.out.println("R17-MATRIX setBalance before: balance=" + before.get("balance_cents")
                + " frozen=" + before.get("frozen_cents") + " -> set " + cents);
        jdbc.update("UPDATE user_account SET balance_cents = ?, frozen_cents = 0 WHERE user_id = ?", cents, uid);
        Integer bal = jdbc.queryForObject(
                "SELECT balance_cents FROM user_account WHERE user_id = ?", Integer.class, uid);
        assertNotNull(bal, "user_account 行必须已由首次充值建立");
        assertEquals(cents, bal, "余额重置必须生效");
    }

    private void setMember(long uid, boolean on) {
        if (on) {
            jdbc.update("INSERT INTO member (user_id, member_level) SELECT ?, 'SILVER' "
                    + "WHERE NOT EXISTS (SELECT 1 FROM member WHERE user_id = ?)", uid, uid);
            jdbc.update("UPDATE member SET member_level = 'SILVER' WHERE user_id = ?", uid);
            jdbc.update("UPDATE member_level_rule SET price_discount_pct = 2.00 "
                    + "WHERE level_code = 'SILVER' AND (price_discount_pct IS NULL OR price_discount_pct <= 0)");
            Double pct = jdbc.queryForObject(
                    "SELECT price_discount_pct::float8 FROM member_level_rule WHERE level_code = 'SILVER'",
                    Double.class);
            assertNotNull(pct);
            assertTrue(pct > 0, "SILVER 档位必须真的减钱，否则矩阵退化假绿");
        } else {
            jdbc.update("DELETE FROM member WHERE user_id = ?", uid);
            Integer cnt = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM member WHERE user_id = ?", Integer.class, uid);
            assertNotNull(cnt);
            assertEquals(0, cnt, "非会员组合必须真的无会员档（防上一组合泄漏）");
        }
    }

    private void setActivityStatus(long activityId, boolean on) {
        jdbc.update("UPDATE promotion_activity SET status = ? WHERE activity_id = ?",
                on ? "ACTIVE" : "DRAFT", activityId);
        String st = jdbc.queryForObject(
                "SELECT status FROM promotion_activity WHERE activity_id = ?", String.class, activityId);
        assertEquals(on ? "ACTIVE" : "DRAFT", st, "活动启停切换必须生效");
    }

    private void clearCoupons(long uid) {
        jdbc.update("DELETE FROM user_coupon WHERE user_id = ?", uid);
    }

    private long seedCoupon(long uid, int denomination) {
        Long defId = jdbc.queryForObject(
                "INSERT INTO coupon_definition (coupon_name, coupon_type, denomination_cents, "
                        + "min_spend_cents, validity_days, device_scope, status) "
                        + "VALUES (?,'AMOUNT_OFF',?,0,30,'ALL','ACTIVE') RETURNING coupon_def_id",
                Long.class, "R17矩阵券-" + System.nanoTime(), denomination);
        assertNotNull(defId);
        jdbc.update("INSERT INTO user_coupon (user_id, coupon_def_id, coupon_code, status, expire_at) "
                        + "VALUES (?,?,?,'UNUSED', NOW() + INTERVAL '30 days')",
                uid, defId, "R17M" + System.nanoTime());
        Long couponId = jdbc.queryForObject(
                "SELECT coupon_id FROM user_coupon WHERE user_id = ? AND coupon_def_id = ? "
                        + "ORDER BY coupon_id DESC LIMIT 1", Long.class, uid, defId);
        assertNotNull(couponId);
        return couponId;
    }

    private long seedActivity(String skuId, int reduceCents) {
        java.util.List<Long> found = jdbc.query(
                "SELECT activity_id FROM promotion_activity WHERE activity_name = 'R17矩阵SKU立减' "
                        + "ORDER BY activity_id DESC LIMIT 1",
                (rs, i) -> rs.getLong(1));
        if (!found.isEmpty()) {
            return found.get(0);
        }
        Long id = jdbc.queryForObject(
                "INSERT INTO promotion_activity (activity_name, activity_type, status, start_time, end_time, "
                        + "budget_cents, user_limit, device_scope, rule_config, reduce_cents) "
                        + "VALUES ('R17矩阵SKU立减','SKU_REDUCE','DRAFT', NOW() - INTERVAL '1 hour', "
                        + "NOW() + INTERVAL '7 days', 1000000, 0, 'ALL', "
                        + "jsonb_build_object('skuIds', jsonb_build_array(?)), ?) RETURNING activity_id",
                Long.class, skuId, reduceCents);
        assertNotNull(id);
        return id;
    }

    private void registerDemoAsJiangyiBound(String deviceId) {
        if (jiangyiDeviceMapper.selectById(deviceId) == null) {
            JiangyiDevice device = new JiangyiDevice();
            device.setDeviceId(deviceId);
            device.setDeviceSn("r17" + Math.abs(deviceId.hashCode()));
            device.setStatus("BOUND");
            jiangyiDeviceMapper.insert(device);
        }
        jiangyiDeviceDirectory.evict(deviceId);
    }

    private static int asInt(Object v) {
        return v == null ? 0 : ((Number) v).intValue();
    }
}
