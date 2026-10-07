package com.aicabinet.trade.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * V314：在途损耗的算术校验（发运 − 实收 = 损耗）。
 *
 * <p>🔴 <b>为什么抽成独立类</b>：本规则最初直接写在
 * {@code WarehouseTransferService.doReceive} 里，测试只能<b>复制一份逻辑</b>去验 ——
 * 那种测试改实现不会红，属于<b>假测试</b>。
 * 抽成无状态工具类后，测试直接调它 ⇒ <b>实现变了测试就会红</b>。
 *
 * <p>🔴 <b>为什么损耗不让运营手填</b>：损耗<b>恒等于</b>「发运量 − 实收量」。
 * 一旦手填，就可能出现「实收 90、损耗 0」而发运是 100 ——
 * 那 10 件**凭空消失**而系统不知情，调拨就成了损耗黑洞。
 *
 * <p>DB 侧另有 {@code chk_wh_transfer_loss} CHECK 兜底（防绕过服务直接写库）。
 */
public final class TransferLossValidator {

    private TransferLossValidator() {
    }

    /**
     * 校验并归一化一行的收货登记。
     *
     * @param shipped   发运数量（调拨行的 quantity）
     * @param reportedReceived 运营登记的实收；<b>null = 视为全部到齐</b>（向后兼容旧流程）
     * @param reportedLoss     运营登记的损耗；<b>null = 0</b>。必须等于 {@code shipped - received}
     * @param lossReason损耗原因；{@code loss > 0} 时必填
     * @return 归一化后的 {@link Result}（received/loss 已确定）
     * @throws ResponseStatusException 400 —— 算术不平/ 实超发 / 有损无因
     */
    public static Result validate(int shipped, Integer reportedReceived, Integer reportedLoss, String lossReason) {
        int received = reportedReceived == null ? shipped : reportedReceived;
        int loss = reportedLoss == null ? 0 : reportedLoss;
        int derived = shipped - received;

        if (loss != derived) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "损耗数量应为发运量− 实收量：发运 " + shipped
                            + "，实收 " + received + "，应填 " + derived + "，实际填了 " + loss);
        }
        if (loss < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "实收数量不能大于发运数量");
        }
        if (loss > 0 && (lossReason == null || lossReason.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "有损耗时必须填写损耗原因");
        }
        return new Result(received, loss, lossReason == null || lossReason.isBlank() ? null : lossReason.trim());
    }

    /** 校验结果。 */
    public record Result(int received, int loss, String lossReason) {}
}
