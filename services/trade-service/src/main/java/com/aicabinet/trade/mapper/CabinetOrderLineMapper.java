package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.CabinetOrderLine;
import java.time.Instant;
import java.util.ArrayList;
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

    long sumCogsSince(@Param("since") Instant since);

    long sumCogsTotal();

    List<LinkedHashMap<String, Object>> _sumSoldQtyBySkuSince(
            @Param("deviceId") String deviceId, @Param("since") Instant since);

    default List<Object[]> sumSoldQtyBySkuSince(String deviceId, Instant since) {
        return toObjectRows(_sumSoldQtyBySkuSince(deviceId, since));
    }

    long sumCogsBetween(@Param("start") Instant start, @Param("end") Instant end);

    List<LinkedHashMap<String, Object>> _skuBreakdownSince(@Param("since") Instant since);

    default List<Object[]> skuBreakdownSince(Instant since) {
        return toObjectRows(_skuBreakdownSince(since));
    }

    List<LinkedHashMap<String, Object>> _skuBreakdownByDevicesSince(
            @Param("deviceIds") Collection<String> deviceIds, @Param("since") Instant since);

    default List<Object[]> skuBreakdownByDevicesSince(Collection<String> deviceIds, Instant since) {
        return toObjectRows(_skuBreakdownByDevicesSince(deviceIds, since));
    }

    long sumCogsByDeviceIdsSince(
            @Param("deviceIds") Collection<String> deviceIds, @Param("since") Instant since);

    long sumRevenueByDeviceIdsSince(
            @Param("deviceIds") Collection<String> deviceIds, @Param("since") Instant since);

    long sumCogsByDeviceIdsBetween(
            @Param("deviceIds") Collection<String> deviceIds,
            @Param("start") Instant start,
            @Param("end") Instant end);

    private static List<Object[]> toObjectRows(List<LinkedHashMap<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<Object[]> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            out.add(row.values().toArray());
        }
        return out;
    }
}
