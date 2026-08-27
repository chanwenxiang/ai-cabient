package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.SkuVisionMapping;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SkuVisionMappingMapper extends BaseTradeMapper<SkuVisionMapping> {

    SkuVisionMapping findByIdForUpdateRaw(@Param("className") String className);

    default Optional<SkuVisionMapping> findByIdForUpdate(String className) {
        return Optional.ofNullable(findByIdForUpdateRaw(className));
    }

    default List<SkuVisionMapping> findByMappingSource(String mappingSource) {
    return selectList(Wrappers.<SkuVisionMapping>lambdaQuery().eq(SkuVisionMapping::getMappingSource, mappingSource));
    }

    /** page 为 0-based；skuIds 非空时按 className/skuId 或 skuId in 匹配。 */
    default Page<SkuVisionMapping> searchPage(String keyword, Collection<String> skuIds, int page, int size) {
        var q = Wrappers.<SkuVisionMapping>lambdaQuery().orderByAsc(SkuVisionMapping::getClassName);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            if (skuIds != null && !skuIds.isEmpty()) {
                q.and(w -> w.like(SkuVisionMapping::getClassName, kw)
                        .or().like(SkuVisionMapping::getSkuId, kw)
                        .or().in(SkuVisionMapping::getSkuId, skuIds));
            } else {
                q.and(w -> w.like(SkuVisionMapping::getClassName, kw).or().like(SkuVisionMapping::getSkuId, kw));
            }
        }
        return selectPage(new Page<>(page + 1L, size), q);
    }

}
