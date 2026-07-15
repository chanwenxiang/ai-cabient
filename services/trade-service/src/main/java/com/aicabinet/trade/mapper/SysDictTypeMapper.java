package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.SysDictType;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysDictTypeMapper extends BaseTradeMapper<SysDictType> {

    default List<SysDictType> findAllByOrderBySortOrderAscDictTypeAsc() {
    return selectList(Wrappers.<SysDictType>lambdaQuery().orderByAsc(SysDictType::getSortOrder).orderByAsc(SysDictType::getDictType));
    }

}
