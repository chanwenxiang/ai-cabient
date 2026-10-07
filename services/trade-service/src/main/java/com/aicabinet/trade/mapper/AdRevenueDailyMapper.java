package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.AdRevenueDaily;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.time.LocalDate;
import java.util.List;

/**
 * V310：广告收益日账。
 */
public interface AdRevenueDailyMapper extends BaseMapper<AdRevenueDaily> {

    /**
     * 指定区间的广告位行（按日期升序）。
     *
     * @param dataSource ESTIMATE / SETTLED；<b>null 表示两种都要</b>（报表层对比用）
     */
    default List<AdRevenueDaily> findRange(LocalDate from, LocalDate to, String dataSource) {
        var q = Wrappers.<AdRevenueDaily>lambdaQuery()
                .ge(AdRevenueDaily::getBizDate, from)
                .le(AdRevenueDaily::getBizDate, to)
                .orderByAsc(AdRevenueDaily::getBizDate)
                .orderByAsc(AdRevenueDaily::getAdSlot);
        if (dataSource != null && !dataSource.isBlank()) {
            q.eq(AdRevenueDaily::getDataSource, dataSource);
        }
        return selectList(q);
    }

    /**
     * 按广告位汇总（区间内求和）。
     *
     * <p>🔴 <b>为什么用 SQL 聚合而不是内存求和</b>：
     * 区间跨 90 天 × 多个广告位时行数上千，内存聚合要拉全量明细。
     * 更重要的是<b>金额求和必须由 DB 做</b> —— 放进 Java 会引入
     * long 溢出与「部分行丢失」的静默风险。
     */
    default List<java.util.Map<String, Object>> sumBySlot(LocalDate from, LocalDate to, String dataSource) {
        var q = Wrappers.<AdRevenueDaily>query()
                .select("ad_slot",
                        "SUM(exposure_count) AS exposure_count",
                        "SUM(click_count) AS click_count",
                        "SUM(income_cents) AS income_cents")
                .ge("biz_date", from)
                .le("biz_date", to)
                .eq(dataSource != null && !dataSource.isBlank(), "data_source", dataSource)
                .groupBy("ad_slot")
                .orderByAsc("ad_slot");
        return selectMaps(q);
    }

    /**
     * 区间总收入（分）。
     *
     * <p>⚠️ 返回 {@code null} 表示<b>该区间没有任何行</b>（区别于「有行但收入 0」）。
     * 两者在报表上意义完全不同：前者是「没拉到数据/没开通」，
     * 后者是「有曝光但没收入」。上层必须能区分 —— 否则会把故障显示成「收益为 0」。
     */
    default Long sumIncomeCents(LocalDate from, LocalDate to, String dataSource) {
        var q = Wrappers.<AdRevenueDaily>query()
                .select("COALESCE(SUM(income_cents), 0) AS total")
                .ge("biz_date", from)
                .le("biz_date", to)
                .eq(dataSource != null && !dataSource.isBlank(), "data_source", dataSource);
        var list = selectMaps(q);
        if (list.isEmpty()) {
            return null;
        }
        Object v = list.get(0).get("total");
        return v == null ? null : ((Number) v).longValue();
    }
}
