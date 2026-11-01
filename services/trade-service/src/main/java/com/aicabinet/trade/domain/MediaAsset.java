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
    /** 图片原始像素尺寸（上传时读文件头）；视频/无法解析时为 null（CB-082）。 */
    private Integer imageWidth;
    private Integer imageHeight;
    private String status = "ACTIVE";
    private Long uploadedBy;
    private Instant createdAt;

}
