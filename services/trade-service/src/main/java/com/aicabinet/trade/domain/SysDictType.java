package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("sys_dict_type")
@Getter
@Setter
public class SysDictType {

    @TableId(type = IdType.INPUT)
    private String dictType;

    private String dictName;

    private String status = "ACTIVE";

    private String remark;

    private int sortOrder = 0;

    private Instant createdAt;

    private Instant updatedAt;

}
