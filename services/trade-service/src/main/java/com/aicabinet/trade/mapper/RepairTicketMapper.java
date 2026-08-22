package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.RepairTicket;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RepairTicketMapper extends BaseTradeMapper<RepairTicket> {

    RepairTicket _findByIdForUpdateRaw(@Param("ticketId") Long ticketId);

    default Optional<RepairTicket> findByIdForUpdate(Long ticketId) {
        return Optional.ofNullable(_findByIdForUpdateRaw(ticketId));
    }
}
