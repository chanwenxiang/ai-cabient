package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.CabinetOrderLine;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CabinetOrderLineMapper extends BaseTradeMapper<CabinetOrderLine> {

    default List<CabinetOrderLine> findByOrderId(String orderId) {
        return selectList(Wrappers.<CabinetOrderLine>lambdaQuery()
                .eq(CabinetOrderLine::getOrderId, orderId)
                .orderByAsc(CabinetOrderLine::getId));
    }

    default void deleteByOrderId(String orderId) {
        delete(Wrappers.<CabinetOrderLine>lambdaQuery().eq(CabinetOrderLine::getOrderId, orderId));
    }

    /** 订单商品件数（quantity 合计），用于列表「X 件」展示。 */
    default int sumQuantityByOrderId(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return 0;
        }
        return findByOrderId(orderId).stream().mapToInt(CabinetOrderLine::getQuantity).sum();
    }

    default Map<String, Integer> sumQuantityByOrderIds(Collection<String> orderIds) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (orderIds == null || orderIds.isEmpty()) {
            return out;
        }
        for (CabinetOrderLine line : selectList(Wrappers.<CabinetOrderLine>lambdaQuery()
                .in(CabinetOrderLine::getOrderId, orderIds))) {
            out.merge(line.getOrderId(), line.getQuantity(), Integer::sum);
        }
        return out;
    }

    long sumCogsSince(@Param("since") Instant since);

    long sumCogsTotal();

    List<LinkedHashMap<String, Object>> _sumSoldQtyBySkuSince(
            @Param("deviceId") String deviceId, @Param("since") Instant since);

    default List<Object[]> sumSoldQtyBySkuSince(String deviceId, Instant since) {
        return ColumnMapRows.toObjectRows(_sumSoldQtyBySkuSince(deviceId, since), 2);
    }

    long sumCogsBetween(@Param("start") Instant start, @Param("end") Instant end);

    List<LinkedHashMap<String, Object>> _skuBreakdownSince(@Param("since") Instant since);

    default List<Object[]> skuBreakdownSince(Instant since) {
        return ColumnMapRows.toObjectRows(_skuBreakdownSince(since), 5);
    }

    List<LinkedHashMap<String, Object>> _skuBreakdownByDevicesSince(
            @Param("deviceIds") Collection<String> deviceIds, @Param("since") Instant since);

    default List<Object[]> skuBreakdownByDevicesSince(Collection<String> deviceIds, Instant since) {
        return ColumnMapRows.toObjectRows(_skuBreakdownByDevicesSince(deviceIds, since), 5);
    }

    long sumCogsByDeviceIdsSince(
            @Param("deviceIds") Collection<String> deviceIds, @Param("since") Instant since);

    long sumRevenueByDeviceIdsSince(
            @Param("deviceIds") Collection<String> deviceIds, @Param("since") Instant since);

    long sumCogsByDeviceIdsBetween(
            @Param("deviceIds") Collection<String> deviceIds,
            @Param("start") Instant start,
            @Param("end") Instant end);
}
