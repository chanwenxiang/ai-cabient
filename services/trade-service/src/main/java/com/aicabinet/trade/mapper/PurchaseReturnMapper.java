package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.PurchaseReturn;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface PurchaseReturnMapper extends BaseTradeMapper<PurchaseReturn> {

    default List<PurchaseReturn> findAllByOrderByCreatedAtDesc() {
        return selectList(Wrappers.<PurchaseReturn>lambdaQuery().orderByDesc(PurchaseReturn::getCreatedAt));
    }
}
