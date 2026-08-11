package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.aicabinet.trade.config.JsonStringTypeHandler;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName(value = "ota_release", autoResultMap = true)
@Getter
@Setter
public class OtaRelease {

    @TableId(type = IdType.AUTO)
    private Long releaseId;

    private String appVersion;

    private String channel = "stable";

    private String downloadUrl;

    private String checksumSha256;

    private String releaseNotes;

    private boolean mandatory;

    private String minVersion;

    private String status = "DRAFT";

    private Instant publishedAt;

    private String objectStorageUri;

    private int grayPercent = 100;

    @TableField(typeHandler = JsonStringTypeHandler.class)
    private String deviceAllowlist;

    private int presignTtlSeconds = 3600;

    private Instant createdAt;

}
