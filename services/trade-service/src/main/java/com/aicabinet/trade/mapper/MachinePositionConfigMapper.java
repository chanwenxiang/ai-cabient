package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.MachinePositionConfig;
import org.apache.ibatis.annotations.Param;

public interface MachinePositionConfigMapper extends BaseTradeMapper<MachinePositionConfig> {

    /** R16-MA-175：行锁读（FOR UPDATE），saveConfig 的 insert-or-update 竞态收口。 */
    MachinePositionConfig findByIdForUpdateRaw(@Param("deviceId") String deviceId);
}
