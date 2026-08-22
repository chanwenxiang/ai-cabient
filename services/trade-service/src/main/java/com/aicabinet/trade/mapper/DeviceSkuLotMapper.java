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

    List<LinkedHashMap<String, Object>> _sumSellableBySku(@Param("deviceId") String deviceId);

    default List<Object[]> sumSellableBySku(String deviceId) {
        return ColumnMapRows.toObjectRows(_sumSellableBySku(deviceId), 2);
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

    List<LinkedHashMap<String, Object>> _sumBookQtyBySlot(@Param("deviceId") String deviceId);

    default List<Object[]> sumBookQtyBySlot(String deviceId) {
        return ColumnMapRows.toObjectRows(_sumBookQtyBySlot(deviceId), 2);
    }
}
