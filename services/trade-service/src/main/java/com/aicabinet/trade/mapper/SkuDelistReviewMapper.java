package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.SkuDelistReview;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface SkuDelistReviewMapper extends BaseTradeMapper<SkuDelistReview> {

    default Optional<SkuDelistReview> findBySkuId(String skuId) {
        return Optional.ofNullable(selectOne(Wrappers.<SkuDelistReview>lambdaQuery()
                .eq(SkuDelistReview::getSkuId, skuId)));
    }

    default List<SkuDelistReview> findPending() {
        return selectList(Wrappers.<SkuDelistReview>lambdaQuery()
                .eq(SkuDelistReview::getReviewStatus, "PENDING")
                .orderByAsc(SkuDelistReview::getId));
    }
}
