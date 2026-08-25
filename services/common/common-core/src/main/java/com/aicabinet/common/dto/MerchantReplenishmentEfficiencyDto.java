package com.aicabinet.common.dto;

/**
 * 补货员今日补货效率（移动端「运营执行情况」）。
 *
 * @param todayAssigned    今日已分配给我的补货任务数
 * @param todayCompleted   今日已完成数
 * @param todayInProgress  今日进行中（已签到未完成）数
 * @param todayPending     今日待处理数
 * @param completionRatePercent 今日完成率（%）
 */
public record MerchantReplenishmentEfficiencyDto(
        int todayAssigned,
        int todayCompleted,
        int todayInProgress,
        int todayPending,
        double completionRatePercent
) {}
