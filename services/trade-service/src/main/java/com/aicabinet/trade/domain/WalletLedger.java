package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 三域统一钱包流水（V388，替代 merchant/line/staff_wallet_ledger 三张流水表）。
 *
 * <p>主键 {@code ledger_id} 为单列 BIGSERIAL ⇒ MP 标准 CRUD 可用。{@code ownerSnapshotName}
 * 承接原商户台账 V241 的 {@code merchant_name} 冗余（线长/补货员为 NULL）。
 */
@TableName("wallet_ledger")
@Getter
@Setter
public class WalletLedger {

    @TableId(type = IdType.AUTO)
    private Long ledgerId;

    private String domain;
    private String ownerId;
    private String entryType;
    private Long amountCents;
    private Long balanceAfter;
    private Long frozenAfter;
    /** 记账时所有者名称快照（仅商户域），对齐旧 merchant_wallet_ledger.merchant_name */
    private String ownerSnapshotName;
    private String refType;
    private String refId;
    private String remark;
    private Instant createdAt;
}
