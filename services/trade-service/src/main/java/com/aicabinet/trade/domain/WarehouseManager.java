package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 分仓负责人（多人）：一仓可配多名补货员，均具备该仓采购入库/盘点权限。
 *
 * <p>🔴 这是负责人集合的**唯一真相**；{@code warehouse.manager_user_id} 只是
 * 「主责人」派生列（= 最早绑定者），由 {@code WarehouseService} 写入时同步。
 * 判断「某人是否该仓负责人」必须查本表，不要再读 {@code warehouse.manager_user_id}。
 */
@TableName("warehouse_manager")
@Getter
@Setter
public class WarehouseManager {

    @TableId(type = IdType.AUTO)
    private Long managerId;

    private String warehouseId;

    private Long userId;

    /** 绑定时间：同仓多人时最早者为「主责人」。 */
    private Instant createdAt;
}
