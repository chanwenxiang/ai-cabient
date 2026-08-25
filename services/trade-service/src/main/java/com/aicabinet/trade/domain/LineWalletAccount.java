package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("line_wallet_account")
@Getter
@Setter
public class LineWalletAccount {

    @TableId
    private Long managerId;
    private Long balanceCents;
    private Long frozenCents;
    private Instant updatedAt;

}
