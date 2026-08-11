package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("merchant")
@Getter
@Setter
public class Merchant {

    @TableId(type = IdType.INPUT)
    private String merchantId;

    private String merchantName;

    private String contactPhone;

    private int platformRateBps = 1000;

    private String wechatReceiverId;

    private String status = "ACTIVE";

    private String remark;

    private String alertContactName;

    private String alertContactPhone;

    private boolean allowMerchantPlanogramEdit = false;

    private boolean allowMerchantPricingEdit = false;

    /** 功能包：现场作业 */
    private boolean packFieldEnabled = true;

    /** 功能包：经营工具 */
    private boolean packBizEnabled = true;

    /** 功能包：团队与设置 */
    private boolean packTeamEnabled = true;

    private String parentMerchantId;

    private Instant createdAt;

    private Instant updatedAt;

}
