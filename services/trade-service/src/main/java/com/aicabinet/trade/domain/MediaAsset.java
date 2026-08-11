package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("media_asset")
@Getter
@Setter
public class MediaAsset {

    @TableId(type = IdType.AUTO)
    private Long assetId;
    private String title;
    private String assetType;
    private String storageUri;
    private int durationSeconds;
    private String status = "ACTIVE";
    private Long uploadedBy;
    private Instant createdAt;

}
