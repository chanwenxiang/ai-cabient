package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("user_notify_pref")
@Getter
@Setter
public class UserNotifyPref {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String category;

    private Boolean enabled = Boolean.TRUE;

    private Instant updatedAt = Instant.now();

}
