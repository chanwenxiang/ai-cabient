package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 外部商品库（CB-036，V347）。
 *
 * <p>🔴 外部 SKU <b>不进我方商品目录</b>（2026-10-13 拍板）：每渠道自见，
 * 首次出现即建档（{@code timesSeen} 累计），经 {@link ExternalProductMapping} 映射到本系统 SKU。
 * {@code externalKey} 来源由模板 {@code keyField} 决定（商品ID / 归一化名称）。
 */
@TableName("external_product")
public class ExternalProduct {

    @TableId(type = IdType.AUTO)
    private Long externalProductId;

    private String channelCode;
    private String externalKey;
    private String externalName;
    private String brand;
    private String spec;
    private Long unitPriceCents;
    private Instant firstSeenAt;
    private Instant lastSeenAt;
    private int timesSeen;

    public Long getExternalProductId() { return externalProductId; }
    public void setExternalProductId(Long externalProductId) { this.externalProductId = externalProductId; }
    public String getChannelCode() { return channelCode; }
    public void setChannelCode(String channelCode) { this.channelCode = channelCode; }
    public String getExternalKey() { return externalKey; }
    public void setExternalKey(String externalKey) { this.externalKey = externalKey; }
    public String getExternalName() { return externalName; }
    public void setExternalName(String externalName) { this.externalName = externalName; }
    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }
    public String getSpec() { return spec; }
    public void setSpec(String spec) { this.spec = spec; }
    public Long getUnitPriceCents() { return unitPriceCents; }
    public void setUnitPriceCents(Long unitPriceCents) { this.unitPriceCents = unitPriceCents; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(Instant firstSeenAt) { this.firstSeenAt = firstSeenAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public int getTimesSeen() { return timesSeen; }
    public void setTimesSeen(int timesSeen) { this.timesSeen = timesSeen; }
}
