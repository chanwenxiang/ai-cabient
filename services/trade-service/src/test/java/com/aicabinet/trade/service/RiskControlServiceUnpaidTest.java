package com.aicabinet.trade.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aicabinet.trade.domain.RiskEvent;
import com.aicabinet.trade.mapper.RiskEventMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * V324：欠款 → 风控事件连线。
 *
 * <p>🔴 这个类的核心价值不是「事件写进去了」，而是
 * <b>「记风控失败绝不能把欠款升级成结算失败」</b>—— 它跑在结算主流程里。
 */
@ExtendWith(MockitoExtension.class)
class RiskControlServiceUnpaidTest {

    @Mock private RiskEventMapper riskEventRepository;

    private RiskControlService service() {
        // ⚠️ 参数序**按真实构造器**（RiskControlProperties, UserBlacklistMapper,
        //    RiskEventMapper, ShoppingSessionMapper, ObjectMapper, DistributedLockService）
        //    —— 本测试只调 onUnpaidOrderCreated，其余依赖用不到可传 null。
        return new RiskControlService(
                // RiskControlProperties 是 record(enabled, maxOpensPerHour, maxDisputesPer7Days)
                new com.aicabinet.trade.config.RiskControlProperties(true, 5, 3),
                null,          // UserBlacklistMapper
                riskEventRepository,
                null,          // ShoppingSessionMapper
                new ObjectMapper(),
                null           // DistributedLockService
        );
    }

    @Test
    @DisplayName("欠款 ⇒ 写 UNPAID_ORDER 事件（severity=WARN）")
    void unpaidOrder_isRecorded() {
        service().onUnpaidOrderCreated(1001L, "DEV-1", "ORD-1", "PRE_CHARGE", 1334L);

        ArgumentCaptor<RiskEvent> captor = ArgumentCaptor.forClass(RiskEvent.class);
        verify(riskEventRepository).save(captor.capture());
        RiskEvent e = captor.getValue();
        assertEquals("UNPAID_ORDER", e.getEventType());
        assertEquals("WARN", e.getSeverity());
        assertEquals(1001L, e.getUserId());
        assertEquals("DEV-1", e.getDeviceId());
    }

    @Test
    @DisplayName("🔴 三个阶段各自标记（PRE_CHARGE / CHARGE_FAILED / CHARGE_UNCONFIRMED）")
    void stages_areDistinguishable() {
        for (String stage : new String[]{"PRE_CHARGE", "CHARGE_FAILED", "CHARGE_UNCONFIRMED"}) {
            reset(riskEventRepository);
            service().onUnpaidOrderCreated(1001L, "DEV-1", "ORD-" + stage, stage, 100L);
            ArgumentCaptor<RiskEvent> captor = ArgumentCaptor.forClass(RiskEvent.class);
            verify(riskEventRepository).save(captor.capture());
            assertTrue(captor.getValue().getDetail().contains(stage),
                    "detail 应含阶段 " + stage + "，实际 " + captor.getValue().getDetail());
        }
    }

    @Test
    @DisplayName("🔴 记风控抛异常时**不得冒泡**（否则欠款会升级成结算失败）")
    void recordFailure_neverPropagates() {
        doThrow(new RuntimeException("db down")).when(riskEventRepository).save(any());

        // 🔴 核心断言：不抛 =欠款流程不被风控记录失败拖垮
        assertDoesNotThrow(() ->
                service().onUnpaidOrderCreated(1001L, "DEV-1", "ORD-1", "PRE_CHARGE", 1334L));
    }

    @Test
    @DisplayName("userId 为 null ⇒ 不写（无主订单不该产生风控事件）")
    void nullUserId_isSkipped() {
        service().onUnpaidOrderCreated(null, "DEV-1", "ORD-1", "PRE_CHARGE", 1334L);
        verify(riskEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("🔴 金额按元记（给人看的详情不该是 1334 分）")
    void amount_isRecordedInYuan() {
        service().onUnpaidOrderCreated(1001L, "DEV-1", "ORD-1", "PRE_CHARGE", 1334L);

        ArgumentCaptor<RiskEvent> captor = ArgumentCaptor.forClass(RiskEvent.class);
        verify(riskEventRepository).save(captor.capture());
        String detail = captor.getValue().getDetail();
        assertTrue(detail.contains("13.34"), "应记 13.34 元，实际 " + detail);
    }

    @Test
    @DisplayName("orderId/stage 为 null 也不炸（Map.of 不接受 null）")
    void nullFields_areSafe() {
        // 🔴 Map.of(null,...) 会 NPE —— 这是本方法最容易被忽略的边界
        assertDoesNotThrow(() ->
                service().onUnpaidOrderCreated(1001L, "DEV-1", null, null, 100L));
    }

    private void reset(Object mock) {
        org.mockito.Mockito.reset(mock);
    }

    /** 避免未使用导入告警。 */
    @SuppressWarnings("unused")
    private static final Map<String, Object> UNUSED_HINT = Map.of();
}
