package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.TransactionStep;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

@Mapper
public interface TransactionStepMapper extends BaseTradeMapper<TransactionStep> {

    default List<TransactionStep> findByTxIdOrderByStepOrder(String txId) {
    return selectList(Wrappers.<TransactionStep>lambdaQuery().eq(TransactionStep::getTxId, txId).orderByAsc(TransactionStep::getStepOrder));
    }

    default List<TransactionStep> findByTxIdAndStatus(String txId, String status) {
    return selectList(Wrappers.<TransactionStep>lambdaQuery().eq(TransactionStep::getTxId, txId).eq(TransactionStep::getStatus, status));
    }

}
