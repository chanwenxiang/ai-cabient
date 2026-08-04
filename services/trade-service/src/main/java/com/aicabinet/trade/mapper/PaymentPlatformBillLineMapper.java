package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.PaymentPlatformBillLine;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PaymentPlatformBillLineMapper extends BaseTradeMapper<PaymentPlatformBillLine> {

    default List<PaymentPlatformBillLine> findByReconId(Long reconId) {
    return selectList(Wrappers.<PaymentPlatformBillLine>lambdaQuery().eq(PaymentPlatformBillLine::getReconId, reconId));
    }

    default void deleteByReconId(Long reconId) {
    delete(Wrappers.<PaymentPlatformBillLine>lambdaQuery().eq(PaymentPlatformBillLine::getReconId, reconId));
    }

}
