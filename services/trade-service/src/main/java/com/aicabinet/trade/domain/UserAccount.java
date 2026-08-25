package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("user_account")
@Getter
@Setter
public class UserAccount {

    @TableId(type = IdType.INPUT)
    private Long userId;

    private int balanceCents;

    private int frozenCents;

    private Instant updatedAt;

}
