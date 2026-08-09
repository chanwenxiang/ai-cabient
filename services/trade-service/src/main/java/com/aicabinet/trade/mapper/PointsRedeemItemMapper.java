package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.PointsRedeemItem;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface PointsRedeemItemMapper extends BaseTradeMapper<PointsRedeemItem> {

    default List<PointsRedeemItem> findActiveOrdered() {
        return selectList(Wrappers.<PointsRedeemItem>lambdaQuery()
                .eq(PointsRedeemItem::getStatus, "ACTIVE")
                .orderByAsc(PointsRedeemItem::getSortOrder)
                .orderByAsc(PointsRedeemItem::getItemId));
    }

    default List<PointsRedeemItem> findAllOrdered() {
        return selectList(Wrappers.<PointsRedeemItem>lambdaQuery()
                .orderByAsc(PointsRedeemItem::getSortOrder)
                .orderByAsc(PointsRedeemItem::getItemId));
    }
}
