package com.aicabinet.trade.payment;

import com.aicabinet.trade.config.ProfitSharingProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * P2-7：分账重试必须逐行隔离。旧实现一条坏行抛异常会中断整批（调度器外层事务还会回滚），
 * 后面的失败分账永远轮不到。
 */
@ExtendWith(MockitoExtension.class)
class WeChatProfitSharingRetryIsolationTest {

    @Mock private WeChatPayV3Client client;
    @Mock private OrderRevenueSplitMapper splitRepository;
    @Mock private WeChatProfitSharingService self;

    @Test
    void oneBadRow_doesNotBlockFollowingRows() {
        ProfitSharingProperties sharing = new ProfitSharingProperties(true, false, true, 20);
        WeChatPayProperties pay = new WeChatPayProperties(
                true, "wx-appid", "1900000109", "https://example.test/notify",
                "0123456789abcdef0123456789abcdef", "SERIAL-1", "PRIVATE-PEM", null, false);
        WeChatProfitSharingService service =
                new WeChatProfitSharingService(sharing, pay, client, splitRepository, null, self);

        OrderRevenueSplit bad = split("S-BAD");
        Merchant merchant = new Merchant();
        merchant.setMerchantId("M-1");
        merchant.setWechatReceiverId("1900000109");

        doThrow(new IllegalStateException("merchant has no wechatReceiverId"))
                .when(self).submitSplit(any(), any(), anyString());

        assertEquals(0, service.retryFailedSplits(List.of(bad), Map.of("M-1", merchant)),
                "坏行只跳过自己，不得让整批失败");
        verify(self).submitSplit(bad, merchant, "WX-TXN-S-BAD");
    }

    private static OrderRevenueSplit split(String id) {
        OrderRevenueSplit s = new OrderRevenueSplit();
        s.setSplitId(id);
        s.setOrderId("O-" + id);
        s.setMerchantId("M-1");
        s.setStatus("WECHAT_FAILED");
        s.setWechatTransactionId("WX-TXN-" + id);
        s.setMerchantCents(100);
        s.setPlatformCents(10);
        return s;
    }
}
