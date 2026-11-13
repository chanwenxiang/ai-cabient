package com.aicabinet.trade.payment;

import com.aicabinet.trade.config.ProfitSharingProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.domain.OrderSplitReturnLeg;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.payment.WeChatProfitSharingService.ReturnSubmitOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P3-待定③（CB-053 官方口径「先查单、同号重试防重复退款」，拍板材料
 * docs/PENDING_DECISIONS_5_2026-11-13.md）：WECHAT_FAILED 回退重试必须**先查单**——
 * 微信侧已受理（PROCESSING/SUCCESS）不再重提，防同号重复回退；查单失败（真失败或
 * 查询通道异常）才走同号重提（outReturnNo 稳定存腿行，同号幂等兜底）。
 */
@ExtendWith(MockitoExtension.class)
class WeChatProfitSharingReturnRetryQueryFirstTest {

    @Mock private WeChatPayV3Client client;
    @Mock private OrderRevenueSplitMapper splitRepository;
    @Mock private WeChatProfitSharingService self;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private WeChatProfitSharingService service() {
        ProfitSharingProperties sharing = new ProfitSharingProperties(true, false, true, 20);
        WeChatPayProperties pay = new WeChatPayProperties(
                true, "wx-appid", "1900000109", "https://example.test/notify",
                "0123456789abcdef0123456789abcdef", "SERIAL-1", "PRIVATE-PEM", null, false);
        return new WeChatProfitSharingService(sharing, pay, client, splitRepository, null, self);
    }

    private OrderSplitReturnLeg failedLeg() {
        OrderSplitReturnLeg leg = new OrderSplitReturnLeg();
        leg.setLegId(1L);
        leg.setSplitId("S-1");
        leg.setOutReturnNo("RTN-S-1-1");
        leg.setCents(50);
        leg.setStatus("FAILED");
        return leg;
    }

    private OrderRevenueSplit split() {
        OrderRevenueSplit s = new OrderRevenueSplit();
        s.setSplitId("S-1");
        s.setOrderId("O-1");
        s.setMerchantId("M-1");
        s.setStatus("WECHAT_FAILED");
        s.setMerchantCents(100);
        return s;
    }

    private Map<String, Merchant> merchants() {
        Merchant merchant = new Merchant();
        merchant.setMerchantId("M-1");
        merchant.setWechatReceiverId("1900000109");
        return Map.of("M-1", merchant);
    }

    @Test
    void queryProcessing_skipsResubmit_andRecordsOutcome() throws Exception {
        when(client.get(contains("/v3/profitsharing/return-orders/")))
                .thenReturn(objectMapper.readTree("{\"result\":\"PROCESSING\"}"));
        OrderRevenueSplit split = split();
        OrderSplitReturnLeg leg = failedLeg();
        when(splitRepository.selectBatchIds(any())).thenReturn(List.of(split));

        int retried = service().retryFailedReturnLegs(List.of(leg), merchants());

        assertEquals(1, retried);
        verify(self, never()).returnMerchantShare(any(), any(), anyLong(), anyString(), anyString());
        verify(self).applyReturnOutcome(split, leg, ReturnSubmitOutcome.PROCESSING);
        verify(client, never()).post(anyString(), any());
    }

    @Test
    void querySuccess_skipsResubmit_andClosesLeg() throws Exception {
        when(client.get(contains("/v3/profitsharing/return-orders/")))
                .thenReturn(objectMapper.readTree("{\"result\":\"SUCCESS\"}"));
        OrderRevenueSplit split = split();
        OrderSplitReturnLeg leg = failedLeg();
        when(splitRepository.selectBatchIds(any())).thenReturn(List.of(split));

        int retried = service().retryFailedReturnLegs(List.of(leg), merchants());

        assertEquals(1, retried);
        verify(self, never()).returnMerchantShare(any(), any(), anyLong(), anyString(), anyString());
        verify(self).applyReturnOutcome(split, leg, ReturnSubmitOutcome.SUCCESS);
    }

    @Test
    void queryFailed_resubmitsSameNumber() throws Exception {
        when(client.get(contains("/v3/profitsharing/return-orders/")))
                .thenReturn(objectMapper.readTree("{\"result\":\"FAILED\"}"));
        when(self.returnMerchantShare(any(), any(), anyLong(), anyString(), anyString()))
                .thenReturn(ReturnSubmitOutcome.PROCESSING);
        OrderRevenueSplit split = split();
        OrderSplitReturnLeg leg = failedLeg();
        Merchant merchant = merchants().get("M-1");
        when(splitRepository.selectBatchIds(any())).thenReturn(List.of(split));

        int retried = service().retryFailedReturnLegs(List.of(leg), Map.of("M-1", merchant));

        assertEquals(1, retried);
        verify(self).returnMerchantShare(split, merchant, 50L, "RTN-S-1-1", "分账回退重试");
        verify(self).applyReturnOutcome(split, leg, ReturnSubmitOutcome.PROCESSING);
    }

    @Test
    void queryTransportError_stillResubmits() throws Exception {
        when(client.get(contains("/v3/profitsharing/return-orders/")))
                .thenThrow(new IllegalStateException("query channel down"));
        when(self.returnMerchantShare(any(), any(), anyLong(), anyString(), anyString()))
                .thenReturn(ReturnSubmitOutcome.PROCESSING);
        OrderRevenueSplit split = split();
        OrderSplitReturnLeg leg = failedLeg();
        when(splitRepository.selectBatchIds(any())).thenReturn(List.of(split));

        int retried = service().retryFailedReturnLegs(List.of(leg), merchants());

        assertEquals(1, retried, "查询通道异常不得阻断同号重提");
        verify(self).returnMerchantShare(any(), any(), anyLong(), anyString(), anyString());
    }
}
