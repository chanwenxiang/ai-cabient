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

    SkuCatalog _findByIdForUpdateRaw(@Param("skuId") String skuId);

    default Optional<SkuCatalog> findByIdForUpdate(String skuId) {
        return Optional.ofNullable(_findByIdForUpdateRaw(skuId));
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

    default boolean existsBySkuName(String skuName, String excludeSkuId) {
        if (skuName == null || skuName.isBlank()) {
            return false;
        }
        var q = Wrappers.<SkuCatalog>lambdaQuery().eq(SkuCatalog::getSkuName, skuName.trim());
        if (excludeSkuId != null && !excludeSkuId.isBlank()) {
            q.ne(SkuCatalog::getSkuId, excludeSkuId.trim());
        }
        return selectCount(q) > 0;
    }

    /** page 为 0-based；status 传 ACTIVE 等，ALL 表示不限。 */
    default Page<SkuCatalog> search(String q, String status, String category, int page, int size) {
        return search(q, status, category, null, page, size);
    }

    /** page 为 0-based；enrollmentStatus 传视觉入驻状态筛选。 */
    default Page<SkuCatalog> search(String q, String status, String category, String enrollmentStatus, int page, int size) {
        var query = Wrappers.<SkuCatalog>lambdaQuery()
                .orderByAsc(SkuCatalog::getSkuCode)
                .orderByAsc(SkuCatalog::getSkuId);
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status.trim())) {
            query.eq(SkuCatalog::getStatus, status.trim().toUpperCase());
        }
        if (category != null && !category.isBlank()) {
            query.eq(SkuCatalog::getCategory, category.trim());
        }
        if (enrollmentStatus != null && !enrollmentStatus.isBlank()) {
            query.eq(SkuCatalog::getVisionEnrollmentStatus, enrollmentStatus.trim().toUpperCase());
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
