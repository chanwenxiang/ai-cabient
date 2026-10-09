package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/**
 * 商户月度结算单（CB-020 ②，V330）：merchant×账期月唯一快照。
 *
 * <p>🔴 联合主键 (merchant_id, period_month)：<b>不设 @TableId</b>，
 * selectById/updateById 不可用，读写一律走 wrapper（同 {@link DeviceDailyOnlineRate}）。</p>
 *
 * <p>状态：{@code PENDING}（默认，随重建刷新）/ {@code CONFIRMED}（人工确认，
 * 重建只刷新金额不动状态）。</p>
 */
@TableName("merchant_settlement_bill")
@Getter
@Setter
public class MerchantSettlementBill {

    private String merchantId;

    /** 账期月首日（Asia/Shanghai 日历月），如 2026-10-01 */
    private LocalDate periodMonth;

    /** 结算单号：确定性生成 SB+yyyyMM-商户号（同月同商户重建不换号） */
    private String billNo;

    private String status = "PENDING";

    private int orderCount;

    private long grossCents;

    private long platformCents;

    private long merchantCents;

    /** status='SUCCESS' 的商户分成合计（已实收微信侧） */
    private long settledCents;

    /** 非 SUCCESS 有效状态的商户分成合计（含入钱包未到账期等） */
    private long pendingCents;

    /** WECHAT_FAILED/FAILED 行数 */
    private int failedCount;

    private Instant computedAt;

    private Instant confirmedAt;
}
