package com.aicabinet.trade.payment;

import com.aicabinet.trade.service.SystemConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 微信分账「单接收方比例上限」判据（资金 #13 / MA-389）——**判据单源**。
 *
 * <p><b>为什么需要</b>：{@code /v3/profitsharing/orders} 的 {@code receivers[].amount} 受服务商
 * 协议限制（CB-032 记的默认口径是单接收方 ≤ 订单毛额 30%，⚠️ 仅 ⚪ 旁证、提额真实性未与微信
 * 书面确认）。而本仓分账模型是「商户作为接收方拿走 {@code gross - platform}」：平台抽成一旦配成
 * 现实档位（如 5%），接收方比例就是 95%，请求必被渠道拒绝 ⇒ 批量分账失败、钱卡在待分账态。
 * 提交前先在本类拦一道，把「渠道拒绝」换成「本地确定性拒绝 + 可执行原因」。
 *
 * <p><b>为什么不改分账模型</b>：把「平台>0 就走分账」改成「走钱包入账」是<b>出款通道的拍板</b>
 * （CB-032 六问未答复），不是缺陷修复；{@code RevenueSplitServiceTest} 有十几处用例钉着
 * 「platform>0 ⇒ ACCRUED 走分账」这一设计。故此处只做<b>提交闸</b>，不改账本状态机。
 *
 * <p>🔴 <b>0 不等于「不限制」</b>：与提现族（0=不限制）语义相反，本闸填 0 就是关闸门，
 * 因此 0/越界一律回落 {@value #DEFAULT_MAX_PERCENT}（fail-closed）。
 */
@Component
public class ProfitSharingCapPolicy {

    /** CB-032 口径的单接收方默认上限（百分数）。容器化在 SystemConfig，可运行期调整。 */
    public static final int DEFAULT_MAX_PERCENT = 30;

    private static final Logger log = LoggerFactory.getLogger(ProfitSharingCapPolicy.class);

    private final SystemConfigService systemConfigService;

    public ProfitSharingCapPolicy(SystemConfigService systemConfigService) {
        this.systemConfigService = systemConfigService;
    }

    /** 当前生效上限（百分数，1-100）；非法值回落默认。 */
    public int maxPercent() {
        int configured = systemConfigService.getInt(
                SystemConfigService.PROFIT_SHARING_RECEIVER_MAX_PERCENT, DEFAULT_MAX_PERCENT);
        if (configured <= 0 || configured > 100) {
            log.warn("CONFIG {} = {} 非法（应 1-100），按默认 {} 处理",
                    SystemConfigService.PROFIT_SHARING_RECEIVER_MAX_PERCENT, configured,
                    DEFAULT_MAX_PERCENT);
            return DEFAULT_MAX_PERCENT;
        }
        return configured;
    }

    /**
     * 接收方金额是否超过上限。
     *
     * <p>无基数（{@code grossCents <= 0}）或无份额时不判——那是既有闸（{@code merchantCents<=0}
     * 落 LEDGER_ONLY）的职责，这里不重复也不抢先。
     */
    public boolean exceedsCap(long receiverCents, long grossCents) {
        if (grossCents <= 0 || receiverCents <= 0) {
            return false;
        }
        return receiverCents * 100L > grossCents * (long) maxPercent();
    }

    /** 拒绝原因（写进 {@code order_revenue_split.failure_reason}，运营台「失败原因」列直接可见）。 */
    public String refusalReason(long receiverCents, long grossCents) {
        int cap = maxPercent();
        long actualPercent = grossCents > 0
                ? Math.round(receiverCents * 100.0d / grossCents) : 0L;
        return "未提交分账：接收方 " + receiverCents + " 分占订单 " + grossCents + " 分的 "
                + actualPercent + "%，超过单接收方上限 " + cap + "%（微信服务商分账口径，CB-032）"
                + "——请将平台抽成调为 0（改钱包入账）或改走商家转账出款；"
                + "拿到微信书面提额确认后再调高 fund.profit_sharing.receiver_max_percent";
    }
}
