package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@TableName("merchant_role_template")
@Getter
@Setter
public class MerchantRoleTemplate {
    @TableId(type = IdType.INPUT)
    private String templateKey;
    private String templateName;
    private String description;
    private String permissionHint;
    private int sortOrder;

}
