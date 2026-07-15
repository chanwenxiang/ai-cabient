package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.Supplier;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SupplierMapper extends BaseTradeMapper<Supplier> {

    default List<Supplier> findAllByOrderByCreatedAtDesc() {
    return selectList(Wrappers.<Supplier>lambdaQuery().orderByDesc(Supplier::getCreatedAt));
    }

}
