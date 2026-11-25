package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.PurchaseReceiveAllocation;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PurchaseReceiveAllocationMapper extends BaseTradeMapper<PurchaseReceiveAllocation> {
    default List<PurchaseReceiveAllocation> findByPurchaseLineIdForUpdate(Long purchaseLineId) {
        return selectList(Wrappers.<PurchaseReceiveAllocation>lambdaQuery()
                .eq(PurchaseReceiveAllocation::getPurchaseLineId, purchaseLineId)
                .orderByAsc(PurchaseReceiveAllocation::getAllocationId)
                .last("FOR UPDATE"));
    }
}
