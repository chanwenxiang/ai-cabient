package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.time.LocalDate;

/**
 * V310：广告收益日账（腾讯流量主）。
 *
 * <p>🔴 <b>为什么独立成表而不挂 {@code OrderRevenueSplit}</b>：
 * 交易分账以 {@code order_id} 为锚（分账 + 全额退款 void 联动），
 * 而<b>广告收入没有订单</b>。硬挂只能造「假订单」，
 * 污染订单表与资金对账口径（对账时会看到一堆无货品的订单）。
 * ⇒ 存储层独立，只在报表层汇总。
 *
 * <p>⚠️ <b>预估与结算同表但不同 {@code data_source}</b>：
 * {@code ESTIMATE} 来自 {@code publisher_adpos_general}（每天变，仅趋势参考），
 * {@code SETTLED} 来自 {@code publisher_settlement}（半月结算，收入确认以此为准）。
 * 混在一起会让人把预估当已到账。
 */
@TableName("ad_revenue_daily")
public class AdRevenueDaily {

    @TableId(type = IdType.AUTO)
    private Long adRevenueId;

    private LocalDate bizDate;
    /** 广告位类型名，如 {@code SLOT_ID_WEAPP_BANNER}。 */
    private String adSlot;
    /**
     * 广告单元 ID。
     *
     * <p>⚠️ <b>刻意不进唯一键</b>：微信在广告位重建后会换 ID，
     * 放进唯一键会把同一广告位拆成两行、收入对不上。详见 V310 迁移注释。
     */
    private String adUnitId;
    private String adUnitName;
    /** ESTIMATE / SETTLED（DB 有 CHECK 约束）。 */
    private String dataSource = "ESTIMATE";

    private long reqSuccCount;
    private long exposureCount;
    private long clickCount;
    /** 收入，单位<b>分</b>。微信返回的 income 本身就是分，未做换算。 */
    private long incomeCents;
    /**
     * 千次曝光收益，单位<b>微元</b>（1 元 = 1_000_000 微元）。
     *
     * <p>🔴 微信返回的 {@code ecpm} 是 double 且单位为<b>分</b>。
     * 存成「分」会丢精度（分是整数，eCPM 常见 6.79 分这种小数），
     * 所以放大 100 倍存成微元 —— <b>整数</b>才能精确比较与聚合。
     * null = 微信未返回（不是 0）。
     */
    private Long ecpmMicros;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    public Long getAdRevenueId() {
        return adRevenueId;
    }

    public void setAdRevenueId(Long adRevenueId) {
        this.adRevenueId = adRevenueId;
    }

    public LocalDate getBizDate() {
        return bizDate;
    }

    public void setBizDate(LocalDate bizDate) {
        this.bizDate = bizDate;
    }

    public String getAdSlot() {
        return adSlot;
    }

    public void setAdSlot(String adSlot) {
        this.adSlot = adSlot;
    }

    public String getAdUnitId() {
        return adUnitId;
    }

    public void setAdUnitId(String adUnitId) {
        this.adUnitId = adUnitId;
    }

    public String getAdUnitName() {
        return adUnitName;
    }

    public void setAdUnitName(String adUnitName) {
        this.adUnitName = adUnitName;
    }

    public String getDataSource() {
        return dataSource;
    }

    public void setDataSource(String dataSource) {
        this.dataSource = dataSource;
    }

    public long getReqSuccCount() {
        return reqSuccCount;
    }

    public void setReqSuccCount(long reqSuccCount) {
        this.reqSuccCount = reqSuccCount;
    }

    public long getExposureCount() {
        return exposureCount;
    }

    public void setExposureCount(long exposureCount) {
        this.exposureCount = exposureCount;
    }

    public long getClickCount() {
        return clickCount;
    }

    public void setClickCount(long clickCount) {
        this.clickCount = clickCount;
    }

    public long getIncomeCents() {
        return incomeCents;
    }

    public void setIncomeCents(long incomeCents) {
        this.incomeCents = incomeCents;
    }

    public Long getEcpmMicros() {
        return ecpmMicros;
    }

    public void setEcpmMicros(Long ecpmMicros) {
        this.ecpmMicros = ecpmMicros;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}