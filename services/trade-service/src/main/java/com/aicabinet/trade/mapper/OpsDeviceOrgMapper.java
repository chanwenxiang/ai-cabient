package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.OpsDeviceOrg;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface OpsDeviceOrgMapper extends BaseTradeMapper<OpsDeviceOrg> {

    default List<OpsDeviceOrg> findByNodeId(Long nodeId) {
        return selectList(Wrappers.<OpsDeviceOrg>lambdaQuery()
                .eq(OpsDeviceOrg::getNodeId, nodeId));
    }

    default List<OpsDeviceOrg> findAll() {
        return selectList(Wrappers.<OpsDeviceOrg>lambdaQuery());
    }

    default void deleteByNodeId(Long nodeId) {
        delete(Wrappers.<OpsDeviceOrg>lambdaQuery()
                .eq(OpsDeviceOrg::getNodeId, nodeId));
    }

    default void deleteByDeviceIds(List<String> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            return;
        }
        delete(Wrappers.<OpsDeviceOrg>lambdaQuery()
                .in(OpsDeviceOrg::getDeviceId, deviceIds));
    }
}
