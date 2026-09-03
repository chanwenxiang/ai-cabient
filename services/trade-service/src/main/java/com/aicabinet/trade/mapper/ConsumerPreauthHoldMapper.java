package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.ConsumerPreauthHold;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ConsumerPreauthHoldMapper extends BaseTradeMapper<ConsumerPreauthHold> {

    ConsumerPreauthHold findByIdForUpdateRaw(@Param("sessionId") String sessionId);

    default Optional<ConsumerPreauthHold> findByIdForUpdate(String sessionId) {
        return Optional.ofNullable(findByIdForUpdateRaw(sessionId));
    }
}
