package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.PointsRedeemItem;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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

    /** 原子扣减可兑库存，返回受影响行数（0 表示已兑完或已下架）。 */
    int tryClaimStock(@Param("itemId") Long itemId);
}
