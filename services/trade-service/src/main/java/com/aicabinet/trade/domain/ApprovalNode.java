package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@TableName("approval_node")
@Getter
@Setter
public class ApprovalNode {
    @TableId(type = IdType.AUTO)
    private Long nodeId;
    private Long defId;
    private Integer seq;
    private String nodeName;
    private String assigneeType;
    private String assigneeValue;
    private String passRule = "ANY";
}
