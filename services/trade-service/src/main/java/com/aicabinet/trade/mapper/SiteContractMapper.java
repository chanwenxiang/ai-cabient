package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.SiteContract;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface SiteContractMapper extends BaseTradeMapper<SiteContract> {

    default List<SiteContract> findAllOrderByUpdatedDesc() {
        return selectList(Wrappers.<SiteContract>lambdaQuery()
                .orderByDesc(SiteContract::getUpdatedAt));
    }

    default Optional<SiteContract> findByDeviceId(String deviceId) {
        return Optional.ofNullable(selectOne(Wrappers.<SiteContract>lambdaQuery()
                .eq(SiteContract::getDeviceId, deviceId)));
    }
}
