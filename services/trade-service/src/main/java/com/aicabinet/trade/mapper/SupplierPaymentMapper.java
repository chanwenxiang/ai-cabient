package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.SupplierPayment;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SupplierPaymentMapper extends BaseTradeMapper<SupplierPayment> {

    default List<SupplierPayment> findByPayableIdOrderByCreatedAtAsc(Long payableId) {
        return selectList(Wrappers.<SupplierPayment>lambdaQuery()
                .eq(SupplierPayment::getPayableId, payableId)
                .orderByAsc(SupplierPayment::getCreatedAt)
                .orderByAsc(SupplierPayment::getPaymentId));
    }
}
