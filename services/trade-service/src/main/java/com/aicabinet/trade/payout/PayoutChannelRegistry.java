package com.aicabinet.trade.payout;

import com.aicabinet.common.constants.CabinetConstants;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 打款通道注册表：上层（*WithdrawPayoutService）只通过它取通道，不直接依赖具体实现。
 *
 * <p>好处：① 加第 4 个通道（如聚合支付、云闪付）只加一个 {@link PayoutChannel} Bean，本类不用改；
 * ② 「当前哪些通道可用」有唯一出口（{@link #readiness()}），运营后台可直接展示，避免
 * 像改造前那样散落在两个 {@code modeInfo()} 里各写一份字面量。
 *
 * <p>⚠️ <b>MOCK 不是通道</b>：记账打款是「是否跳过真实出款」的开关，不是通道类型。
 * 它由 {@code aicabinet.*-withdraw.mock-enabled} 控制，走 mock 时<b>不查本注册表</b>。
 */
@Component
public class PayoutChannelRegistry {

    private final Map<String, PayoutChannel> channels;

    public PayoutChannelRegistry(List<PayoutChannel> channelList) {
        Map<String, PayoutChannel> map = new LinkedHashMap<>();
        for (PayoutChannel channel : channelList) {
            PayoutChannel duplicated = map.put(channel.channel(), channel);
            if (duplicated != null) {
                throw new IllegalStateException("重复的打款通道实现: " + channel.channel()
                        + "（" + duplicated.getClass().getSimpleName() + " 与 "
                        + channel.getClass().getSimpleName() + "）");
            }
        }
        this.channels = Map.copyOf(map);
    }

    public Optional<PayoutChannel> find(String channel) {
        return channel == null ? Optional.empty() : Optional.ofNullable(channels.get(channel));
    }

    /** 取通道；未注册时抛 400（说明配置/数据写错了，不该静默降级）。 */
    public PayoutChannel require(String channel) {
        return find(channel).orElseThrow(() -> new PayoutChannel.PayeeIncompatibleException(
                "未注册的打款通道：" + channel + "（可用：" + String.join("/", channels.keySet()) + "）"));
    }

    public boolean isReady(String channel) {
        return find(channel).map(PayoutChannel::isReady).orElse(false);
    }

    /**
     * 各通道就绪状态，供运营后台「打款模式」面板展示。
     *
     * <p>🔴 与改造前 {@code modeInfo()} 的差异：真实渠道一律显示「未就绪（缺什么）」，
     * <b>不因支付参数已配就显示可用</b> —— 转账产品开通状态无法从配置推断。
     */
    public Map<String, Object> readiness() {
        Map<String, Object> result = new LinkedHashMap<>();
        channels.forEach((name, channel) -> {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("ready", channel.isReady());
            info.put("supportedPayeeType", channel.supportedAccountType());
            // V308：把渠道硬限额一并暴露 —— 运营要能在后台看到「为什么这商户只能提 ¥200」，
            // 而不必去翻代码常量。三维度（单笔/单收款人单日/单通道当日总额）0 = 不限。
            info.put("limits", channel.channelLimits().describe());
            result.put(name, info);
        });
        // 不用 Map.copyOf：拒绝 null 值，且会丢顺序（前端要按 WECHAT/ALIPAY/BANK 固定顺序展示）
        return result;
    }

    /** 全部已注册通道名（顺序稳定，供前端下拉与文档）。 */
    public List<String> registeredChannels() {
        return List.of(CabinetConstants.PAY_CHANNEL_WECHAT,
                CabinetConstants.PAY_CHANNEL_ALIPAY,
                PayoutConstants.PAY_CHANNEL_BANK);
    }
}
