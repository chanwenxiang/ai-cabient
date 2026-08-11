package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("user_feedback")
@Getter
@Setter
public class UserFeedback {

    @TableId(type = IdType.AUTO)
    private Long feedbackId;
    private Long userId;
    private String feedbackType;
    private String content;
    private String contactInfo;
    private String deviceId;
    private String sessionId;
    private String images;
    private Integer rating;
    private String status = "PENDING";
    private Long handlerId;
    private String reply;
    private Instant handledAt;
    private Instant createdAt;

}
