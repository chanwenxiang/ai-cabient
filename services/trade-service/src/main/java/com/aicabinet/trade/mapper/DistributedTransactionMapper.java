package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.DistributedTransaction;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DistributedTransactionMapper extends BaseTradeMapper<DistributedTransaction> {

    default List<DistributedTransaction> findByStatusAndCreatedAtBefore(String status, Instant createdAt) {
    return selectList(Wrappers.<DistributedTransaction>lambdaQuery().eq(DistributedTransaction::getStatus, status).lt(DistributedTransaction::getCreatedAt, createdAt));
    }

    default List<DistributedTransaction> findByStatus(String status) {
    return selectList(Wrappers.<DistributedTransaction>lambdaQuery().eq(DistributedTransaction::getStatus, status));
    }

        List<DistributedTransaction> findRetryableTransactions();

}
