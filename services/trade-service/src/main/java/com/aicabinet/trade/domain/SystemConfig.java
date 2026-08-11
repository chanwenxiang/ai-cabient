package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("system_config")
@Getter
@Setter
public class SystemConfig {

    @TableId(type = IdType.INPUT)
    private String configKey;

    private String configValue;

    private String description;

    private Instant updatedAt = Instant.now();








}
