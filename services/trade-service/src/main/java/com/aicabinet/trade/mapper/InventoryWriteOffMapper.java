package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.InventoryWriteOff;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.util.Collection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface InventoryWriteOffMapper extends BaseTradeMapper<InventoryWriteOff> {

        long sumCostCentsSince(@Param("since") Instant since);


        long sumQuantitySince(@Param("since") Instant since);


        long sumCostCentsBetween(@Param("start") Instant start, @Param("end") Instant end);


        long sumCostCentsByDeviceIdsSince( @Param("deviceIds") Collection<String> deviceIds, @Param("since") Instant since);


        long sumQuantityByDeviceIdsSince( @Param("deviceIds") Collection<String> deviceIds, @Param("since") Instant since);


        long sumCostCentsByDeviceIdsBetween( @Param("deviceIds") Collection<String> deviceIds, @Param("start") Instant start, @Param("end") Instant end);


}
