package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.ShoppingSession;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 会话类型归类（sessionKind）钉行为：STOCKTAKE 分支 2026-10-30 缺失导致盘点开门
 * 在会话列表显示成「消费」（用户截图实锤）。结算侧 isNonConsumerSession 一直正确，
 * 本测试钉住「展示口径与结算口径同源」，防再次漂移（铁律 16）。
 */
class DeviceValidationServiceSessionKindTest {

    private static ShoppingSession sessionWithKey(String idempotencyKey) {
        ShoppingSession s = new ShoppingSession();
        s.setSessionId("17933440664381611303");
        s.setIdempotencyKey(idempotencyKey);
        return s;
    }

    @Test
    void stocktakeSession_isKindStocktake_notConsumer() {
        assertEquals("STOCKTAKE",
                DeviceValidationService.sessionKind(sessionWithKey("STOCKTAKE:100000006:17933440664381611303")));
    }

    @Test
    void stocktakeSession_isNonConsumer_soNoSettlement() {
        assertEquals(true,
                DeviceValidationService.isNonConsumerSession(sessionWithKey("STOCKTAKE:1:2")));
    }

    @Test
    void restockAndOpsAndConsumer_kindsUnchanged() {
        assertEquals("RESTOCK",
                DeviceValidationService.sessionKind(sessionWithKey("RESTOCK:9:17933440664381611303")));
        assertEquals("OPS",
                DeviceValidationService.sessionKind(sessionWithKey("OPS_REMOTE:1:17933440664381611303")));
        assertEquals("CONSUMER", DeviceValidationService.sessionKind(sessionWithKey(null)));
        assertEquals("CONSUMER", DeviceValidationService.sessionKind(sessionWithKey("PAY-INTENT-xyz")));
    }
}
