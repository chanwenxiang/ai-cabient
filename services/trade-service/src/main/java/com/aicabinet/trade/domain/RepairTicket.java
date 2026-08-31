package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("repair_ticket")
@Getter
@Setter
public class RepairTicket {

    @TableId(type = IdType.AUTO)
    private Long ticketId;
    private String deviceId;
    /** 设备名称冗余 */
    private String deviceName;
    /** 商户 ID 冗余 */
    private String merchantId;
    /** 商户名称冗余 */
    private String merchantName;
    private String title;
    private String faultType;
    private String status;
    private String assignee;
    private String priority;
    private String remark;
    private Long createdBy;
    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
    private Instant closedAt;

    @TableLogic(value = "false", delval = "true")
    @TableField("is_deleted")
    private Boolean deleted;

}
