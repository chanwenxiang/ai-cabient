package com.aicabinet.trade.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V314：在途损耗的算术护栏。
 *
 * <p>🔴本类<b>直接调 {@link TransferLossValidator}</b>（真实实现），不复制逻辑。
 * 早先的版本把同一套判断抄在测试里 —— 那种测试<b>改实现不会红</b>，是假测试。
 * 抽成独立类后才真正起到「实现变了这里就会红」的作用。
 *
 * <p>核心不变量：**损耗恒等于「发运量 − 实收量」，不让运营手填**。
 * 否则「实收 90、损耗 0」而发运 100 ⇒ 那 10 件凭空消失而系统不知情。
 */
class TransferLossArithmeticTest {

    @Test
    @DisplayName("全到齐：实收=发运、损耗 0 ⇒ 合法")
    void fullArrival() {
        TransferLossValidator.Result r = TransferLossValidator.validate(100, 100, 0, null);
        assertEquals(100, r.received());
        assertEquals(0, r.loss());
        assertNull(r.lossReason());
    }

    @Test
    @DisplayName("未登记实收 ⇒ 视为全部到齐（向后兼容旧的「只点收货」流程）")
    void nullReceived_meansFullArrival() {
        TransferLossValidator.Result r = TransferLossValidator.validate(100, null, 0, null);
        assertEquals(100, r.received());
        assertEquals(0, r.loss());
    }

    @Test
    @DisplayName("有损耗：实收 95、损耗 5 且写了原因 ⇒ 合法")
    void partialLoss_withReason() {
        TransferLossValidator.Result r = TransferLossValidator.validate(100, 95, 5, "HANDLING_DAMAGE");
        assertEquals(95, r.received());
        assertEquals(5, r.loss());
        assertEquals("HANDLING_DAMAGE", r.lossReason());
    }

    @Test
    @DisplayName("🔴 损耗填 0 但实收少于发运 ⇒ 拒（这就是损耗黑洞的形态）")
    void lossZeroButShortReceived() {
        // 发 100、到 90、损耗填 0 ⇒ 那 10 件凭空消失
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> TransferLossValidator.validate(100, 90, 0, null));
        assertTrue(e.getMessage().contains("应填 10"), "错误消息应告诉运营正确值：" + e.getMessage());
    }

    @Test
    @DisplayName("🔴 损耗多填：实收 95、损耗填 10 ⇒ 拒（算术不平）")
    void lossOverstated() {
        assertThrows(ResponseStatusException.class,
                () -> TransferLossValidator.validate(100, 95, 10, "HANDLING_DAMAGE"));
    }

    @Test
    @DisplayName("🔴 实收大于发运 ⇒ 拒（负损耗）")
    void receivedGreaterThanShipped() {
        assertThrows(ResponseStatusException.class,
                () -> TransferLossValidator.validate(100, 120, -20, "X"));
    }

    @Test
    @DisplayName("🔴 有损耗但没写原因 ⇒ 拒（又是一笔说不清的损耗）")
    void lossWithoutReason() {
        assertThrows(ResponseStatusException.class,
                () -> TransferLossValidator.validate(100, 95, 5, null));
        // 空白串也不行
        assertThrows(ResponseStatusException.class,
                () -> TransferLossValidator.validate(100, 95, 5, "   "));
    }

    @Test
    @DisplayName("全部损耗（实收 0）也是合法场景：货全丢")
    void totalLoss_isValid() {
        TransferLossValidator.Result r = TransferLossValidator.validate(100, 0, 100, "LOST");
        assertEquals(0, r.received());
        assertEquals(100, r.loss());
    }

    @Test
    @DisplayName("原因首尾空白被裁掉（与仓库其他写入口径一致）")
    void reasonIsTrimmed() {
        TransferLossValidator.Result r = TransferLossValidator.validate(100, 95, 5, "  DAMAGED  ");
        assertEquals("DAMAGED", r.lossReason());
    }

    @Test
    @DisplayName("恒等式 received + loss == quantity（6 个场景，与 DB CHECK 语义一致）")
    void arithmeticIdentity() {
        int[][] cases = {{100, 100, 0}, {100, 95, 5}, {50, 0, 50}, {7, 3, 4}, {1, 1, 0}, {999, 998, 1}};
        for (int[] c : cases) {
            int shipped = c[0], received = c[1], loss = c[2];
            TransferLossValidator.Result r = TransferLossValidator.validate(shipped, received, loss, "X");
            assertEquals(shipped, r.received() + r.loss(),
                    "发运" + shipped + " 实收" + received + " 损耗" + loss);
        }
    }
}
