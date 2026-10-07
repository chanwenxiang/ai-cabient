package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.AdRevenueDaily;
import com.aicabinet.trade.mapper.AdRevenueDailyMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aicabinet.trade.wechat.WeChatPublisherStatClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * V310：广告收益拉取与落库（切片 3）。
 *
 * <p><b>职责边界</b>：只「把腾讯算出来的数抄回本地」，
 * <b>不改任何资金</b>。收入入账（切片 4）是另一个动作 ——
 * 两者混在一起会让人以为「拉下来就是赚到了」。
 *
 * <p>🔴 <b>为什么必须落库</b>：微信两个接口最大跨度 90 天，
 * 超窗取不到 ⇒ 必须定期拉。想查半年前的收入时现拉已经来不及。
 */
@Service
public class AdRevenueSyncService {

    private static final Logger log = LoggerFactory.getLogger(AdRevenueSyncService.class);

    private static final String SOURCE_ESTIMATE = "ESTIMATE";
    private static final String SOURCE_SETTLED = "SETTLED";

    /**
     * 微信最大回溯天数。
     *
     * <p>🔴 定为 88 而非 90：<b>留 2 天缓冲</b>。微信的「90 天」按自然日算，
     * 而我们要在 T+1 拉 T 日的数据；若卡在第 90 天边界，微信侧时区/口径差异
     * 会让最早那一天取不到。宁可少拉两天，也不要出现「某天数据永久缺失」。
     */
    static final int MAX_LOOKBACK_DAYS = 88;

    private final AdRevenueDailyMapper mapper;
    private final WeChatPublisherStatClient client;

    public AdRevenueSyncService(AdRevenueDailyMapper mapper, WeChatPublisherStatClient client) {
        this.mapper = mapper;
        this.client = client;
    }

    /**
     * 拉取并落库广告预估收益（按广告位×日）。
     *
     * @param adSlot 只关心某个广告位时传（如 {@code SLOT_ID_WEAPP_BANNER}）；
     *               <b>null = 全部类型</b>（数据量大得多）
     * @return 落库行数
     * @throws IllegalStateException 微信侧报错时<b>直接抛</b>，
     *         绝不吞成「今天拉到 0 条」—— 那会把「没开通流量主」伪装成「没收入」
     */
    @Transactional
    public int syncEstimates(LocalDate from, LocalDate to, String adSlot) {
        List<WeChatPublisherStatClient.AdPosGeneralRow> rows =
                client.fetchAdPosGeneral(from, to, adSlot);
        if (rows.isEmpty()) {
            log.info("ad revenue: 微信返回空（from={} to={} slot={}）—— 可能尚未开通流量主或区间内无曝光",
                    from, to, adSlot);
            return 0;
        }
        int saved = 0;
        for (WeChatPublisherStatClient.AdPosGeneralRow row : rows) {
            if (row.date == null || row.adSlot == null || row.adSlot.isBlank()) {
                log.warn("跳过缺关键字段的行: date={} slot={}", row.date, row.adSlot);
                continue;
            }
            upsert(toEntity(row.date, row.adSlot, null, null, row.reqSuccCount,
                    row.exposureCount, row.clickCount, row.incomeCents, row.ecpmCents, SOURCE_ESTIMATE));
            saved++;
        }
        log.info("ad revenue: 预估收益落库 {} 行 range={}..{} slot={}", saved, from, to, adSlot);
        return saved;
    }

    /**
     * 拉取并落库<b>结算</b>收益（已确认口径，按广告位）。
     *
     * <p>结算接口的粒度是「半月 × 广告位」，这里把每个广告位明细摊到
     * {@code settlement_list[].date}（结算数据更新时间）那一天的 biz_date 上。
     *
     * <p>⚠️ <b>为什么只落 {@code isSettled()} 的行</b>：
     * {@code sett_status} 1=结算中、4=付款中 —— 这两种状态下金额随时可能变，
     * 落库等于记了一笔会变的账。收入确认只认 2/3/5。
     */
    @Transactional
    public int syncSettlements(LocalDate from, LocalDate to) {
        List<WeChatPublisherStatClient.SettlementRow> rows = client.fetchSettlement(from, to);
        if (rows.isEmpty()) {
            log.info("ad revenue: 结算接口返回空 range={}..{} —— 尚无结算记录属正常", from, to);
            return 0;
        }
        int saved = 0;
        for (WeChatPublisherStatClient.SettlementRow row : rows) {
            if (!row.isSettled()) {
                log.info("跳过未完成结算: zone={} status={}（1 结算中/4 付款中，金额还会变）",
                        row.zone, row.settStatus);
                continue;
            }
            // 结算行没有「日」粒度，只有结算区间的更新时间。
            // 落在该更新日期上，并在 ad_unit_name 里标明是哪个半月 —— 宁可粒度粗，
            // 也不能把半月收入伪造成某一天的日收入（那会让日趋势图失真）。
            LocalDate bizDate = parseSettlementDate(row);
            if (bizDate == null) {
                log.warn("结算行缺可解析日期: zone={}", row.zone);
                continue;
            }
            for (WeChatPublisherStatClient.SlotRevenue sr : row.bySlot) {
                if (sr.adSlot == null || sr.adSlot.isBlank()) {
                    continue;
                }
                upsert(toEntity(bizDate, sr.adSlot, null, row.zone,
                        0L, 0L, 0L, sr.settledRevenueCents, Double.NaN, SOURCE_SETTLED));
                saved++;
            }
        }
        log.info("ad revenue: 结算收益落库 {} 行 range={}..{}", saved, from, to);
        return saved;
    }

