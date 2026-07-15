package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.FranchiseDevice;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

@Mapper
public interface FranchiseDeviceMapper extends BaseTradeMapper<FranchiseDevice> {

    default List<FranchiseDevice> findByFranchiseId(Long franchiseId) {
    return selectList(Wrappers.<FranchiseDevice>lambdaQuery().eq(FranchiseDevice::getFranchiseId, franchiseId));
    }

    default Optional<FranchiseDevice> findByDeviceIdAndStatus(String deviceId, String status) {
    return Optional.ofNullable(selectOne(Wrappers.<FranchiseDevice>lambdaQuery().eq(FranchiseDevice::getDeviceId, deviceId).eq(FranchiseDevice::getStatus, status)));
    }

    default List<FranchiseDevice> findByStatus(String status) {
    return selectList(Wrappers.<FranchiseDevice>lambdaQuery().eq(FranchiseDevice::getStatus, status));
    }

}
