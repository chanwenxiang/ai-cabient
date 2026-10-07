package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
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

    /**
     * V321 法人姓名。**提现门禁必填项之一**。
     *
     * <p>⚠️ 存了**不等于**审过：系统没有「资质审核状态」概念（未取证），
     * 内容真伪由人工运营负责。⇒ 不要给这个字段加「已认证」语义的接口。
     */
    private String legalPerson;

    /**
     * V321 营业执照地址（存**地址**不是文件本体，文件走 FileAttachment）。
     * **提现门禁必填项之一**。
     */
    private String businessLicenseUrl;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    @TableLogic(value = "false", delval = "true")
    @TableField("is_deleted")
    private Boolean deleted;

}
