package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.SkuVisionMapping;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SkuVisionMappingMapper extends BaseTradeMapper<SkuVisionMapping> {

    SkuVisionMapping _findByIdForUpdateRaw(@Param("className") String className);

    default Optional<SkuVisionMapping> findByIdForUpdate(String className) {
        return Optional.ofNullable(_findByIdForUpdateRaw(className));
    }

    default List<SkuVisionMapping> findByMappingSource(String mappingSource) {
    return selectList(Wrappers.<SkuVisionMapping>lambdaQuery().eq(SkuVisionMapping::getMappingSource, mappingSource));
    }

}
