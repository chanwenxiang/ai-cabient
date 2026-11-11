package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.MachinePayeeBill;
import org.apache.ibatis.annotations.Param;

public interface MachinePayeeBillMapper extends BaseTradeMapper<MachinePayeeBill> {

    /** R16-MA-175：行锁读（FOR UPDATE），settle 的读后写竞态收口。 */
    MachinePayeeBill findByIdForUpdateRaw(@Param("billId") Long billId);
}
