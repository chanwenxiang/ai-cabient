package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.SkuCatalog;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

@Mapper
public interface SkuCatalogMapper extends BaseTradeMapper<SkuCatalog> {

    SkuCatalog findByIdForUpdateRaw(@Param("skuId") String skuId);

    default Optional<SkuCatalog> findByIdForUpdate(String skuId) {
        return Optional.ofNullable(findByIdForUpdateRaw(skuId));
    }

    default java.util.List<SkuCatalog> findAllByOrderBySkuIdAsc() {
        return selectList(Wrappers.<SkuCatalog>lambdaQuery().orderByAsc(SkuCatalog::getSkuId));
    }

    default java.util.List<SkuCatalog> findAllByOrderBySkuCodeAsc() {
        return selectList(Wrappers.<SkuCatalog>lambdaQuery().orderByAsc(SkuCatalog::getSkuCode).orderByAsc(SkuCatalog::getSkuId));
    }

    @Select("SELECT nextval('sku_catalog_sku_code_seq')")
    long nextSkuCode();

    default boolean existsByBarcode(String barcode, String excludeSkuId) {
        if (barcode == null || barcode.isBlank()) {
            return false;
        }
        var q = Wrappers.<SkuCatalog>lambdaQuery().eq(SkuCatalog::getBarcode, barcode.trim());
        if (excludeSkuId != null && !excludeSkuId.isBlank()) {
            q.ne(SkuCatalog::getSkuId, excludeSkuId.trim());
        }
        return selectCount(q) > 0;
    }

    default boolean existsByNameAndSpec(String skuName, String spec, String excludeSkuId) {
        if (skuName == null || skuName.isBlank()) {
            return false;
        }
        String name = skuName.trim().toLowerCase();
        String normalizedSpec = spec == null ? "" : spec.trim().toLowerCase();
        var q = Wrappers.<SkuCatalog>lambdaQuery()
                .apply("LOWER(TRIM(sku_name)) = {0}", name)
                .apply("LOWER(TRIM(COALESCE(spec, ''))) = {0}", normalizedSpec);
        if (excludeSkuId != null && !excludeSkuId.isBlank()) {
            q.ne(SkuCatalog::getSkuId, excludeSkuId.trim());
        }
        return selectCount(q) > 0;
    }

    default boolean existsBySkuCode(long skuCode, String excludeSkuId) {
        var q = Wrappers.<SkuCatalog>lambdaQuery().eq(SkuCatalog::getSkuCode, skuCode);
        if (excludeSkuId != null && !excludeSkuId.isBlank()) {
            q.ne(SkuCatalog::getSkuId, excludeSkuId.trim());
        }
        return selectCount(q) > 0;
    }

    /**
     * page 为 0-based；status 传 ACTIVE 等，ALL 表示不限；
     * operationScope 传 DIRECT/NON_DIRECT（V349 字典 sku_operation_scope），
     * UNCLASSIFIED=未分类（NULL 桶，CB-036），ALL/空表示不限。
     */
    default Page<SkuCatalog> search(String q, String status, String category,
                                    String operationScope, int page, int size) {
        var query = Wrappers.<SkuCatalog>lambdaQuery()
                .orderByAsc(SkuCatalog::getSkuCode)
                .orderByAsc(SkuCatalog::getSkuId);
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status.trim())) {
            query.eq(SkuCatalog::getStatus, status.trim().toUpperCase());
        }
        if (category != null && !category.isBlank()) {
            query.eq(SkuCatalog::getCategory, category.trim());
        }
        if (operationScope != null && !operationScope.isBlank()
                && !"ALL".equalsIgnoreCase(operationScope.trim())) {
            if ("UNCLASSIFIED".equalsIgnoreCase(operationScope.trim())) {
                query.isNull(SkuCatalog::getOperationScope);
            } else {
                query.eq(SkuCatalog::getOperationScope, operationScope.trim().toUpperCase());
            }
        }
        if (q != null && !q.isBlank()) {
            String kw = q.trim();
            query.and(w -> {
                w.like(SkuCatalog::getSkuId, kw)
                        .or().like(SkuCatalog::getSkuName, kw)
                        .or().like(SkuCatalog::getBarcode, kw)
                        .or().like(SkuCatalog::getBrand, kw);
                try {
                    long code = Long.parseLong(kw);
                    w.or().eq(SkuCatalog::getSkuCode, code);
                } catch (NumberFormatException ignored) {
                    // not a numeric code
                }
            });
        }
        return selectPage(new Page<>(page + 1L, size), query);
    }

}
