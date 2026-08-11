package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("ops_2fa_recovery_code")
@Getter
@Setter
public class OpsTwoFactorRecoveryCode {

    private Long userId;
    private String codeHash;
    private boolean used;
    private Instant createdAt;

}
