package com.aicabinet.trade.payment;

import com.aicabinet.trade.config.ProfitSharingProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.domain.Merchant;
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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 提交分账的「平台不抽成」闸（拍板 2026-10-17：platform_rate_bps 先按 0 收）。
 *
 * <p>rate=0 ⇒ receiver.amount 会等于订单毛额。微信服务商**全额分账是否允许未实证**
 * （CB-032 记的「单订单分账比例默认上限 30%」只是 ⚪ 旁证），所以 0 抽成的单
 * 一律**不发**分账请求——否则运营在分账页对一条 LEDGER_ONLY/0 抽成的行点「提交分账」
 * 就会把整笔订单额打给商户接收方（商户份额已由钱包入账 ⇒ 双付）。
 */
@ExtendWith(MockitoExtension.class)
class WeChatProfitSharingServiceZeroRateTest {
    static {
        // prepareSubmit 用 LambdaUpdateWrapper 落 out_order_no：纯 Mockito 环境需注册 TableInfo
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                OrderRevenueSplit.class);
    }

    @Mock private WeChatPayV3Client client;
    @Mock private OrderRevenueSplitMapper splitRepository;

    private WeChatProfitSharingService service;

    @BeforeEach
    void setUp() {
        ProfitSharingProperties sharing = new ProfitSharingProperties(true, false, true, 20);
        WeChatPayProperties pay = new WeChatPayProperties(
                true, "wx-appid", "1900000109", "https://example.test/notify",
                "0123456789abcdef0123456789abcdef", "SERIAL-1", "PRIVATE-PEM", null, false);
        service = new WeChatProfitSharingService(sharing, pay, client, splitRepository, null);
    }

    private static Merchant merchant() {
        Merchant m = new Merchant();
        m.setMerchantId("M-1");
        m.setWechatReceiverId("1900000109");
        return m;
    }

    private static OrderRevenueSplit split(long platformCents, long merchantCents) {
        OrderRevenueSplit s = new OrderRevenueSplit();
        s.setSplitId("SPLIT-Z1");
        s.setOrderId("O-Z1");
        s.setMerchantId("M-1");
        s.setGrossCents(platformCents + merchantCents);
        s.setPlatformCents(platformCents);
        s.setMerchantCents(merchantCents);
        s.setStatus("ACCRUED");
        return s;
    }

    @Test
    void prepareSubmit_zeroPlatformCents_isTerminalWithoutBodyOrWrites() {
        OrderRevenueSplit zeroRate = split(0, 1000);

        WeChatProfitSharingService.PreparedSubmission prep =
                service.prepareSubmit(zeroRate, merchant(), "WX-TXN-1");

        assertTrue(prep.terminal());
        assertNull(prep.body());
        assertEquals("ACCRUED", zeroRate.getStatus());
        verifyNoInteractions(client);
        verify(splitRepository, never()).save(any());
        verify(splitRepository, never()).update(any(), any());
    }

    @Test
    void prepareSubmit_positivePlatformCents_stillBuildsSharingBody() {
        OrderRevenueSplit split = split(100, 900);

        WeChatProfitSharingService.PreparedSubmission prep =
                service.prepareSubmit(split, merchant(), "WX-TXN-2");

        assertFalse(prep.terminal());
        assertEquals("WX-TXN-2", prep.wxTransactionId());
        assertEquals(Boolean.TRUE, prep.body().get("unfreeze_unsplit"));
        assertEquals("WX-TXN-2", prep.body().get("transaction_id"));
        assertEquals(split.getSplitId(), prep.body().get("out_order_no"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> receivers = (List<Map<String, Object>>) prep.body().get("receivers");
        assertEquals(1, receivers.size());
        assertEquals(900L, receivers.get(0).get("amount"));
        assertEquals("1900000109", receivers.get(0).get("account"));
        // out_order_no 先落库再打微信（B2 幂等键）
        verify(splitRepository).update(any(), any());
        verifyNoInteractions(client);
    }

    @Test
    void prepareSubmit_zeroMerchantShare_stillFlipsToLedgerOnly() {
        // 既有短路：商户份额 0（全额平台抽成）本来就不发分账
        OrderRevenueSplit split = split(1000, 0);

        WeChatProfitSharingService.PreparedSubmission prep =
                service.prepareSubmit(split, merchant(), "WX-TXN-3");

        assertTrue(prep.terminal());
        assertEquals("LEDGER_ONLY", split.getStatus());
        verify(splitRepository).save(split);
    }
}
