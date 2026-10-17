package com.aicabinet.trade.service;

import com.aicabinet.trade.config.CheckoutProperties;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.PaymentOperation;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.payment.AlipayPayClient;
import com.aicabinet.trade.payment.WeChatPayClient;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;


/**
 * 审计 P0-4 不变量测试：{@code applyBalanceCharge} 无论冲抵比例如何，落账必须满足
 * <pre>netCompletedCents(orderId) == order.getTotalAmountCents()</pre>
 *
 * <p>三场景：混合冲抵（60/100）/ 全额冲抵（100/100）/ 无预授权（0/100）。
 * 修复前分别为 140 / 200 / 200（CHARGE:PREAUTH 键重复记账 + 终段全额 CHARGE 重复）。
 *
 * <p>机制：用内存 store 模拟 {@code payment_operation} 表；{@code captureForCharge} 的
 * stub 按真实语义**同步写入 PREAUTH_CAPTURE 行**（orderId 挂单，F1-A），{@code
 * balanceLedgerService.change} 的 stub 同步写入 CHARGE 行——两个真实副作用都在测内可见，
 * 不变量才测得住（此前 {@code CHARGE:PREAUTH} 路径零覆盖正是因 mock 吞掉了副作用）。
 * 净额一律经生产 {@code PaymentOperationMapper.netCompletedCents}（F-10 整改：测试不再手抄口径）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderPaymentChargeInvariantTest {

    static {
        // 真调生产 wrapper 需要列名解析 ⇒ 纯 Mockito 环境手动初始化 TableInfo 缓存
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                PaymentOperation.class);
    }

    @Mock UserInfoMapper userInfoRepository;
    @Mock BalanceLedgerService balanceLedgerService;
    @Mock PayScoreService payScoreService;
    @Mock WeChatPayClient weChatPayClient;
    @Mock AlipayPayClient alipayPayClient;
    @Mock PaymentOperationMapper paymentOperationRepository;
    @Mock CabinetOrderMapper cabinetOrderRepository;
    @Mock DistributedLockService distributedLockService;
    @Mock ShoppingSessionMapper sessionRepository;
    @Mock MemberService memberService;
    @Mock ConsumerPreauthService consumerPreauthService;

    /** 内存版 payment_operation 表：idempotencyKey → 行。 */
    private final Map<String, PaymentOperation> store = new HashMap<>();

    private OrderPaymentService service;

    private static final String SESSION_ID = "S-INV";
    private static final String ORDER_ID = "O-INV-1";
    private static final long USER_ID = 10001L;
    private static final int TOTAL = 100;

    @BeforeEach
    void setUp() {
        service = new OrderPaymentService(
                userInfoRepository,
                payScoreService,
                weChatPayClient,
                alipayPayClient,
                new WeChatPayProperties(false, "", "", "", "", "", "", "", true),
                new SecurityProperties(true),
                paymentOperationRepository,
                cabinetOrderRepository,
                distributedLockService,
                balanceLedgerService,
                new CheckoutProperties(true, 2000),
                sessionRepository,
                consumerPreauthService,
                null,
                null,
                null,
                null,
                memberService,
                null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);

        // 内存表：幂等键读写
        lenient().when(paymentOperationRepository.findByIdempotencyKey(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0, String.class))));
        lenient().when(paymentOperationRepository.save(any(PaymentOperation.class)))
                .thenAnswer(inv -> {
                    PaymentOperation op = inv.getArgument(0);
                    store.put(op.getIdempotencyKey(), op);
                    return op;
                });
        lenient().when(paymentOperationRepository.saveAndFlush(any(PaymentOperation.class)))
                .thenAnswer(inv -> {
                    PaymentOperation op = inv.getArgument(0);
                    store.put(op.getIdempotencyKey(), op);
                    return op;
                });
        // F-10 整改（2026-10-17）：净额改**真调生产 default 方法**（原手抄 net() 属「测试自建真相」，
        // 教训 lessons-learned 315——正因抄漏了 .in(...) 过滤，F-10 才没被这批用例测出）。
        // selectList 按生产 wrapper 声明的参数值真过滤 ⇒ SQL 少一个类型，这里就查不到对应行。
        lenient().when(paymentOperationRepository.selectList(any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("rawtypes")
                    AbstractWrapper w = inv.getArgument(0);
                    w.getSqlSegment();
                    var params = w.getParamNameValuePairs().values();
                    return store.values().stream()
                            .filter(op -> params.contains(op.getOrderId())
                                    && params.contains(op.getStatus())
                                    && params.contains(op.getOperationType()))
                            .toList();
                });
        lenient().when(paymentOperationRepository.findCompletedPaymentOpsByOrderId(anyString()))
                .thenCallRealMethod();
        lenient().when(paymentOperationRepository.netCompletedCents(anyString()))
                .thenCallRealMethod();
        lenient().when(paymentOperationRepository.chargedCompletedCents(anyString()))
                .thenCallRealMethod();

        // 锁与订单/会话/用户
        lenient().when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        lenient().when(userInfoRepository.findById(USER_ID)).thenReturn(Optional.of(new UserInfo()));
        lenient().when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session()));
        lenient().when(cabinetOrderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order()));

        // 余额扣款：与真实 doChange 同语义——写一行 CHARGE（amount=|delta|，键=idemKey）并返回
        lenient().when(balanceLedgerService.change(anyLong(), anyInt(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    long userId = inv.getArgument(0);
                    int delta = inv.getArgument(1);
                    String type = inv.getArgument(2);
                    String orderId = inv.getArgument(3);
                    String key = inv.getArgument(4);
                    PaymentOperation op = new PaymentOperation();
                    op.setOperationId("OP-" + key);
                    op.setOrderId(orderId);
                    op.setOperationType(type);
                    op.setAmountCents(Math.abs(delta));
                    op.setChannel("BALANCE");
                    op.setStatus("COMPLETED");
                    op.setIdempotencyKey(key);
                    op.setUserId(userId);
                    store.put(key, op);
                    return op;
                });
    }

    /** captureForCharge stub：按真实语义同步写 PREAUTH_CAPTURE 行（orderId 挂单，F1-A）。 */
    private void stubCapture(int capturedCents) {
        lenient().when(consumerPreauthService.captureForCharge(any(), anyInt(), anyString()))
                .thenAnswer(inv -> {
                    int amount = inv.getArgument(1);
                    int cap = Math.min(capturedCents, amount);
                    if (cap > 0) {
                        String key = "PREAUTH_CAPTURE:" + SESSION_ID + ":" + cap;
                        PaymentOperation op = new PaymentOperation();
                        op.setOperationId("OP-" + key);
                        op.setOrderId(ORDER_ID);
                        op.setOperationType("PREAUTH_CAPTURE");
                        op.setAmountCents(cap);
                        op.setChannel("BALANCE");
                        op.setStatus("COMPLETED");
                        op.setIdempotencyKey(key);
                        op.setUserId(USER_ID);
                        store.put(key, op);
                    }
                    return new ConsumerPreauthService.CaptureChargeResult(cap, amount - cap, false);
                });
    }

    /** 净额走生产查询原语（SQL 过滤 + switch 口径都由 PaymentOperationMapper 决定）。 */
    private int netProduced() {
        return paymentOperationRepository.netCompletedCents(ORDER_ID);
    }

    private ShoppingSession session() {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId(SESSION_ID);
        session.setUserId(USER_ID);
        session.setPreauthStatus("FROZEN");
        session.setPreauthCents(2000);
        return session;
    }

    private CabinetOrder order() {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId(ORDER_ID);
        order.setUserId(USER_ID);
        order.setTotalAmountCents(TOTAL);
        order.setPayChannel("BALANCE");
        order.setSessionId(SESSION_ID);
        return order;
    }

    @Test
    void mixedCapture_netEqualsTotal() {
        stubCapture(60); // 冻结 60 可冲抵，余额补 40

        service.chargeOrder(order());

        assertEquals(TOTAL, netProduced(),
                "混合冲抵：PREAUTH_CAPTURE 60 + CHARGE 40 必须恰为应付 100（修前 140）");
    }

    @Test
    void fullCapture_netEqualsTotal() {
        stubCapture(100); // 冻结充足，全额冲抵

        service.chargeOrder(order());

        assertEquals(TOTAL, netProduced(),
                "全额冲抵：PREAUTH_CAPTURE 100 即实收（修前另记全额 CHARGE 凑成 200）");
    }

    @Test
    void noPreauth_netEqualsTotal() {
        stubCapture(0); // 会话无冻结：captureForCharge 返回 (0, 100, false)

        service.chargeOrder(order());

        assertEquals(TOTAL, netProduced(),
                "无预授权：余额 CHARGE 100 单行（修前 CHARGE:PREAUTH 与余额行双记凑成 200）");
    }

    /**
     * F-9（CB-057）：收款成功后冻结的毛额 = **收入流水之和**（CHARGE+ADJUST_CHARGE+PREAUTH_CAPTURE，
     * 与 V384 回填同定义），且之后的部分退款（摊薄 total + 记 REFUND 行）不得改写它。
     */
    @Test
    void freezePaidAmount_snapshotsIncomeAndSurvivesPartialRefund() {
        stubCapture(60); // 冲抵 60 + 余额 40
        CabinetOrder order = order();

        service.chargeOrder(order);
        service.freezePaidAmount(order);

        assertEquals(TOTAL, order.getPaidAmountCents(),
                "冻结毛额必须等于实收 100（60 冲抵 + 40 余额），与 netCompletedCents 同值");

        // 按行部分退（SettlementPartialRefundService 的写法）：total 被摊薄成 30、refunded 记 70
        order.setTotalAmountCents(30);
        order.setRefundedCents(70);
        store.put("RF-PARTIAL", refundRow(70));

        service.freezePaidAmount(order); // 争议结案等路径重放

        assertEquals(TOTAL, order.getPaidAmountCents(),
                "退款只追加 REFUND 行，冻结毛额只增不减（改小就会把退款扣两遍）");
        assertEquals(30, CommissionBaseCalc.netCents(order),
                "净额 = 冻结毛额 100 − 已退 70 = 30；用被摊薄的 total(30)−70 会算成 0");
    }

    private PaymentOperation refundRow(int cents) {
        PaymentOperation op = new PaymentOperation();
        op.setOperationId("OP-RF-PARTIAL");
        op.setOrderId(ORDER_ID);
        op.setOperationType("REFUND");
        op.setAmountCents(cents);
        op.setChannel("BALANCE");
        op.setStatus("COMPLETED");
        op.setIdempotencyKey("RF-PARTIAL");
        op.setUserId(USER_ID);
        return op;
    }
}
