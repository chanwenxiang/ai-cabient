package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.DeviceInfo;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DeviceInfoMapper extends BaseTradeMapper<DeviceInfo> {

    DeviceInfo findByIdForUpdateRaw(@Param("deviceId") String deviceId);

    default Optional<DeviceInfo> findByIdForUpdate(String deviceId) {
        return Optional.ofNullable(findByIdForUpdateRaw(deviceId));
    }

    default List<DeviceInfo> findAllOrderByDeviceIdAsc() {
        return selectList(Wrappers.<DeviceInfo>lambdaQuery()
                .orderByAsc(DeviceInfo::getDeviceId));
    }

    default long countByMerchantId(String merchantId) {
    Long c = selectCount(Wrappers.<DeviceInfo>lambdaQuery().eq(DeviceInfo::getMerchantId, merchantId));
    return c == null ? 0 : c;
    }

    default long countByOnlineStatusNot(String onlineStatus) {
    Long c = selectCount(Wrappers.<DeviceInfo>lambdaQuery().ne(DeviceInfo::getOnlineStatus, onlineStatus));
    return c == null ? 0 : c;
    }

    default List<DeviceInfo> findTop10ByOnlineStatusNotOrderByUpdatedAtAsc(String onlineStatus) {
    return selectList(Wrappers.<DeviceInfo>lambdaQuery().ne(DeviceInfo::getOnlineStatus, onlineStatus).orderByAsc(DeviceInfo::getUpdatedAt).last("LIMIT 10"));
    }

    default List<DeviceInfo> findByOnlineStatus(String onlineStatus) {
        return selectList(Wrappers.<DeviceInfo>lambdaQuery()
                .eq(DeviceInfo::getOnlineStatus, onlineStatus)
                .orderByAsc(DeviceInfo::getUpdatedAt));
    }

    default List<DeviceInfo> findByOnlineStatusAndUpdatedAtBefore(String onlineStatus, java.time.Instant cutoff) {
        return findByOnlineStatusAndUpdatedAtBefore(onlineStatus, cutoff, 500);
    }

    default List<DeviceInfo> findByOnlineStatusAndUpdatedAtBefore(
            String onlineStatus, java.time.Instant cutoff, int limit) {
        int lim = Math.max(1, Math.min(limit, 500));
        return selectList(Wrappers.<DeviceInfo>lambdaQuery()
                .eq(DeviceInfo::getOnlineStatus, onlineStatus)
                .and(w -> w.isNull(DeviceInfo::getUpdatedAt).or().lt(DeviceInfo::getUpdatedAt, cutoff))
                .orderByAsc(DeviceInfo::getUpdatedAt)
                .last("LIMIT " + lim));
    }

    default List<DeviceInfo> findByOnlineStatusAndUpdatedAtBeforeAndSalesLockedFalse(
            String onlineStatus, java.time.Instant cutoff) {
        return findByOnlineStatusAndUpdatedAtBeforeAndSalesLockedFalse(onlineStatus, cutoff, 500);
    }

    default List<DeviceInfo> findByOnlineStatusAndUpdatedAtBeforeAndSalesLockedFalse(
            String onlineStatus, java.time.Instant cutoff, int limit) {
        int lim = Math.max(1, Math.min(limit, 500));
        return selectList(Wrappers.<DeviceInfo>lambdaQuery()
                .eq(DeviceInfo::getOnlineStatus, onlineStatus)
                .and(w -> w.isNull(DeviceInfo::getSalesLocked).or().eq(DeviceInfo::getSalesLocked, false))
                .lt(DeviceInfo::getUpdatedAt, cutoff)
                .orderByAsc(DeviceInfo::getUpdatedAt)
                .last("LIMIT " + lim));
    }

    /** updateById 默认跳过 null 字段，离线置空 online_since 需要显式 SQL。 */
    default void clearOnlineSince(String deviceId) {
        update(null, Wrappers.<DeviceInfo>lambdaUpdate()
                .eq(DeviceInfo::getDeviceId, deviceId)
                .set(DeviceInfo::getOnlineSince, null));
    }

    /** 锁机时清空解锁宽限时间戳。 */
    default void clearSalesUnlockedAt(String deviceId) {
        update(null, Wrappers.<DeviceInfo>lambdaUpdate()
                .eq(DeviceInfo::getDeviceId, deviceId)
                .set(DeviceInfo::getSalesUnlockedAt, null));
    }

    /** 锁机中且已稳定在线超过 cutff 的设备（用于稳定在线自动解锁）。 */
    default List<DeviceInfo> findByOnlineStatusAndSalesLockedTrueAndOnlineSinceBefore(
            String onlineStatus, java.time.Instant cutoff, int limit) {
        int lim = Math.max(1, Math.min(limit, 500));
        return selectList(Wrappers.<DeviceInfo>lambdaQuery()
                .eq(DeviceInfo::getOnlineStatus, onlineStatus)
                .eq(DeviceInfo::getSalesLocked, true)
                .isNotNull(DeviceInfo::getOnlineSince)
                .lt(DeviceInfo::getOnlineSince, cutoff)
                .orderByAsc(DeviceInfo::getOnlineSince)
                .last("LIMIT " + lim));
    }

    default List<DeviceInfo> findByOnlineStatusNot(String onlineStatus) {
    return selectList(Wrappers.<DeviceInfo>lambdaQuery().ne(DeviceInfo::getOnlineStatus, onlineStatus).orderByAsc(DeviceInfo::getUpdatedAt));
    }

    default List<DeviceInfo> findByOnlineStatusNot(String onlineStatus, int limit) {
        int lim = Math.max(1, Math.min(limit, 500));
        return selectList(Wrappers.<DeviceInfo>lambdaQuery()
                .ne(DeviceInfo::getOnlineStatus, onlineStatus)
                .orderByAsc(DeviceInfo::getUpdatedAt)
                .last("LIMIT " + lim));
    }

    default long countByOnlineStatus(String onlineStatus) {
        Long c = selectCount(Wrappers.<DeviceInfo>lambdaQuery().eq(DeviceInfo::getOnlineStatus, onlineStatus));
        return c == null ? 0 : c;
    }

    default long countByDeviceIdInAndOnlineStatusNot(Collection<String> deviceIds, String onlineStatus) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            return 0;
        }
        Long c = selectCount(Wrappers.<DeviceInfo>lambdaQuery()
                .in(DeviceInfo::getDeviceId, deviceIds)
                .ne(DeviceInfo::getOnlineStatus, onlineStatus));
        return c == null ? 0 : c;
    }

    default List<DeviceInfo> findByMerchantIdIn(Collection<String> merchantIds) {
    return selectList(Wrappers.<DeviceInfo>lambdaQuery().in(DeviceInfo::getMerchantId, merchantIds));
    }

    default List<DeviceInfo> findByDeviceIdIn(Collection<String> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<DeviceInfo>lambdaQuery().in(DeviceInfo::getDeviceId, deviceIds));
    }

    @Select("""
            SELECT COALESCE(MAX(CAST(device_id AS BIGINT)), 0)
            FROM device_info
            WHERE device_id ~ '^[0-9]+$'
              AND LENGTH(device_id) BETWEEN 6 AND 10
              AND is_deleted = false
            """)
    Long maxNumericDeviceIdRaw();

    default Optional<DeviceInfo> findByImei(String imei) {
        if (imei == null || imei.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(selectOne(Wrappers.<DeviceInfo>lambdaQuery()
                .eq(DeviceInfo::getImei, imei.trim())));
    }

}
