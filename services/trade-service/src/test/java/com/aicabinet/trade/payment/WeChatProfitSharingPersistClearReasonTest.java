package com.aicabinet.trade.payment;

import com.aicabinet.trade.config.ProfitSharingProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 审计 R11-B-N1（M01 清列家族第 7 处）：persistSplit 是 refreshSplitStatus 的唯一落盘点。
 * MyBatis-Plus 全仓无 FieldStrategy 覆盖 ⇒ save/updateById 默认 NOT_NULL 策略跳 null ——
 * applyRemoteState 把 FAILED 单刷新成 FINISHED/PROCESSING 时 setFailureReason(null)
 * 不会真清列，旧失败文案残留库中与 WECHAT_SUBMITTED 自相矛盾。必须补 wrapper 真清列。
 */
@ExtendWith(MockitoExtension.class)
class WeChatProfitSharingPersistClearReasonTest {
    static {
        // persistSplit 的清列走 LambdaUpdateWrapper：纯 Mockito 环境需注册 TableInfo
        // （照 WeChatProfitSharingServiceZeroRateTest 同款）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                OrderRevenueSplit.class);
    }

    @Mock private WeChatPayV3Client client;
    @Mock private OrderRevenueSplitMapper splitRepository;
    @Mock private com.aicabinet.trade.service.SystemConfigService systemConfig;

    private WeChatProfitSharingService service;

    @BeforeEach
    void setUp() {
        ProfitSharingProperties sharing = new ProfitSharingProperties(true, false, true, 20);
        WeChatPayProperties pay = new WeChatPayProperties(
                true, "wx-appid", "1900000109", "https://example.test/notify",
                "0123456789abcdef0123456789abcdef", "SERIAL-1", "PRIVATE-PEM", null, false);
        service = new WeChatProfitSharingService(sharing, pay, client, splitRepository, null,
                new ProfitSharingCapPolicy(systemConfig), null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    void persistWithNullFailureReason_clearsStaleColumn() {
        OrderRevenueSplit s = new OrderRevenueSplit();
        s.setSplitId("S-1");
        s.setOrderId("O-1");
        s.setMerchantId("M-1");
        s.setStatus("WECHAT_SUBMITTED");
        s.setFailureReason(null);
        OrderRevenueSplit saved = new OrderRevenueSplit();
        saved.setSplitId("S-1");
        saved.setOrderId("O-1");
        saved.setStatus("WECHAT_SUBMITTED");
        when(splitRepository.findByOrderIdForUpdate("O-1")).thenReturn(Optional.of(s), Optional.of(saved));
        when(splitRepository.update(isNull(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(1);

        assertSame(saved, service.persistRemoteState("O-1", "FINISHED", null));

        // 条件 wrapper 必须显式 set(null)，不能靠 save/updateById 清列。
        verify(splitRepository).update(isNull(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        verify(splitRepository, never()).save(any(OrderRevenueSplit.class));
    }

    @Test
    void persistWithNonNullReason_writesReasonInConditionalUpdate() {
        OrderRevenueSplit s = new OrderRevenueSplit();
        s.setSplitId("S-2");
        s.setOrderId("O-2");
        s.setMerchantId("M-1");
        s.setStatus("WECHAT_FAILED");
        s.setFailureReason("insufficient balance");
        OrderRevenueSplit saved = new OrderRevenueSplit();
        saved.setSplitId("S-2");
        saved.setOrderId("O-2");
        saved.setStatus("WECHAT_FAILED");
        saved.setFailureReason("insufficient balance");
        when(splitRepository.findByOrderIdForUpdate("O-2")).thenReturn(Optional.of(s), Optional.of(saved));
        when(splitRepository.update(isNull(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(1);

        assertSame(saved, service.persistRemoteState("O-2", "CLOSED", "insufficient balance"));

        verify(splitRepository).update(isNull(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        verify(splitRepository, never()).save(any(OrderRevenueSplit.class));
    }

    @Test
    void persist_terminalCurrentRow_blocksStaleEntityResurrection() {
        for (String terminal : new String[]{"VOIDED", "REVERSED"}) {
            OrderRevenueSplit stale = new OrderRevenueSplit();
            stale.setSplitId("S-TERMINAL");
            stale.setOrderId("O-TERMINAL");
            stale.setMerchantCents(100L);
            stale.setStatus("WECHAT_SUBMITTED");
            stale.setFailureReason(null);
            OrderRevenueSplit current = new OrderRevenueSplit();
            current.setSplitId("S-TERMINAL");
            current.setOrderId("O-TERMINAL");
            current.setMerchantCents(100L);
            current.setStatus(terminal);
            when(splitRepository.findByOrderIdForUpdate("O-TERMINAL")).thenReturn(Optional.of(current));

            assertSame(current, service.persistRemoteState("O-TERMINAL", "FINISHED", null));

            assertEquals(terminal, current.getStatus());
            verify(splitRepository, never()).save(any(OrderRevenueSplit.class));
            verify(splitRepository, never()).update(any(), any());
        }
    }

    @Test
    void mockRefresh_alsoRereadsAndProtectsTerminalRow() {
        ProfitSharingProperties mockSharing = new ProfitSharingProperties(true, true, true, 20);
        WeChatPayProperties pay = new WeChatPayProperties(
                true, "wx-appid", "1900000109", "https://example.test/notify",
                "0123456789abcdef0123456789abcdef", "SERIAL-1", "PRIVATE-PEM", null, false);
        WeChatProfitSharingService mockService = new WeChatProfitSharingService(
                mockSharing, pay, client, splitRepository, null, new ProfitSharingCapPolicy(systemConfig), null);
        org.springframework.test.util.ReflectionTestUtils.setField(mockService, "self", mockService);
        OrderRevenueSplit stale = new OrderRevenueSplit();
        stale.setSplitId("S-MOCK");
        stale.setOrderId("O-MOCK");
        stale.setMerchantCents(100L);
        stale.setStatus("WECHAT_SUBMITTED");
        stale.setWechatTransactionId("TX-1");
        stale.setWechatOutOrderNo("OUT-1");
        OrderRevenueSplit current = new OrderRevenueSplit();
        current.setSplitId("S-MOCK");
        current.setOrderId("O-MOCK");
        current.setStatus("VOIDED");
        when(splitRepository.findByOrderIdForUpdate("O-MOCK")).thenReturn(Optional.of(current));

        assertSame(current, mockService.refreshSplitStatus(stale));

        assertEquals("VOIDED", current.getStatus());
        verify(splitRepository, never()).save(any(OrderRevenueSplit.class));
        verifyNoInteractions(client);
    }

    @Test
    void refresh_voidedSplit_neverCallsWechatOrResurrectsStatus() {
        OrderRevenueSplit split = new OrderRevenueSplit();
        split.setSplitId("S-VOID");
        split.setOrderId("O-VOID");
        split.setMerchantId("M-1");
        split.setMerchantCents(100L);
        split.setStatus("VOIDED");
        split.setWechatTransactionId("TX-1");
        split.setWechatOutOrderNo("OUT-1");

        OrderRevenueSplit refreshed = service.refreshSplitStatus(split);

        assertEquals("VOIDED", refreshed.getStatus());
        verify(client, never()).get(any(String.class));
    }
}
