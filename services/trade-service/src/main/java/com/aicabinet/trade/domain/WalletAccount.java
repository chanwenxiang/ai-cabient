package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 三域统一钱包账户（V388，替代 merchant/line/staff_wallet_account 六表中的三张账户表）。
 *
 * <p>主键为<b>复合</b> {@code (domain, owner_id)}：MyBatis-Plus 的单主键 CRUD
 * （selectById/updateById/deleteById）无法表达复合键 ⇒ 本实体的 {@code @TableId} 只标在
 * {@code ownerId}（INPUT，供 insert 携带），<b>读锁定/更新一律走 {@code WalletAccountMapper}
 * 里按 (domain, owner_id) 的显式 SQL</b>，严禁调用 {@code selectById}/{@code updateById}
 * （那会 WHERE owner_id 单列、跨域误伤）。
 */
@TableName("wallet_account")
@Getter
@Setter
public class WalletAccount {

    private String domain;

    @TableId(value = "owner_id", type = IdType.INPUT)
    private String ownerId;

    private Long balanceCents;
    private Long frozenCents;
    private Instant updatedAt;
}
