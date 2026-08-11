package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("sys_dict_data")
@Getter
@Setter
public class SysDictData {

    @TableId(type = IdType.AUTO)
    private Long dictDataId;

    private String dictType;

    private String dictValue;

    private String dictLabel;

    private int sortOrder = 0;

    private String status = "ACTIVE";

    private String remark;

    private Instant createdAt;

    private Instant updatedAt;

}
