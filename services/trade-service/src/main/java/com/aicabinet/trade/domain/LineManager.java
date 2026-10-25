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
    /** 身份核验通过时间（运营侧二要素 vendor 核验或 C 端实名后由核验动作落值；CB-066 持有人一致性守卫前置） */
    private Instant identityVerifiedAt;
    private Instant createdAt;
    private Instant updatedAt;

}
