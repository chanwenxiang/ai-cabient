package com.aicabinet.trade.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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

import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.client.DeviceServiceClient;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E2E - 消费者端核心流程 + 跨域隔离。
 *
 * 微信小程序未配置时（测试 profile）登录走 mock openId（dev-only）；
 * 断言消费者可浏览柜机/商品/会员/订单，且消费者账号访问商家门户被 403 拒绝。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConsumerE2ETest {

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
        // 沙箱重力兜底：视觉服务不可用时按购物车/重力信号结算（见 SettlementService.tryStagingGravitySettle）
        registry.add("aicabinet.gravity-fallback-settle", () -> "true");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${aicabinet.internal-api.key}")
    private String internalApiKey;

    /** 设备服务未运行：开门指令直接 mock 成功，避免会话创建时远程调用失败回滚。 */
    @MockBean
    private DeviceServiceClient deviceServiceClient;

    @Autowired
    private CabinetOrderMapper cabinetOrderMapper;

    @Autowired
    private ShoppingSessionMapper shoppingSessionMapper;

    /** 本用例**直插**的订单号（见 {@link #insertNeverChargedPendingOrder}），用于用例结束后的清理。 */
    private final List<String> f6InsertedOrderIds = new ArrayList<>();

    /**
     * 用例结束后清掉「**从未扣款**」的 PENDING 订单。
     *
     * <p>🔴 必须清理，否则会**污染同用户的既有用例**：{@code UserValidationService.enforceUnpaidDebtBlock}
     * （`debt.block_open_on_pending` 默认 true）在开门前统计 `countByUserIdAndStatus(userId,"PENDING")`，
     * 只要 &gt; 0 就以 412「您有 N 笔待支付账单，请先补缴后再开门」拒绝 —— 而 mock 登录
     * （{@code WeChatMiniAppClient.code2Session} 恒返回 `mock_openid_10001`）让本类**所有**用例都是
     * 同一个用户 10001，于是 `consumer_fullPurchaseFlow` 的开门步骤被带崩。实测红线：
     * {@code ConsumerE2ETest.consumer_fullPurchaseFlow:205 Status expected:<200> but was:<412>}。
     *
     * <p>只删 PENDING（即从未进入扣款的直插行）：这类行在库中**只有它自己**——`payment_operation`
     * 与 `cabinet_order_line` 均为 `ON DELETE CASCADE` 且本就没有子行，`order_revenue_split` /
     * `user_coupon` / `payscore_order` / `ops_exception` 为 `SET NULL`，而 `shopping_session.order_id`
     * 自引用从未被赋值。**已扣款订单不删**：其 `revenue_share_detail` / `invoice_request` 外键是
     * `RESTRICT`（真库 `pg_constraint.confdeltype='r'`），删订单会直接报外键错误，且那条 PAID 订单
     * 本身是合法的业务终态，抹掉反而让派生对账数据（分成、钱包）失去来源。
     *
     * <p>放在 {@code @AfterEach}（而不是用例末尾）：断言失败时也能清理，避免一个失败用例
     * 连锁带崩后续用例（这正是本次踩坑的形态）。
     */
    @AfterEach
    void cleanupNeverChargedFixtureOrders() {
        for (String orderId : f6InsertedOrderIds) {
            cabinetOrderMapper.findById(orderId)
                    .filter(order -> "PENDING".equals(order.getStatus())) // 已 PAID 的删不掉（见上）
                    .ifPresent(order -> cabinetOrderMapper.deleteById(orderId));
        }
        f6InsertedOrderIds.clear();
    }

    private static String consumerToken;

    @Test
    @DisplayName("微信 mock 登录并获取 token")
    void consumer_wxLogin() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/v2/auth/wx-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", "mock-e2e-code"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        consumerToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .path("data").path("token").asText();
    }

    @Test
    @DisplayName("消费者可浏览柜机状态")
    void consumer_deviceStatus() throws Exception {
        mockMvc.perform(get("/api/v2/devices/CAB-001/status")
                        .header("Authorization", "Bearer " + consumerToken()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("消费者可浏览柜机商品")
    void consumer_deviceProducts() throws Exception {
        mockMvc.perform(get("/api/v2/devices/CAB-001/products")
                        .header("Authorization", "Bearer " + consumerToken()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("消费者可查看会员资料")
    void consumer_memberProfile() throws Exception {
        mockMvc.perform(get("/api/v2/member/profile")
                        .header("Authorization", "Bearer " + consumerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("消费者可查看订单列表")
    void consumer_orders() throws Exception {
        mockMvc.perform(get("/api/v2/orders")
                        .header("Authorization", "Bearer " + consumerToken())
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("消费者账号访问商家门户 → 403（跨域隔离）")
    void consumer_cannotAccessMerchantPortal() throws Exception {
        mockMvc.perform(get("/api/v2/merchant/me")
                        .header("Authorization", "Bearer " + consumerToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("消费者完整购买链路：实名→充值→开柜→购物车→关门结算→支付")
    void consumer_fullPurchaseFlow() throws Exception {
        String token = consumerToken();

        // 1) 实名（演示仅姓名+身份证后 4 位）
        mockMvc.perform(post("/api/v2/account/verify")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("realName", "E2E用户", "idCardLast4", "1234"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 2) mock 充值 100 元并确认到账
        String idempotencyKey = "e2e-recharge-" + System.currentTimeMillis();
        MvcResult prepayResult = mockMvc.perform(post("/api/v2/payment/recharge/prepay")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "channel", "WECHAT",
                                "amountCents", 10000,
                                "idempotencyKey", idempotencyKey))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        String rechargeOrderId = objectMapper.readTree(prepayResult.getResponse().getContentAsString())
                .path("data").path("orderId").asText();

        mockMvc.perform(post("/api/v2/dev/payment/recharge/" + rechargeOrderId + "/mock-success")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 3) 设备心跳置为在线（种子数据 CAB-001 为 OFFLINE，开柜校验要求在线）
        mockMvc.perform(post("/internal/v1/devices/CAB-001/heartbeat")
                        .header("X-Internal-Api-Key", internalApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        // 4) 开柜（设备开门指令已被 @MockBean 短路）
        MvcResult sessionResult = mockMvc.perform(post("/api/v2/sessions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "deviceId", "CAB-001",
                                "idempotencyKey", "e2e-session-" + System.currentTimeMillis()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        String sessionId = objectMapper.readTree(sessionResult.getResponse().getContentAsString())
                .path("data").path("sessionId").asText();

        // 5) 购物车（mock 结算按购物车扣款）
        mockMvc.perform(put("/api/v2/sessions/" + sessionId + "/cart")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"skuId\":\"SKU-WATER-001\",\"qty\":1}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 6) 关门事件（设备侧内部接口）→ 触发结算生成订单
        mockMvc.perform(post("/internal/v1/sessions/door-event")
                        .header("X-Internal-Api-Key", internalApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sessionId", sessionId,
                                "deviceId", "CAB-001",
                                "doorState", "CLOSED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 7) 取订单：余额充足应直接 PAID；若为 PENDING 则补一次支付
        MvcResult orderResult = mockMvc.perform(get("/api/v2/sessions/" + sessionId + "/order")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        String orderId = objectMapper.readTree(orderResult.getResponse().getContentAsString())
                .path("data").path("orderId").asText();
        String status = objectMapper.readTree(orderResult.getResponse().getContentAsString())
                .path("data").path("status").asText();
        if ("PENDING".equalsIgnoreCase(status)) {
            mockMvc.perform(post("/api/v2/orders/" + orderId + "/pay")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }

        // 8) 最终断言订单已支付
        mockMvc.perform(get("/api/v2/orders/" + orderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("PAID"));
    }

    // ---------- F6：结算页「显式选择支付方式」在真实 HTTP 层的语义 ----------
    //
    // 渠道语义本身已由 OrderPaymentSelectedChannelTest / PayChannelReadinessTest（单测）覆盖；
    // 这里补的是**接线**——可选 body 的绑定、@Pattern 校验、412 的透出，三者都只在真实请求
    // 管线里成立，单测看不见。

    @Test
    @DisplayName("F6｜不传 body 补缴 → 与接入前一致（可选 body 未破坏原链路）")
    void f6_payWithoutBody_stillCollects() throws Exception {
        String token = consumerToken();
        long userId = account(token).path("userId").asLong();
        String orderId = insertNeverChargedPendingOrder(insertCompletedSession(userId), userId, ORDER_CENTS,
                SENTINEL_CHANNEL);

        // 刻意**带 Content-Type 却不给 body** —— 这正是前端「不选渠道」时发出的真实形态
        // （packages/shared-uni/src/request.ts 恒设 Content-Type: application/json，body 为 undefined）。
        mockMvc.perform(post("/api/v2/orders/" + orderId + "/pay")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        assertEquals("PAID", cabinetOrderMapper.findById(orderId).orElseThrow().getStatus(),
                "无 body 的补缴必须照常扣款并置 PAID");
    }

    @Test
    @DisplayName("F6｜渠道值非法 → 400（@Pattern 先于业务逻辑：订单号不存在也报 400 而非 404）")
    void f6_invalidChannelValue_is400() throws Exception {
        // 用**不存在**的订单号：若校验没接线，最先暴露的会是 404 ORDER_NOT_FOUND；
        // 因此「400」这个结果同时证明了校验真的生效。
        mockMvc.perform(post("/api/v2/orders/NO-SUCH-ORDER/pay")
                        .header("Authorization", "Bearer " + consumerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"BOGUS\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("F6｜显式选择未开通渠道 → 412 且零资金动作（拒绝，而非静默降级扣余额）")
    void f6_unavailableChannel_is412WithoutSilentDowngrade() throws Exception {
        String token = consumerToken();
        JsonNode before = account(token);
        // 前提断言①：该用户确实**未**开通支付宝免密 —— 否则 412 可能因别的原因成立。
        // 🔴 先断 `has`：字段若整体缺失，`asBoolean()` 会返回 false，使下面那条断言**恒真**。
        assertTrue(before.has("alipayAgreementEnabled"), "响应必须真的带该字段，否则断言无分辨力");
        assertEquals(false, before.path("alipayAgreementEnabled").asBoolean());
        // 前提断言②：余额**充足**（种子 100 元 ≫ 本单 20 元）。只有在「够扣」时仍然拒绝，
        // 「不降级」才是有分辨力的判据；余额不足时「拒绝」会与「余额不够」混为一谈。
        assertTrue(before.path("balanceCents").asInt() >= ORDER_CENTS, "本用例要求余额足以支付本单");

        long userId = before.path("userId").asLong();
        String orderId = insertNeverChargedPendingOrder(insertCompletedSession(userId), userId, ORDER_CENTS,
                SENTINEL_CHANNEL);

        MvcResult payResult = mockMvc.perform(post("/api/v2/orders/" + orderId + "/pay")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"ALIPAY\"}"))
                .andReturn();
        // 断言带响应体：失败时能直接看到服务端到底说了什么（避免只为看原因再跑一轮）
        String payBody = payResult.getResponse().getContentAsString();
        assertEquals(412, payResult.getResponse().getStatus(), "期望 412（拒绝），实际响应体=" + payBody);
        assertTrue(payBody.contains("所选支付方式当前不可用"), "应返回不可用文案，实际响应体=" + payBody);

        CabinetOrder after = cabinetOrderMapper.findById(orderId).orElseThrow();
        assertEquals(SENTINEL_CHANNEL, after.getPayChannel(), "拒绝时不得改写渠道（静默降级）");
        assertEquals("PENDING", after.getStatus(), "拒绝后订单必须仍未支付");
        assertNull(after.getPaymentOperationId(), "拒绝时不得落任何资金操作");
        assertEquals(ORDER_CENTS, after.getTotalAmountCents(), "拒绝时订单金额也不得被改写");
        assertEquals(before.path("balanceCents").asInt(), account(token).path("balanceCents").asInt(),
                "零资金动作：余额分文未动");
    }

    @Test
    @DisplayName("F6｜显式选择余额 → 按余额渠道落账（入参大小写归一）")
    void f6_balanceChannel_isChargedViaBalance() throws Exception {
        String token = consumerToken();
        long userId = account(token).path("userId").asLong();
        String orderId = insertNeverChargedPendingOrder(insertCompletedSession(userId), userId, ORDER_CENTS,
                SENTINEL_CHANNEL);

        mockMvc.perform(post("/api/v2/orders/" + orderId + "/pay")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"balance\"}")) // 小写：验证服务端 toUpperCase 归一
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        CabinetOrder after = cabinetOrderMapper.findById(orderId).orElseThrow();
        assertEquals("PAID", after.getStatus(), "显式选余额应完成扣款");
        assertEquals("BALANCE", after.getPayChannel(), "显式选余额必须真按余额渠道落账");
        assertNotNull(after.getPaymentOperationId(), "应产生资金操作");
    }

    // ---------- F6 夹具 ----------

    /**
     * 诱饵渠道值。刻意**不用 {@code BALANCE}**：{@code cabinet_order.pay_channel} 的字段默认值恰是
     * {@code BALANCE}，用「等于默认值」来断言「没被写入」在本领域模型上**恒真**，
     * 分辨不出「拒绝后原样保留」与「被静默降级成余额」。用哨兵值才验得出「零改写」。
     */
    private static final String SENTINEL_CHANNEL = "__NONE__";

    /**
     * 本单金额（分）。**必须显著大于任何种子优惠券的面额**。
     *
     * <p>🔴 踩过的坑：`UnpaidOrderService.markPaid` 在扣款前会调 {@code applyBestCouponForCollect}
     * 自动择优抵扣，而 {@code OrderPaymentService.chargeOrder} 开头有一条
     * 「{@code totalAmountCents <= 0} ⇒ 直接按余额并 return」的早退 —— 一旦本单被券**全额抵扣到 0**，
     * 整条渠道判定会被跳过，于是「显式选未开通渠道」不再 412、而是**成功 200**（实测响应体
     * `totalAmountCents:0, couponDiscountCents:100, status:PAID`）。
     * 种子给 10001 播了 `DEMO2OFF001`「新人立减 ¥2」= 200 分（`V99__member_marketing_consumer.sql`），
     * 故 100 分的订单必被抵成 0。取 2000 分留 10 倍余量，且种子余额 10000 分足以支付。
     */
    private static final int ORDER_CENTS = 2000;

    /** 读账户视图（余额 / 用户 id / 免密开通状态）。 */
    private JsonNode account(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v2/account")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    /**
     * 直插一个**终态**会话行，只用于满足 {@code cabinet_order.session_id} 的外键。
     *
     * <p>刻意**不走** {@code POST /api/v2/sessions}：那条路径会占用 {@code session:open:{deviceId}}
     * 分布式锁（{@code SessionService.runWithDeviceOpenLock}），本类多开几次会让**同类既有用例**
     * 也拿不到锁而 409 —— 实测正是如此（`consumer_fullPurchaseFlow` 与两条新用例同时 409）。
     * 终态会话不参与任何「进行中会话」判定，故既不占锁也不影响他人。
     *
     * <p>🔴 {@code createdAt} 刻意**拨回 2 小时前**：{@code RiskControlService.validateCanOpenDoor}
     * 以 `countByUserIdAndCreatedAtAfter(userId, now-1h)` 计算「近一小时开门次数」，而
     * `aicabinet.risk.enabled` 默认 **true**、`max-opens-per-hour` 默认 **5**。若让直插会话带上
     * 「刚刚创建」的时间戳，本类每跑一轮就白吃 2 次配额（两个用例各一行），
     * 会逐步把同用户的开门用例挤成 429。终态会话本就不代表一次真实开门 ⇒ 时间戳回填才符合语义。
     */
    private String insertCompletedSession(long userId) {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("E2EF6S" + UUID.randomUUID().toString().replace("-", "").substring(0, 21));
        session.setUserId(userId);
        session.setDeviceId("CAB-001");
        session.setState(SessionState.COMPLETED);
        session.setIdempotencyKey("e2e-f6-session-" + UUID.randomUUID());
        // 直插时显式赋值即可：MybatisMetaObjectHandler.insertFill 走 strictInsertFill，
        // 只在字段为 null 时填充，不会覆盖这里给的值。
        session.setCreatedAt(Instant.now().minus(2, ChronoUnit.HOURS));
        shoppingSessionMapper.insert(session);
        return session.getSessionId();
    }

    /**
     * 直插一张**从未扣过款**的 PENDING 单（会话行是 {@code cabinet_order.session_id} 的外键依赖，
     * 故须先 {@link #insertCompletedSession(long)}）。
     *
     * <p>为什么不复用结算流程产出的订单：{@code OrderPaymentService.restoreCompletedCharge} 依据
     * payment_operation 行（幂等键 {@code CHARGE:{orderId}:{amount}}）早退 —— 已结算过的订单再补缴会
     * **直接返回成功、根本不进入渠道判定**，拿它做判据必然**假绿**。
     *
     * <p>插完即登记到 {@link #f6InsertedOrderIds}，由 {@link #cleanupNeverChargedFixtureOrders()}
     * 在用例结束后清理（否则未支付的单会让同用户后续开门被判 412）。
     *
     * @param sentinelChannel 诱饵渠道值，见 {@link #SENTINEL_CHANNEL}
     */
    private String insertNeverChargedPendingOrder(String sessionId, long userId, int amountCents,
                                                 String sentinelChannel) {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId("E2EF6" + UUID.randomUUID().toString().replace("-", "").substring(0, 22));
        order.setSessionId(sessionId);
        order.setUserId(userId);
        order.setDeviceId("CAB-001");
        order.setTotalAmountCents(amountCents);
        order.setStatus("PENDING");
        order.setPayChannel(sentinelChannel);
        cabinetOrderMapper.insert(order);
        f6InsertedOrderIds.add(order.getOrderId());
        return order.getOrderId();
    }

    private String consumerToken() throws Exception {
        if (consumerToken == null) {
            MvcResult loginResult = mockMvc.perform(post("/api/v2/auth/wx-login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("code", "mock-e2e-code"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andReturn();
            consumerToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                    .path("data").path("token").asText();
        }
        return consumerToken;
    }
}
