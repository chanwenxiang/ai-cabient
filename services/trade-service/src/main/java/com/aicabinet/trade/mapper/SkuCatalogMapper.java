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

    /** 条码批量点查（只读）：厂商标准商品条码 → 本平台候选 SKU（§5.5.3 对照解码用）。 */
    default java.util.List<SkuCatalog> findByBarcodes(java.util.Collection<String> barcodes) {
        if (barcodes == null || barcodes.isEmpty()) {
            return java.util.List.of();
        }
        return selectList(Wrappers.<SkuCatalog>lambdaQuery().in(SkuCatalog::getBarcode, barcodes));
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
        return search(q, status, category, operationScope, page, size, null);
    }

    /**
     * sortDir = asc/desc 时按添加时间（created_at）排序，sku_id 同向做稳定次序；
     * 空/其它值保持既有「sku_code, sku_id 升序」——非商品管理页的列表/下拉不受影响。
     */
    default Page<SkuCatalog> search(String q, String status, String category,
                                    String operationScope, int page, int size, String sortDir) {
        return search(q, status, category, null, operationScope, page, size, sortDir);
    }

    /**
     * categoryLabel 与 category 同时命中（IN），兼容历史行存类目标签、新行存字典键值的混用：
     * 只按选中项的键值 eq 会漏掉标签行，只按标签 eq 会漏掉新行。两者相同/为空时退化为 eq。
     */
    default Page<SkuCatalog> search(String q, String status, String category,
                                    String categoryLabel, String operationScope,
                                    int page, int size, String sortDir) {
        boolean asc = "asc".equalsIgnoreCase(sortDir);
        boolean desc = "desc".equalsIgnoreCase(sortDir);
        var query = Wrappers.<SkuCatalog>lambdaQuery();
        if (asc || desc) {
            query.orderBy(true, asc, SkuCatalog::getCreatedAt)
                    .orderBy(true, asc, SkuCatalog::getSkuId);
        } else {
            query.orderByAsc(SkuCatalog::getSkuCode)
                    .orderByAsc(SkuCatalog::getSkuId);
        }
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status.trim())) {
            query.eq(SkuCatalog::getStatus, status.trim().toUpperCase());
        }
        if (category != null && !category.isBlank()) {
            String code = category.trim();
            String label = categoryLabel == null ? null : categoryLabel.trim();
            if (label != null && !label.isBlank() && !label.equals(code)) {
                query.in(SkuCatalog::getCategory, code, label);
            } else {
                query.eq(SkuCatalog::getCategory, code);
            }
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
