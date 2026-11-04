package com.aicabinet.common.dto;

/**
 * 活动位效果（按展示位分桶，批次 T）。
 *
 * <p>为什么不并进 {@code MarketingRoiRowDto}：ROI 表是**活动**（PromotionActivity）维度，
 * 而 banner 位挂的是**广告计划**（AdCampaign，channel=MINI_PROGRAM）——两者主体不同，
 * 硬塞一张表会让 banner 行凭空消失或错挂到别的活动上。
 *
 * <p>口径说明（前端 HelpTip 同文）：
 * <ul>
 *   <li>{@code exposurePerUser = exposureCount / exposureUsers}，分母只算**已归因**（有 user_id）的行；</li>
 *   <li>{@code unattributedCount} 单独列出：设备侧上报与 V439 之前的历史行没有主体，
 *       混进分母会把人均压成假低值；</li>
 *   <li>{@code position='UNKNOWN'} = 迁移前 banner 位写 {@code MP-U{userId}} 那批行的归位兜底
 *       （V439 已把它们标成 BANNER，正常应为 0）。</li>
 * </ul>
 */
public record PlacementEffectRowDto(
        String position,
        /** ACTIVITY（弹窗位）/ AD_CAMPAIGN（banner 位） */
        String subjectKind,
        Long subjectId,
        String subjectName,
        long exposureCount,
        long exposureUsers,
        long clickCount,
        double exposurePerUser,
        long maxExposurePerUser,
        long unattributedCount
) {}
