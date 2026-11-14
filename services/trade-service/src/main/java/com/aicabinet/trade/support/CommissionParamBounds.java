package com.aicabinet.trade.support;

import com.aicabinet.common.constants.CabinetConstants;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 佣金/分成参数限幅单源（MA-069 线长、MA-090 机器账单、MA-100 线路成员三处同族）。
 *
 * <p>为什么必须单源：这三处都是<b>裸 Map / 裸实体直落</b>，参数被
 * {@code LineCommissionJob} 与账单生成按万分比乘进真实出账，越界值不是「显示错」而是
 * 持续放大的资金乘数（rateBps=5000 ⇒ 5 倍 GMV 佣金）。仓内已有三处先例各写一套
 * （{@code SiteRentSplitService} 400、{@code SiteRentBillService} 钳位、
 * {@code FundBillService} 回落默认），本类把「写侧一律 400」这条口径收成一个口子。
 *
 * <p>{@link #FIXED_CENTS_MAX} 是<b>数量级防线，不是业务上限</b>：本系统是饮料零售柜，
 * 每件固定佣金 ¥50 已远超任何合法配置。刻意<b>不做成参数配置旋钮</b>——
 * 真需要更高的固定佣金档位时应当先改代码并留下评审，而不是让线上一个手滑就把
 * 每件 5000 分写进乘数链（与 {@code FUND_POSITIVE_LIMIT_KEYS} 那些「上限可调」的键不同，
 * 那些键管的是单笔收付阈值，不是长期生效的分成系数）。
 */
public final class CommissionParamBounds {

    /** 每件/每单固定佣金上限（分）= ¥50。见类注释：数量级防线。 */
    public static final int FIXED_CENTS_MAX = 5_000;

    /**
     * 提现手续费率天花板（万分比）= 10%。S12 扩面（2026-11-14 拍板，
     * docs/PENDING_DECISIONS_R23_2026-11-14.md ⑧）：老系统 6‰=60bps，此处天花板只防
     * 「手填荒谬值」（如 50000bps=5 倍手续费），不是业务费率上限——调费率走正常拍板。
     * 适用 merchant.withdraw.fee_bps（全局+按商户前缀形态）与 line.withdraw.fee_bps。
     */
    public static final int WITHDRAW_FEE_BPS_MAX = 1_000;

    /**
     * 余额退款单笔上限天花板（分）= ¥5 万。DC-6 键 balance.refund.max_cents 的上界——
     * 写侧 400、读侧 {@code getPositiveLimit} 回落默认（存量行双保险）。
     */
    public static final int REFUND_LIMIT_CENTS_CEILING = 5_000_000;

    /**
     * 开门预授权天花板（分）= ¥1 万。checkout.preauth_cents 的上界——0 仍合法
     * （=关闭预授权，2026-11-11 现状），故该键只进 {@code FUND_GUARDED_KEYS} 不进 DC-6 集合。
     */
    public static final int PREAUTH_CENTS_CEILING = 1_000_000;

    private CommissionParamBounds() {}

    /** 万分比：0..10000（{@link CabinetConstants#SHARE_BPS_FULL}）。>10000 在数学上不成立。 */
    public static int requireBps(String field, Integer value) {
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " 不能为空");
        }
        if (value < 0 || value > CabinetConstants.SHARE_BPS_FULL) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " 须在 0.." + CabinetConstants.SHARE_BPS_FULL + "（万分比，10000 = 100%）");
        }
        return value;
    }

    /** 固定佣金（分/件）：0..{@link #FIXED_CENTS_MAX}。 */
    public static int requireFixedCents(String field, Integer value) {
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " 不能为空");
        }
        if (value < 0 || value > FIXED_CENTS_MAX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " 须在 0.." + FIXED_CENTS_MAX + " 分");
        }
        return value;
    }

    /** 同上，用于实体里已是 {@code Long} 的分位字段（避免各处手写 0..N 判断漂移出第二套上限）。 */
    public static long requireCents(String field, Long value) {
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " 不能为空");
        }
        if (value < 0 || value > FIXED_CENTS_MAX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " 须在 0.." + FIXED_CENTS_MAX + " 分");
        }
        return value;
    }
}
