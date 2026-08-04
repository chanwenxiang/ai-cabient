package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.SkuVisionMapping;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SkuVisionMappingMapper extends BaseTradeMapper<SkuVisionMapping> {

    default List<SkuVisionMapping> findByMappingSource(String mappingSource) {
    return selectList(Wrappers.<SkuVisionMapping>lambdaQuery().eq(SkuVisionMapping::getMappingSource, mappingSource));
    }

}
