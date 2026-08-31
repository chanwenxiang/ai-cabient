package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Data;

@Data
@TableName("user_info")
public class UserInfo {

    @TableId(type = IdType.INPUT)
    private Long userId;

    private String phoneNumber;

    private String name;

    private boolean verified;

    private String wxOpenId;

    private String passwordHash;

    /** ACTIVE / INACTIVE — 运营账号启停 */
    private String status = "ACTIVE";

    private boolean payscoreEnabled;

    private String payscoreContractId;

    private String alipayAgreementId;

    private String alipayUserId;

    private String payPreferredChannel = "BALANCE";

    /** 运营账号 TOTP 密钥（Base32，明文存储于服务端，仅用于校验动态码） */
    private String totpSecret;

    /** 是否已启用运营双因子认证 */
    private boolean totpEnabled;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    @TableLogic(value = "false", delval = "true")
    @TableField("is_deleted")
    private Boolean deleted;

}
