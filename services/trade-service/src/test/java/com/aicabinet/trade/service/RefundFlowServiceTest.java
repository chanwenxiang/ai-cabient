package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.RefundFlow;
import com.aicabinet.trade.mapper.RefundFlowMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B3（V485）：退款流水幂等钉——refundNo 唯一防重 / 无单号 24h 宽松防重 / 越界参数拒记。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RefundFlowServiceTest {

    @Mock
    private RefundFlowMapper mapper;

    private RefundFlowService service;

    private CabinetOrder order;

    @BeforeEach
    void setUp() {
        // lambda 缓存：纯单测需注册 MyBatis-Plus 列缓存（铁律 28 家族：单独跑必红的顺序依赖）
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                RefundFlow.class);
        service = new RefundFlowService(mapper);
        order = new CabinetOrder();
        order.setOrderId("O1");
        order.setMerchantId("M1");
        order.setPayChannel("WECHAT");
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.insert(any(RefundFlow.class))).thenAnswer(inv -> {
            inv.getArgument(0, RefundFlow.class).setFlowId(99L);
            return 1;
        });
    }

    @Test
    void record_writesCopiedOrderFields_andTruncatesReason() {
        Long id = service.record(order, 500, RefundFlowService.METHOD_CHANNEL_REFUND,
                "RF-1", Instant.now(), "x".repeat(600));
        assertEquals(99L, id);
        org.mockito.Mockito.verify(mapper).insert(org.mockito.ArgumentMatchers.argThat((RefundFlow f) -> {
            assertEquals("O1", f.getOrderId());
            assertEquals("M1", f.getMerchantId());
            assertEquals("WECHAT", f.getOrderPayChannel());
            assertEquals(500, f.getAmountCents());
            assertEquals("RF-1", f.getRefundNo());
            assertNotNull(f.getRefundedAt());
            org.junit.jupiter.api.Assertions.assertTrue(f.getReason().length() <= 500,
                    "reason 必须截断到 500 以内（含截断标记），实际 " + f.getReason().length());
            org.junit.jupiter.api.Assertions.assertTrue(f.getReason().endsWith("...[截断]"));
            return true;
        }));
    }

    @Test
    void sameRefundNo_isIdempotentSkip() {
        when(mapper.selectCount(any())).thenReturn(1L);
        Long id = service.record(order, 500, RefundFlowService.METHOD_CHANNEL_REFUND,
                "RF-DUP", Instant.now(), "r");
        assertNull(id, "同 refundNo 已存在 ⇒ 幂等跳过（渠道重试不双记）");
        verify(mapper, never()).insert(any(RefundFlow.class));
    }

    @Test
    void recentDuplicateWithoutRefundNo_isSkipped() {
        when(mapper.selectCount(any())).thenReturn(1L);
        Long id = service.record(order, 300, RefundFlowService.METHOD_BALANCE,
                null, Instant.now(), "r");
        assertNull(id, "无单号 24h 内同 (order,amount,method) 视为重放");
    }

    @Test
    void nonPositiveAmount_isRejected() {
        assertNull(service.record(order, 0, RefundFlowService.METHOD_BALANCE, null, Instant.now(), "r"));
        assertNull(service.record(order, -1, RefundFlowService.METHOD_BALANCE, null, Instant.now(), "r"));
        assertNull(service.record(null, 100, RefundFlowService.METHOD_BALANCE, null, Instant.now(), "r"));
        verify(mapper, never()).insert(any(RefundFlow.class));
    }
}
