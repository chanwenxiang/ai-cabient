package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("ops_role")
@Getter
@Setter
public class OpsRole {

    @TableId(type = IdType.AUTO)
    private Long roleId;

    private String roleKey;

    private String roleName;

    private String status = "ACTIVE";

    private String remark;

    private Instant createdAt;

}
