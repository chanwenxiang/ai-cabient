package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("file_attachment")
@Getter
@Setter
public class FileAttachment {

    @TableId(type = IdType.AUTO)
    private Long fileId;
    private String refType;
    private String refId;
    private String fileName;
    private Long fileSize;
    private String contentType;
    private String storagePath;
    private String storageBucket;
    private String contentSha256;
    private Long uploadedBy;
    private Instant createdAt;

}
