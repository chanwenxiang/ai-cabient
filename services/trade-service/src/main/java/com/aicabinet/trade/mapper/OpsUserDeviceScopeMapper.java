package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.OpsUserDeviceScope;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.List;

public interface OpsUserDeviceScopeMapper extends BaseTradeMapper<OpsUserDeviceScope> {
    default List<OpsUserDeviceScope> findByUserId(Long userId) {
        return selectList(Wrappers.<OpsUserDeviceScope>lambdaQuery().eq(OpsUserDeviceScope::getUserId, userId));
    }

    default void deleteByUserId(Long userId) {
        delete(Wrappers.<OpsUserDeviceScope>lambdaQuery().eq(OpsUserDeviceScope::getUserId, userId));
    }
}
