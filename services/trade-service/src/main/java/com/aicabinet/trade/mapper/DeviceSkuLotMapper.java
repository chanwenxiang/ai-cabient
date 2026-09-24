package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.DeviceSkuLot;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DeviceSkuLotMapper extends BaseTradeMapper<DeviceSkuLot> {

    default List<DeviceSkuLot> findByDeviceIdAndSkuIdOrderByExpiryDateAsc(String deviceId, String skuId) {
        return selectList(Wrappers.<DeviceSkuLot>lambdaQuery()
                .eq(DeviceSkuLot::getDeviceId, deviceId)
                .eq(DeviceSkuLot::getSkuId, skuId)
                .orderByAsc(DeviceSkuLot::getExpiryDate));
    }

    /**
     * 清仓（滞销）判定用：按**入库时间**升序取该设备该 SKU 的批次，最早入库的在最前。
     *
     * <p>刻意与 {@link #findByDeviceIdAndSkuIdOrderByExpiryDateAsc}（FEFO 用到期日）分开：
     * 「临期」看 {@code expiry_date}，「清仓」看 {@code created_at}，是两个不同维度。
     */
    default List<DeviceSkuLot> findByDeviceIdAndSkuIdOrderByCreatedAtAsc(String deviceId, String skuId) {
        return selectList(Wrappers.<DeviceSkuLot>lambdaQuery()
                .eq(DeviceSkuLot::getDeviceId, deviceId)
                .eq(DeviceSkuLot::getSkuId, skuId)
                .orderByAsc(DeviceSkuLot::getCreatedAt));
    }

    default List<DeviceSkuLot> findByDeviceIdAndSkuIdAndSlotIdOrderByExpiryDateAsc(
            String deviceId, String skuId, String slotId) {
        return selectList(Wrappers.<DeviceSkuLot>lambdaQuery()
                .eq(DeviceSkuLot::getDeviceId, deviceId)
                .eq(DeviceSkuLot::getSkuId, skuId)
                .eq(DeviceSkuLot::getSlotId, slotId)
                .orderByAsc(DeviceSkuLot::getExpiryDate));
    }

    default List<DeviceSkuLot> findByDeviceId(String deviceId) {
        return selectList(Wrappers.<DeviceSkuLot>lambdaQuery().eq(DeviceSkuLot::getDeviceId, deviceId));
    }

    default Optional<DeviceSkuLot> findByDeviceIdAndSkuIdAndBatchNo(
            String deviceId, String skuId, String batchNo) {
        List<DeviceSkuLot> rows = selectList(Wrappers.<DeviceSkuLot>lambdaQuery()
                .eq(DeviceSkuLot::getDeviceId, deviceId)
                .eq(DeviceSkuLot::getSkuId, skuId)
                .eq(DeviceSkuLot::getBatchNo, batchNo)
                .orderByDesc(DeviceSkuLot::getUpdatedAt));
        return rows.stream().findFirst();
    }

    default List<DeviceSkuLot> findAllByDeviceIdAndSkuIdAndBatchNo(
            String deviceId, String skuId, String batchNo) {
        return selectList(Wrappers.<DeviceSkuLot>lambdaQuery()
                .eq(DeviceSkuLot::getDeviceId, deviceId)
                .eq(DeviceSkuLot::getSkuId, skuId)
                .eq(DeviceSkuLot::getBatchNo, batchNo)
                .gt(DeviceSkuLot::getQuantity, 0)
                .orderByAsc(DeviceSkuLot::getExpiryDate));
    }

    /** 同批次可能分多个货道存放；补货入库按货道精确匹配。 */
    default Optional<DeviceSkuLot> findByDeviceIdAndSkuIdAndBatchNoAndSlotId(
            String deviceId, String skuId, String batchNo, String slotId) {
        return Optional.ofNullable(selectOne(Wrappers.<DeviceSkuLot>lambdaQuery()
                .eq(DeviceSkuLot::getDeviceId, deviceId)
                .eq(DeviceSkuLot::getSkuId, skuId)
                .eq(DeviceSkuLot::getBatchNo, batchNo)
                .eq(DeviceSkuLot::getSlotId, slotId)));
    }

    int sumSellableQuantity(@Param("deviceId") String deviceId, @Param("skuId") String skuId);

    /** 该柜机所有在售批次的物理库存全量（**运营口径**，不含货道启用过滤）。 */
    List<LinkedHashMap<String, Object>> selectSumSellableBySku(@Param("deviceId") String deviceId);

    default List<Object[]> sumSellableBySku(String deviceId) {
        return ColumnMapRows.toObjectRows(selectSumSellableBySku(deviceId), 2);
    }

    /**
     * 消费者口径的可售量：排除**已禁用货道**上的批次。
     *
     * <p>两个方法只差「货道启用与否」一个条件，但口径用途完全不同，别互相替换：
     * <ul>
     *   <li>{@link #sumSellableBySku}＝运营侧（账面/盘点/补货/资产），禁用的货道照样要算，
     *       否则货在柜里却从报表上消失；</li>
     *   <li>本方法＝消费者侧（小程序商品列表），禁用的货道不再对外可售。</li>
     * </ul>
     * 用错一边就是「禁用开关失效（小程序照旧显示）」或「运营报表凭空少货」。
     */
    List<LinkedHashMap<String, Object>> selectSumSellableBySkuOnEnabledSlots(
            @Param("deviceId") String deviceId);

    default List<Object[]> sumSellableBySkuOnEnabledSlots(String deviceId) {
        return ColumnMapRows.toObjectRows(selectSumSellableBySkuOnEnabledSlots(deviceId), 2);
    }

    long countNearExpiry(@Param("today") LocalDate today, @Param("nearDate") LocalDate nearDate);

    long countExpiredWithStock(@Param("today") LocalDate today);

    default List<DeviceSkuLot> findByStatusInAndQuantityGreaterThan(List<String> statuses, int quantity) {
        return selectList(Wrappers.<DeviceSkuLot>lambdaQuery()
                .in(DeviceSkuLot::getStatus, statuses)
                .gt(DeviceSkuLot::getQuantity, quantity));
    }

    /**
     * 临期/过期扫描：仅扫有库存且到期日不晚于 horizon 的批次，按到期日升序批量拉取。
     */
    default List<DeviceSkuLot> findForExpiryScan(List<String> statuses, LocalDate horizonDate, int limit) {
        int lim = Math.max(1, Math.min(limit, 500));
        return selectList(Wrappers.<DeviceSkuLot>lambdaQuery()
                .in(DeviceSkuLot::getStatus, statuses)
                .gt(DeviceSkuLot::getQuantity, 0)
                .le(DeviceSkuLot::getExpiryDate, horizonDate)
                .orderByAsc(DeviceSkuLot::getExpiryDate)
                .last("LIMIT " + lim));
    }

    List<LinkedHashMap<String, Object>> selectSumBookQtyBySlot(@Param("deviceId") String deviceId);

    default List<Object[]> sumBookQtyBySlot(String deviceId) {
        return ColumnMapRows.toObjectRows(selectSumBookQtyBySlot(deviceId), 2);
    }
}
