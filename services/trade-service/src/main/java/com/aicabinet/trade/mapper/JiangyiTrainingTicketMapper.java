package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.JiangyiTrainingTicket;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Mapper
public interface JiangyiTrainingTicketMapper extends BaseTradeMapper<JiangyiTrainingTicket> {

    /**
     * 同一回执 id 下的全部票据。批量触发一次 §4.4.3 调用覆盖多个 SKU，票据共享
     * finishNotifyId（V407 起不再唯一），回调必须扇出到每一张。
     */
    default List<JiangyiTrainingTicket> listByFinishNotifyId(String finishNotifyId) {
        return selectList(Wrappers.<JiangyiTrainingTicket>lambdaQuery()
                .eq(JiangyiTrainingTicket::getFinishNotifyId, finishNotifyId)
                .orderByAsc(JiangyiTrainingTicket::getId));
    }

    /** 设备最近一次学习凭据（进度面板显示「回调到了没有」的唯一锚点）。 */
    default Optional<JiangyiTrainingTicket> latestByDevice(String deviceId) {
        return Optional.ofNullable(selectOne(Wrappers.<JiangyiTrainingTicket>lambdaQuery()
                .eq(JiangyiTrainingTicket::getDeviceId, deviceId)
                .orderByDesc(JiangyiTrainingTicket::getCreatedAt)
                .orderByDesc(JiangyiTrainingTicket::getId)
                .last("LIMIT 1")));
    }

    /** 全局最近学习凭据（学习库「回调到了没有」面板；跨柜机按创建时间倒序）。 */
    default List<JiangyiTrainingTicket> listRecent(int limit) {
        int lim = Math.max(1, Math.min(limit, 100));
        return selectList(Wrappers.<JiangyiTrainingTicket>lambdaQuery()
                .orderByDesc(JiangyiTrainingTicket::getCreatedAt)
                .orderByDesc(JiangyiTrainingTicket::getId)
                .last("LIMIT " + lim));
    }

    /** 同 SKU 未完成 ticket（startTraining 幂等判定）。 */
    default Optional<JiangyiTrainingTicket> findPendingBySku(String skuId) {
        return Optional.ofNullable(selectOne(Wrappers.<JiangyiTrainingTicket>lambdaQuery()
                .eq(JiangyiTrainingTicket::getSkuId, skuId)
                .eq(JiangyiTrainingTicket::getStatus, "PENDING")
                .orderByDesc(JiangyiTrainingTicket::getCreatedAt)
                .last("LIMIT 1")));
    }

    /** 批量触发的在途拦截：任一 SKU 已有 PENDING 票据就不许再提一次。 */
    default List<JiangyiTrainingTicket> listPendingBySkus(Collection<String> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<JiangyiTrainingTicket>lambdaQuery()
                .in(JiangyiTrainingTicket::getSkuId, skuIds)
                .eq(JiangyiTrainingTicket::getStatus, "PENDING"));
    }

    /** 回执终态（仅本人 PENDING → FINISHED/FAILED，一次性消费防重放）。 */
    default int finishById(Long ticketId, boolean success, Instant at) {
        return update(null, Wrappers.<JiangyiTrainingTicket>lambdaUpdate()
                .set(JiangyiTrainingTicket::getStatus, success ? "FINISHED" : "FAILED")
                .set(JiangyiTrainingTicket::getFinishedAt, at)
                .eq(JiangyiTrainingTicket::getId, ticketId)
                .eq(JiangyiTrainingTicket::getStatus, "PENDING"));
    }
}
