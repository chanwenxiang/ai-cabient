package com.aicabinet.trade.service;

import com.aicabinet.trade.sms.AliyunSmsSender.SmsReceipt;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 短信到达率的**唯一**聚合口径（阿里云回执 → 计数）。
 *
 * <p>口径与 {@code scripts/sms-delivery-report.mjs} 逐字一致（那边是运维即时排查用的同一算法实现），
 * 两者的共同夹具由 {@code SmsDeliveryStatsTest} 钉住——改一处口径必须让另一处也改，否则测试红。
 * 为什么是聚合而不是逐行：阿里云 {@code QuerySendDetails} 返回体里没有 BizId，
 * 同号同日的多条发送无法对回我方具体某一行日志（2026-11-06 探针实证）。</p>
 */
public record SmsDeliveryStats(int total, int delivered, int pending, int failed,
                               Map<String, Integer> codes) {

    /**
     * 失败占比（整数百分比，向下取整），只用于展示；判定请用 {@link #isFailRatioOver}。
     * total=0 时返回 0，由调用方按「样本不足」处理，不得当健康。
     */
    public int failPercent() {
        return total == 0 ? 0 : (int) ((long) failed * 100 / total);
    }

    /**
     * 判定用精确比较（{@code failed/total > threshold%}），不经过取整：
     * 用 {@link #failPercent()} 判会把 20.9% 读成 20% 而漏报，与 CLI 的比值口径也不一致。
     */
    public boolean isFailRatioOver(int thresholdPercent) {
        return total > 0 && (long) failed * 100 > (long) thresholdPercent * total;
    }

    public static SmsDeliveryStats of(List<SmsReceipt> rows) {
        int delivered = 0;
        int pending = 0;
        int failed = 0;
        // 码分布按 key 排序：告警指纹与 summary 文本都要可复现，Map.copyOf 的迭代顺序不保证
        Map<String, Integer> codes = new TreeMap<>();
        for (SmsReceipt r : rows) {
            String code = r.errCode() == null || r.errCode().isBlank()
                    ? (r.sendStatus() == 1 ? "PENDING" : "UNKNOWN")
                    : r.errCode();
            codes.merge(code, 1, Integer::sum);
            if (r.sendStatus() == 3 && "DELIVERED".equals(code)) {
                delivered++;
            } else if (r.sendStatus() == 1) {
                pending++;
            } else {
                failed++;
            }
        }
        return new SmsDeliveryStats(rows.size(), delivered, pending, failed,
                Collections.unmodifiableMap(codes));
    }

    /** 台账/告警用的一行摘要。 */
    public String summary() {
        return "回执 " + total + " 条：送达 " + delivered + "，发送中 " + pending
                + "，失败 " + failed + "（失败占比 " + failPercent() + "%），码分布 " + codes;
    }
}
