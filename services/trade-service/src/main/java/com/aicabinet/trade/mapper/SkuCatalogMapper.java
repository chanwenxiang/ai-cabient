package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.SkuCatalog;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SkuCatalogMapper extends BaseTradeMapper<SkuCatalog> {

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
}
