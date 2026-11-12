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

    /**
     * 商户账单（图4 对照，2026-10-15）：按日聚合 销售额/销售量/成本。
     * c0=day(yyyy-MM-DD)，c1=qty，c2=salesCents，c3=cogsCents，c4=orderCount。
     * deviceIds：null=不过滤；空集合=无可见设备（上层短路）；单元素=指定设备。
     */
    /**
     * v1 活动核销限制（CB-082）：统计某活动已核销的订单数；userId 非空时只算该用户。
     * 订单行带 activity_id 即视为该活动已核销。
     *
     * <p>R20-13：必须排除 {@code PENDING}/{@code CANCELLED}。旧实现只要行上有 activity_id 就计数，
     * 于是「下单未支付」「下单后取消」都白占名额：user_limit=1 的用户只要留着一张未支付单，
     * 就再也享受不到该活动价（且放开名额要靠他自己去取消，运营无从干预）。
     * 未支付单在关门超时后才会转 CANCELLED，中间那段窗口正是最常见的自助结算期。
     */
    @org.apache.ibatis.annotations.Select("""
        SELECT COUNT(DISTINCT l.order_id) FROM cabinet_order_line l
        JOIN cabinet_order o ON o.order_id = l.order_id
        WHERE l.activity_id = #{activityId}
          AND o.status NOT IN ('PENDING', 'CANCELLED')
          AND (#{userId} IS NULL OR o.user_id = #{userId})
        """)
    long countOrdersForActivity(@Param("activityId") Long activityId, @Param("userId") Long userId);

    /**
     * 同口径计数，但排除指定订单：争议改单重算时本单已带 activity_id，
     * 不排除会把自己算进 user_limit（user_limit=1 时反而丢失活动价）。
     */
    @org.apache.ibatis.annotations.Select("""
        SELECT COUNT(DISTINCT l.order_id) FROM cabinet_order_line l
        JOIN cabinet_order o ON o.order_id = l.order_id
        WHERE l.activity_id = #{activityId}
          AND o.status NOT IN ('PENDING', 'CANCELLED')
          AND (#{userId} IS NULL OR o.user_id = #{userId})
          AND l.order_id <> #{excludeOrderId}
        """)
    long countOrdersForActivityExcluding(@Param("activityId") Long activityId,
                                         @Param("userId") Long userId,
                                         @Param("excludeOrderId") String excludeOrderId);

    List<LinkedHashMap<String, Object>> selectMerchantBillSalesByDay(
            @Param("merchantIds") Collection<String> merchantIds,
            @Param("start") Instant start,
            @Param("end") Instant end,
            @Param("deviceIds") Collection<String> deviceIds);

    default List<Object[]> merchantBillSalesByDay(
            Collection<String> merchantIds, Instant start, Instant end, Collection<String> deviceIds) {
        return ColumnMapRows.toObjectRows(
                selectMerchantBillSalesByDay(merchantIds, start, end, deviceIds), 5);
    }

    /**
     * 商户账单：按日聚合退款（按订单 pay_channel 归桶）。
     * c0=day，c1=instantRefundCents（非余额渠道），c2=balanceRefundCents，c3=refundOrderCount。
     */
    List<LinkedHashMap<String, Object>> selectMerchantBillRefundsByDay(
            @Param("merchantIds") Collection<String> merchantIds,
            @Param("start") Instant start,
            @Param("end") Instant end,
            @Param("deviceIds") Collection<String> deviceIds);

    default List<Object[]> merchantBillRefundsByDay(
            Collection<String> merchantIds, Instant start, Instant end, Collection<String> deviceIds) {
        return ColumnMapRows.toObjectRows(
                selectMerchantBillRefundsByDay(merchantIds, start, end, deviceIds), 4);
    }

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

    long sumCogsBetween(@Param("start") Instant start, @Param("end") Instant end);

    List<LinkedHashMap<String, Object>> selectSkuBreakdownSince(@Param("since") Instant since);

    default List<Object[]> skuBreakdownSince(Instant since) {
        return ColumnMapRows.toObjectRows(selectSkuBreakdownSince(since), 6);
    }

    List<LinkedHashMap<String, Object>> selectSkuBreakdownBetween(
            @Param("start") Instant start, @Param("end") Instant end);

    default List<Object[]> skuBreakdownBetween(Instant start, Instant end) {
        return ColumnMapRows.toObjectRows(selectSkuBreakdownBetween(start, end), 8);
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
        return ColumnMapRows.toObjectRows(selectSkuBreakdownByDevicesBetween(deviceIds, start, end), 8);
    }

    List<LinkedHashMap<String, Object>> selectDeviceBreakdownBetween(
            @Param("deviceIds") Collection<String> deviceIds,
            @Param("start") Instant start,
            @Param("end") Instant end);

    default List<Object[]> deviceBreakdownBetween(Collection<String> deviceIds, Instant start, Instant end) {
        return ColumnMapRows.toObjectRows(selectDeviceBreakdownBetween(deviceIds, start, end), 7);
    }

    List<LinkedHashMap<String, Object>> selectChannelBreakdownBetween(
            @Param("deviceIds") Collection<String> deviceIds,
            @Param("start") Instant start,
            @Param("end") Instant end);

    default List<Object[]> channelBreakdownBetween(Collection<String> deviceIds, Instant start, Instant end) {
        return ColumnMapRows.toObjectRows(selectChannelBreakdownBetween(deviceIds, start, end), 7);
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