    /**
     * 回溯窗口内的日常同步入口（给定时任务用）。
     *
     * <p>默认只拉预估 —— 结算每半月才变，定时拉没有意义，
     * 交给运营手动触发或月度任务。
     */
    public int syncRecentEstimates(String adSlot) {
        LocalDate to = LocalDate.now().minusDays(1);
        LocalDate from = to.minusDays(MAX_LOOKBACK_DAYS);
        return syncEstimates(from, to, adSlot);
    }

    /**
     * UPSERT（覆盖而非累加）。
     *
     * <p>🔴 <b>为什么必须覆盖</b>：定时任务会反复拉同一区间（微信不提供
     * 「增量」接口），若用「有则累加」逻辑，收入会<b>每次调度翻一倍</b>。
     * 这是这类同步任务最经典的 bug。
     */
    private void upsert(AdRevenueDaily entity) {
        AdRevenueDaily existing = mapper.selectOne(
                Wrappers.<AdRevenueDaily>lambdaQuery()
                        .eq(AdRevenueDaily::getBizDate, entity.getBizDate())
                        .eq(AdRevenueDaily::getAdSlot, entity.getAdSlot())
                        .eq(AdRevenueDaily::getDataSource, entity.getDataSource())
                        .last("LIMIT 1"));
        if (existing == null) {
            mapper.insert(entity);
            return;
        }
        entity.setAdRevenueId(existing.getAdRevenueId());
        // created_at 保留首次入库时间，updated_at 刷新 —— 能看出「这条被重拉了几次」
        entity.setCreatedAt(existing.getCreatedAt());
        entity.setUpdatedAt(java.time.Instant.now());
        mapper.updateById(entity);
    }

    private AdRevenueDaily toEntity(LocalDate date, String adSlot, String adUnitId, String adUnitName,
                                    long reqSucc, long exposure, long click,
                                    long incomeCents, double ecpmCents, String source) {
        AdRevenueDaily e = new AdRevenueDaily();
        e.setBizDate(date);
        e.setAdSlot(adSlot);
        e.setAdUnitId(adUnitId);
        e.setAdUnitName(adUnitName);
        e.setDataSource(source);
        e.setReqSuccCount(reqSucc);
        e.setExposureCount(exposure);
        e.setClickCount(click);
        e.setIncomeCents(incomeCents);
        e.setEcpmMicros(toEcpmMicros(ecpmCents));
        return e;
    }

    /**
     * eCPM 换算：微信给「分」（double），存「微元」（long）。
     *
     * <p>放大 100 倍而不是直接存分：eCPM 常见值 6.79 分，存整数分会截成 6，
     * <b>误差 11%</b>。用 BigDecimal 而不是 {@code long} 强转，
     * 避免二进制浮点放大后再截断的二次误差。
     *
     * @return null 表示微信未返回（{@link Double#NaN}）——
     *         <b>不是 0</b>：0 是合法 eCPM（零收益曝光），与「没测到」意义不同
     */
    static Long toEcpmMicros(double ecpmCents) {
        if (Double.isNaN(ecpmCents) || Double.isInfinite(ecpmCents)) {
            return null;
        }
        return BigDecimal.valueOf(ecpmCents)
                .multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** 从结算行的 {@code date}（yyyy-MM-dd）解析；不可解析时回退到区间起点。 */
    private LocalDate parseSettlementDate(WeChatPublisherStatClient.SettlementRow row) {
        // SettlementRow 目前不暴露 date 字段（结算数据的 date 是「数据更新时间」，
        // 语义与 zone 不同），这里保守用 zone 里的起始月第一天。
        // 🔴 粒度粗是刻意的：把半月收入伪造成日收入会让趋势图失真。
        if (row.month != null && row.month.length() == 6) {
            try {
                int year = Integer.parseInt(row.month.substring(0, 4));
                int month = Integer.parseInt(row.month.substring(4, 6));
                int day = row.order == 2 ? 16 : 1;
                return LocalDate.of(year, month, day);
            } catch (RuntimeException ignored) {
                // 落到下面的 null
            }
        }
        return null;
    }
}
