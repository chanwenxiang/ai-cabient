package com.aicabinet.trade.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "cabinet_order_line")
public class CabinetOrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private CabinetOrder order;

    @Column(nullable = false, length = 64)
    private String skuId;

    @Column(length = 128)
    private String skuName;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private int unitPriceCents;

    @Column(nullable = false)
    private int lineAmountCents;

    private Float confidence;

    @Column(length = 64)
    private String batchNo;

    private Integer unitCostCents;

    public Long getId() { return id; }
    public void setOrder(CabinetOrder order) { this.order = order; }
    public String getSkuId() { return skuId; }
    public void setSkuId(String skuId) { this.skuId = skuId; }
    public String getSkuName() { return skuName; }
    public void setSkuName(String skuName) { this.skuName = skuName; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public int getUnitPriceCents() { return unitPriceCents; }
    public void setUnitPriceCents(int unitPriceCents) { this.unitPriceCents = unitPriceCents; }
    public int getLineAmountCents() { return lineAmountCents; }
    public void setLineAmountCents(int lineAmountCents) { this.lineAmountCents = lineAmountCents; }
    public Float getConfidence() { return confidence; }
    public void setConfidence(Float confidence) { this.confidence = confidence; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public Integer getUnitCostCents() { return unitCostCents; }
    public void setUnitCostCents(Integer unitCostCents) { this.unitCostCents = unitCostCents; }
}
