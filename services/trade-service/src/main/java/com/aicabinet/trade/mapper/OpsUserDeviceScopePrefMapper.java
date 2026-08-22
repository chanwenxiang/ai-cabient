package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.OpsUserDeviceScopePref;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OpsUserDeviceScopePrefMapper extends BaseTradeMapper<OpsUserDeviceScopePref> {

    OpsUserDeviceScopePref _findByIdForUpdateRaw(@Param("userId") Long userId);

    default Optional<OpsUserDeviceScopePref> findByIdForUpdate(Long userId) {
        return Optional.ofNullable(_findByIdForUpdateRaw(userId));
    }
}
