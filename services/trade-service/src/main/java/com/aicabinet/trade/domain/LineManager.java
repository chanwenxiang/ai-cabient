package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("line_manager")
@Getter
@Setter
public class LineManager {

    @TableId(type = IdType.AUTO)
    private Long managerId;
    private String managerName;
    private String phone;
    private String status;
    private String wxOpenid;
    private Long userId;
    private String orgName;
    private Integer commissionRateBps;
    private Integer commissionFixedCents;
    private Instant createdAt;
    private Instant updatedAt;

}
