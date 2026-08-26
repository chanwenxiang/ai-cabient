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

    SkuDelistReview _findBySkuIdForUpdateRaw(@Param("skuId") String skuId);

    default Optional<SkuDelistReview> findBySkuIdForUpdate(String skuId) {
        return Optional.ofNullable(_findBySkuIdForUpdateRaw(skuId));
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

    /** page 为 0-based。 */
    default Page<SkuDelistReview> searchPage(int page, int size) {
        return selectPage(new Page<>(page + 1L, size),
                Wrappers.<SkuDelistReview>lambdaQuery().orderByDesc(SkuDelistReview::getUpdatedAt));
    }
}
