package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.DeviceInfo;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DeviceInfoMapper extends BaseTradeMapper<DeviceInfo> {

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

}
