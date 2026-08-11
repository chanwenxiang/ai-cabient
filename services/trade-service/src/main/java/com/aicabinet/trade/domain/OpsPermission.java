package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import lombok.Getter;
import lombok.Setter;

@TableName("ops_permission")
@Getter
@Setter
public class OpsPermission {

    @TableId(type = IdType.AUTO)
    private Long permissionId;

    private Long parentId = 0L;

    private String permCode;

    private String permName;

    private String permType = "M";

    private String path;

    private int sortOrder;

    private String status = "ACTIVE";

}
