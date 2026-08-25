package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@TableName("ops_department")
@Getter
@Setter
public class OpsDepartment {
    @TableId(type = IdType.AUTO)
    private Long deptId;
    private String deptKey;
    private String deptName;
    private Long parentId;
    private Integer sortOrder = 0;
    private String status = "ACTIVE";
    private String remark;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
