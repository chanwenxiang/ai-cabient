package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.SystemConfig;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SystemConfigMapper extends BaseTradeMapper<SystemConfig> {

    SystemConfig findByIdForUpdateRaw(@Param("configKey") String configKey);

    default Optional<SystemConfig> findByIdForUpdate(String configKey) {
        return Optional.ofNullable(findByIdForUpdateRaw(configKey));
    }
}
