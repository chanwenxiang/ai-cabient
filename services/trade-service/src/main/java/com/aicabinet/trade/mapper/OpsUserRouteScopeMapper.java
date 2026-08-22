package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.OpsUserRouteScope;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface OpsUserRouteScopeMapper extends BaseTradeMapper<OpsUserRouteScope> {

    default List<OpsUserRouteScope> findByUserId(Long userId) {
        return selectList(Wrappers.<OpsUserRouteScope>lambdaQuery()
                .eq(OpsUserRouteScope::getUserId, userId));
    }

    default void deleteByUserId(Long userId) {
        delete(Wrappers.<OpsUserRouteScope>lambdaQuery().eq(OpsUserRouteScope::getUserId, userId));
    }
}
