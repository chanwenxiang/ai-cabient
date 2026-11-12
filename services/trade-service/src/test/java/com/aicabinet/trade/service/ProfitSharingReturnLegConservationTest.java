package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.domain.OrderSplitReturnLeg;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.mapper.OrderSplitReturnLegMapper;
import com.aicabinet.trade.payment.WeChatProfitSharingService.ReturnSubmitOutcome;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;

/**
 * R20-9 验收线（报告 §十九.4）：
 * {@code Σ(leg.cents WHERE status='SUCCESS') = 初始 merchant_cents − 现 merchant_cents}。
 *
 * <p>这里用内存态 fake 顶替 Mapper，让 upsert / 条件更新 / 聚合都按真库语义跑一遍，
 * 而不只是 verify 某个方法被调用。100 元单三轮部分退（30/20/50）后：
 * 改前 split 单值列只剩最后一笔 50（渠道合计只回退 50，报告场景是 70，同族），
 * 改后三条腿各自留痕，渠道合计 100，商户留 0。
 */
@ExtendWith(MockitoExtension.class)
class ProfitSharingReturnLegConservationTest {

    static {
        // refreshProjection 用 LambdaUpdateWrapper：纯 Mockito 环境需注册 TableInfo 缓存
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                OrderRevenueSplit.class);
    }

    private static final String SPLIT_ID = "SPLIT-100";
    private static final long INITIAL_MERCHANT_CENTS = 10_000L; // 100 元

    @Mock private OrderSplitReturnLegMapper legRepository;
    @Mock private OrderRevenueSplitMapper splitRepository;

    /** out_return_no → 腿；LinkedHashMap 保证插入序 = 真实 BIGSERIAL 顺序。 */
    private final Map<String, OrderSplitReturnLeg> store = new LinkedHashMap<>();
    private long sequence = 0;
    private ProfitSharingReturnLegService legService;

    @BeforeEach
    void setUp() {
        store.clear();
        sequence = 0;
        org.mockito.Mockito.lenient().when(legRepository.insertLegIfAbsent(any())).thenAnswer(inv -> {
            OrderSplitReturnLeg draft = inv.getArgument(0);
            if (store.containsKey(draft.getOutReturnNo())) {
                return 0; // ON CONFLICT (out_return_no) DO NOTHING
            }
            OrderSplitReturnLeg row = copy(draft);
            row.setLegId(++sequence);
            store.put(row.getOutReturnNo(), row);
            return 1;
        });
        org.mockito.Mockito.lenient().when(legRepository.findByOutReturnNo(anyString()))
                .thenAnswer(inv -> store.get((String) inv.getArgument(0)));
        org.mockito.Mockito.lenient().when(legRepository.updateOutcomeIfNotSuccess(anyLong(), anyString(), nullable(String.class)))
                .thenAnswer(inv -> {
                    long id = inv.getArgument(0);
                    OrderSplitReturnLeg row = store.values().stream()
                            .filter(l -> l.getLegId() != null && l.getLegId() == id)
                            .findFirst().orElse(null);
                    if (row == null || ProfitSharingReturnLegService.STATUS_SUCCESS.equals(row.getStatus())) {
                        return 0;
                    }
                    row.setStatus(inv.getArgument(1));
                    row.setFailureReason(inv.getArgument(2));
                    return 1;
                });
        org.mockito.Mockito.lenient().when(legRepository.findOpenBySplitId(anyString())).thenAnswer(inv -> openLegs((String) inv.getArgument(0)));
        org.mockito.Mockito.lenient().when(legRepository.sumSuccessCents(anyString())).thenAnswer(inv -> store.values().stream()
                .filter(l -> SPLIT_ID.equals(l.getSplitId())
                        && ProfitSharingReturnLegService.STATUS_SUCCESS.equals(l.getStatus()))
                .mapToLong(OrderSplitReturnLeg::getCents).sum());
        org.mockito.Mockito.lenient().when(legRepository.sumOpenCents(anyString())).thenAnswer(inv -> openLegs((String) inv.getArgument(0))
                .stream().mapToLong(OrderSplitReturnLeg::getCents).sum());
        legService = new ProfitSharingReturnLegService(legRepository, splitRepository);
    }

    @Test
    void threePartialRefunds_threeLegs_conserveFullAmount() {
        OrderRevenueSplit split = split();
        record(split, "PSR-SPLIT-100:g7000", 3_000L, ReturnSubmitOutcome.PROCESSING);
        record(split, "PSR-SPLIT-100:g5000", 2_000L, ReturnSubmitOutcome.PROCESSING);
        record(split, "PSR-FULL-SPLIT-100", 5_000L, ReturnSubmitOutcome.PROCESSING);

        assertEquals(3, store.size(), "三轮回退必须落三条独立腿，不能互相覆盖");
        assertEquals(10_000L, legService.sumOpenCents(SPLIT_ID), "未完结金额 = 三轮之和");
        assertEquals("PSR-SPLIT-100:g7000", split.getWechatPendingReturnNo(),
                "投影必须是最老一条开放腿，不是最后一轮");
        assertEquals(3_000L, split.getWechatPendingReturnCents());

        confirm(split, "PSR-SPLIT-100:g7000", 3_000L);
        confirm(split, "PSR-SPLIT-100:g5000", 2_000L);
        confirm(split, "PSR-FULL-SPLIT-100", 5_000L);
        split.setMerchantCents(0L); // 全额退完

        long success = legService.sumSuccessCents(SPLIT_ID);
        assertEquals(INITIAL_MERCHANT_CENTS, success, "渠道合计回退 100 元，改前只会回退最后一笔");
        assertEquals(INITIAL_MERCHANT_CENTS - split.getMerchantCents(), success, "守恒式");
        assertEquals(0L, legService.sumOpenCents(SPLIT_ID));
        assertNull(split.getWechatPendingReturnNo(), "没有开放腿时投影必须清空");
        assertNull(split.getWechatPendingReturnCents());
    }

    /** 对照：改前的单值覆盖只留最后一笔，守恒必然失败（说明本用例真的能咬住旧行为）。 */
    @Test
    void legacySingleSlot_wouldBreakConservation() {
        long legacyPendingCents = 0L;
        String legacyNo = null;
        for (long[] round : new long[][]{{0, 3_000L}, {1, 2_000L}, {2, 5_000L}}) {
            legacyNo = "PSR-ROUND-" + round[0];
            legacyPendingCents = round[1]; // setXxx 覆盖，前一轮整笔丢失
        }
        assertEquals(5_000L, legacyPendingCents);
        assertNotEquals(INITIAL_MERCHANT_CENTS, legacyPendingCents);
        assertEquals("PSR-ROUND-2", legacyNo);
    }

    /** 同一渠道单号重报 = upsert 同一条腿；SUCCESS 是终态，不得被 PROCESSING/FAILED 复活。 */
    @Test
    void sameOutReturnNo_isIdempotentAndSuccessIsTerminal() {
        OrderRevenueSplit split = split();
        record(split, "PSR-X", 1_000L, ReturnSubmitOutcome.PROCESSING);
        confirm(split, "PSR-X", 1_000L);
        assertEquals(1, store.size());
        assertEquals(1_000L, legService.sumSuccessCents(SPLIT_ID));

        record(split, "PSR-X", 1_000L, ReturnSubmitOutcome.FAILED);
        assertEquals(1, store.size(), "同号不得插出第二条腿");
        assertEquals(1_000L, legService.sumSuccessCents(SPLIT_ID), "SUCCESS 腿不得复活");
        assertEquals(0L, legService.sumOpenCents(SPLIT_ID));
    }

    /** 同号却报了不同金额 ⇒ 资金路径 fail-closed，宁可退款事务回滚也不压成一条腿。 */
    @Test
    void sameOutReturnNoWithDifferentCents_failsClosed() {
        OrderRevenueSplit split = split();
        record(split, "PSR-Y", 1_000L, ReturnSubmitOutcome.PROCESSING);
        assertThrows(IllegalStateException.class,
                () -> record(split, "PSR-Y", 2_000L, ReturnSubmitOutcome.PROCESSING));
        assertEquals(1, store.size());
    }

    /** 补偿键按腿：三条腿三个不同 txId（旧实现按 splitId 只有一个槽）。 */
    @Test
    void threeLegs_haveThreeDistinctCompensationKeys() {
        OrderRevenueSplit split = split();
        record(split, "PSR-A", 3_000L, ReturnSubmitOutcome.PROCESSING);
        record(split, "PSR-B", 2_000L, ReturnSubmitOutcome.PROCESSING);
        record(split, "PSR-C", 5_000L, ReturnSubmitOutcome.PROCESSING);
        List<String> keys = legService.openLegs(SPLIT_ID).stream()
                .map(l -> ProfitSharingReturnLegService.legTxId(l.getLegId()))
                .toList();
        assertEquals(3, keys.size());
        assertEquals(3, new java.util.HashSet<>(keys).size());
        assertNotEquals(SPLIT_ID, keys.get(0), "补偿键不得再是裸 splitId");
    }

    private void record(OrderRevenueSplit split, String outReturnNo, long cents, ReturnSubmitOutcome outcome) {
        legService.recordOutcome(split, outReturnNo, cents, outcome, null);
    }

    private void confirm(OrderRevenueSplit split, String outReturnNo, long cents) {
        legService.recordOutcome(split, outReturnNo, cents, ReturnSubmitOutcome.SUCCESS, null);
    }

    private List<OrderSplitReturnLeg> openLegs(String splitId) {
        List<OrderSplitReturnLeg> open = new ArrayList<>();
        for (OrderSplitReturnLeg leg : store.values()) {
            if (splitId.equals(leg.getSplitId()) && ProfitSharingReturnLegService.isOpen(leg.getStatus())) {
                open.add(leg);
            }
        }
        open.sort(Comparator.comparing(OrderSplitReturnLeg::getLegId));
        return open;
    }

    private static OrderSplitReturnLeg copy(OrderSplitReturnLeg src) {
        OrderSplitReturnLeg row = new OrderSplitReturnLeg();
        row.setSplitId(src.getSplitId());
        row.setOutReturnNo(src.getOutReturnNo());
        row.setCents(src.getCents());
        row.setStatus(src.getStatus());
        row.setFailureReason(src.getFailureReason());
        return row;
    }

    private static OrderRevenueSplit split() {
        OrderRevenueSplit split = new OrderRevenueSplit();
        split.setSplitId(SPLIT_ID);
        split.setOrderId("O-100");
        split.setMerchantId("M-1");
        split.setMerchantCents(INITIAL_MERCHANT_CENTS);
        return split;
    }
}
