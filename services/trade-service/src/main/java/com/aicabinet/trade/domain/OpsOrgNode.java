package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("ops_org_node")
@Getter
@Setter
public class OpsOrgNode {

    @TableId(type = IdType.AUTO)
    private Long nodeId;
    private Long parentId;
    private String name;
    private String nodeType = "BRANCH";
    private int sortOrder;
    private boolean enabled = true;
    private Instant createdAt;
    private Instant updatedAt;

}
