package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 欠款催缴阶梯（V325 表 {@code unpaid_dunning_record}）。
 *
 * <p>依据 CB-011：抄共享充电宝的阶梯式惩罚范式 —— 累计 3 次未还即进「谨慎名单」，
 * 之后要求双倍押金；极端情况冻结该用户的免密先享能力。
 *
 * 🔴 与 {@link UserBlacklist} 的分工（两者都存在，别混）：
 * <ul>
 *   <li>{@code UserBlacklist} —— <b>硬拦截</b>：开门直接拒，有/无 + 到期时间；</li>
 *   <li>本表 —— <b>软阶梯</b>：记录累计欠款次数与当前限制等级，可随还清自愈。</li>
 * </ul>
 *
 * 🔴 合规红线（CB-011）：本表**只用于平台内行为限制**。
 * 我们不是持牌征信机构，**不得对外宣称上报征信**（哈啰案例：无持牌主体仅能做信息共享，
 * 且需信息主体事先书面同意；企业「无权司法认定、无权单方拉黑」）。
 */
@TableName("unpaid_dunning_record")
@Getter
@Setter
public class UnpaidDunningRecord {

    @TableId(type = IdType.INPUT)
    private Long userId;

    /** 累计欠款关单次数（阶梯判据）。 */
    private Integer unpaidCount = 0;

    /** 当前阶梯：0 无 / 1 提醒 / 2 限制额度 / 3 限制免密先享。 */
    private Integer tier = 0;

    private Instant lastRemindedAt;

    private Instant lastDunningAt;

    private Instant updatedAt;
}
