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

    /** 批量取订单行（列表摘要用，避免逐单 N+1 查询）。 */
    default List<CabinetOrderLine> findByOrderIds(Collection<String> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<CabinetOrderLine>lambdaQuery()
                .in(CabinetOrderLine::getOrderId, orderIds)
                .orderByAsc(CabinetOrderLine::getId));
    }

    long sumCogsSince(@Param("since") Instant since);

    long sumCogsTotal();

    List<LinkedHashMap<String, Object>> selectSumSoldQtyBySkuSince(
            @Param("deviceId") String deviceId, @Param("since") Instant since);

    default List<Object[]> sumSoldQtyBySkuSince(String deviceId, Instant since) {
        return ColumnMapRows.toObjectRows(selectSumSoldQtyBySkuSince(deviceId, since), 2);
    }

    List<LinkedHashMap<String, Object>> selectSumSoldQtyAllSince(@Param("since") Instant since);

    default List<Object[]> sumSoldQtyAllSince(Instant since) {
        return ColumnMapRows.toObjectRows(selectSumSoldQtyAllSince(since), 2);
    }

    /** 按 SKU + 自然日（Asia/Shanghai）聚合销量，供采购建议趋势预测使用。 */
    List<LinkedHashMap<String, Object>> selectSoldQtyDailySince(@Param("since") Instant since);

    default List<Object[]> soldQtyDailySince(Instant since) {
        return ColumnMapRows.toObjectRows(selectSoldQtyDailySince(since), 3);
    }

    /** 按货道聚合销量/营收（货道热区），仅统计有 slot_id 的订单行。 */
    List<LinkedHashMap<String, Object>> selectSlotBreakdownByDeviceSince(
            @Param("deviceId") String deviceId, @Param("since") Instant since);

    default List<Object[]> slotBreakdownByDeviceSince(String deviceId, Instant since) {
        return ColumnMapRows.toObjectRows(selectSlotBreakdownByDeviceSince(deviceId, since), 5);
    }

    long sumCogsBetween(@Param("start") Instant start, @Param("end") Instant end);

    List<LinkedHashMap<String, Object>> selectSkuBreakdownSince(@Param("since") Instant since);

    default List<Object[]> skuBreakdownSince(Instant since) {
        return ColumnMapRows.toObjectRows(selectSkuBreakdownSince(since), 6);
    }

    List<LinkedHashMap<String, Object>> selectSkuBreakdownBetween(
            @Param("start") Instant start, @Param("end") Instant end);

    default List<Object[]> skuBreakdownBetween(Instant start, Instant end) {
        return ColumnMapRows.toObjectRows(selectSkuBreakdownBetween(start, end), 6);
    }

    List<LinkedHashMap<String, Object>> selectSkuBreakdownByDevicesSince(
            @Param("deviceIds") Collection<String> deviceIds, @Param("since") Instant since);

    default List<Object[]> skuBreakdownByDevicesSince(Collection<String> deviceIds, Instant since) {
        return ColumnMapRows.toObjectRows(selectSkuBreakdownByDevicesSince(deviceIds, since), 6);
    }

    List<LinkedHashMap<String, Object>> selectSkuBreakdownByDevicesBetween(
            @Param("deviceIds") Collection<String> deviceIds,
            @Param("start") Instant start,
            @Param("end") Instant end);

    default List<Object[]> skuBreakdownByDevicesBetween(Collection<String> deviceIds, Instant start, Instant end) {
        return ColumnMapRows.toObjectRows(selectSkuBreakdownByDevicesBetween(deviceIds, start, end), 6);
    }

    List<LinkedHashMap<String, Object>> selectDeviceBreakdownBetween(
            @Param("deviceIds") Collection<String> deviceIds,
            @Param("start") Instant start,
            @Param("end") Instant end);

    default List<Object[]> deviceBreakdownBetween(Collection<String> deviceIds, Instant start, Instant end) {
        return ColumnMapRows.toObjectRows(selectDeviceBreakdownBetween(deviceIds, start, end), 5);
    }

    long sumCogsByDeviceIdsSince(
            @Param("deviceIds") Collection<String> deviceIds, @Param("since") Instant since);

    long sumRevenueByDeviceIdsSince(
            @Param("deviceIds") Collection<String> deviceIds, @Param("since") Instant since);

    long sumCogsByDeviceIdsBetween(
            @Param("deviceIds") Collection<String> deviceIds,
            @Param("start") Instant start,
            @Param("end") Instant end);

    long sumRevenueByDeviceIdsBetween(
            @Param("deviceIds") Collection<String> deviceIds,
            @Param("start") Instant start,
            @Param("end") Instant end);
}
