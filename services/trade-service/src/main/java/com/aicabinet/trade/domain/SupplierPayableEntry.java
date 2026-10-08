package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 应付事件流水（V326，E3 缺口 #8，竞品口径见 CB-016）。
 *
 * <p><b>为什么存在</b>：{@link SupplierPayable#getAmountCents()} 是原地修改的快照
 * （收货累加/退货冲减都改同一行），撑不起「期初/本期发生」的对账口径。
 * 本表 append-only：每笔 {@code RECEIVE}/{@code RETURN}/{@code OPENING} 一行，只增不改。
 *
 * <p><b>付款为什么不在本表</b>：付款已有精确流水 {@link SupplierPayment}（V159），
 * 同一笔钱不两处记账——对账聚合时两表各取一半。
 *
 * <p>🔴 不变量：对任意时刻 T，Σ(RECEIVE+OPENING) − Σ(RETURN) − Σ付款 = 主表当前未付余额。
 * 因此 RETURN 必须记「实际冲减额」（主表 {@code Math.max(0, old−ret)} 截断时记实际变化量），
 * 而不是请求的退货额。
 */
@TableName("supplier_payable_entry")
@Getter
@Setter
public class SupplierPayableEntry {

    public static final String TYPE_RECEIVE = "RECEIVE";
    public static final String TYPE_RETURN = "RETURN";
    public static final String TYPE_OPENING = "OPENING";

    @TableId(type = IdType.AUTO)
    private Long entryId;
    private String supplierId;
    private Long payableId;
    private Long purchaseOrderId;
    private String entryType;
    private long amountCents;
    private Long operatorId;
    private String notes;
    private Instant createdAt;

}
