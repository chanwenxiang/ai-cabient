package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.JiangyiTrainingTicket;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;
import java.util.Optional;

@Mapper
public interface JiangyiTrainingTicketMapper extends BaseTradeMapper<JiangyiTrainingTicket> {

    default Optional<JiangyiTrainingTicket> byFinishNotifyId(String finishNotifyId) {
        return Optional.ofNullable(selectOne(Wrappers.<JiangyiTrainingTicket>lambdaQuery()
                .eq(JiangyiTrainingTicket::getFinishNotifyId, finishNotifyId)));
    }

    /** 同 SKU 未完成 ticket（startTraining 幂等判定）。 */
    default Optional<JiangyiTrainingTicket> findPendingBySku(String skuId) {
        return Optional.ofNullable(selectOne(Wrappers.<JiangyiTrainingTicket>lambdaQuery()
                .eq(JiangyiTrainingTicket::getSkuId, skuId)
                .eq(JiangyiTrainingTicket::getStatus, "PENDING")
                .orderByDesc(JiangyiTrainingTicket::getCreatedAt)
                .last("LIMIT 1")));
    }

    /** 回执终态（仅 PENDING → FINISHED/FAILED，一次性消费防重放）。 */
    default int finish(String finishNotifyId, boolean success, Instant at) {
        return update(null, Wrappers.<JiangyiTrainingTicket>lambdaUpdate()
                .set(JiangyiTrainingTicket::getStatus, success ? "FINISHED" : "FAILED")
                .set(JiangyiTrainingTicket::getFinishedAt, at)
                .eq(JiangyiTrainingTicket::getFinishNotifyId, finishNotifyId)
                .eq(JiangyiTrainingTicket::getStatus, "PENDING"));
    }
}
