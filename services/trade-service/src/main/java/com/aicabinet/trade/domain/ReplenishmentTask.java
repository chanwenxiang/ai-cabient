package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Data;

@Data
@TableName("replenishment_task")
public class ReplenishmentTask {

    @TableId(type = IdType.AUTO)
    private Long taskId;

    private Long routeId;

    private String deviceId;

    private Long assigneeUserId;

    private String status = "PENDING";

    private String notes;

    /** 客户端幂等键（V401，自主补货 clientRequestNo 契约）：同键重放返回原任务；NULL=旧客户端走 90s 短窗。 */
    private String clientRequestNo;

    private Instant completedAt;

    private Long outboundId;

    private Instant checkInAt;

    private Double checkInLat;

    private Double checkInLng;

    private Instant createdAt;

    /**
     * 「此刻正在补货」= IN_PROGRESS **且已签到**（人在柜边）。
     * <p>出库明细生成那一步也会把任务置成 IN_PROGRESS（{@code ReplenishmentService#generateLinesForTask}），
     * 但那时补货员还没到店；「挡消费者开门/结算」用的就是这条判据（{@code DeviceValidationService}），
     * 展示面必须与它同源——否则一条挂着没签到的旧任务会让柜机一直显示「补货中」，
     * 而 4 小时超时收口只扫已签到的行，这种状态永远不会自己消掉。
     */
    public static boolean isRestockingNow(ReplenishmentTask task) {
        return task != null && "IN_PROGRESS".equals(task.getStatus()) && task.getCheckInAt() != null;
    }

}
