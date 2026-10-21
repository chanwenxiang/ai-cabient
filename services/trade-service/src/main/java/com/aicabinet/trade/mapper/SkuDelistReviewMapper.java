package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.SkuDelistReview;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface SkuDelistReviewMapper extends BaseTradeMapper<SkuDelistReview> {

    SkuDelistReview findBySkuIdForUpdateRaw(@Param("skuId") String skuId);

    default Optional<SkuDelistReview> findBySkuIdForUpdate(String skuId) {
        return Optional.ofNullable(findBySkuIdForUpdateRaw(skuId));
    }

    default Optional<SkuDelistReview> findBySkuId(String skuId) {
        return Optional.ofNullable(selectOne(Wrappers.<SkuDelistReview>lambdaQuery()
                .eq(SkuDelistReview::getSkuId, skuId)));
    }

    default List<SkuDelistReview> findPending() {
        return selectList(Wrappers.<SkuDelistReview>lambdaQuery()
                .eq(SkuDelistReview::getReviewStatus, "PENDING")
                .orderByAsc(SkuDelistReview::getId));
    }

    /**
     * page 为 0-based。
     * 排除已下架（sku_catalog.status=INACTIVE）SKU 的评审行：诊断只服务在售商品，
     * SQL 层过滤保证 total/分页正确（与 SkuDelistReviewService.list 的内存过滤口径一致）。
     */
    default Page<SkuDelistReview> searchPage(int page, int size) {
        return selectPage(new Page<>(page + 1L, size),
                Wrappers.<SkuDelistReview>lambdaQuery()
                        .notExists("SELECT 1 FROM sku_catalog c WHERE c.sku_id = sku_delist_review.sku_id AND c.status = 'INACTIVE'")
                        .orderByDesc(SkuDelistReview::getUpdatedAt));
    }
}
