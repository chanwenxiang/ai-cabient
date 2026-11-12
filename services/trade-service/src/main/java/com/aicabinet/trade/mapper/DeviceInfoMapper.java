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
        // 仅锁「曾心跳上线过」的离线柜；新建未联网柜 last_heartbeat_at 为空，不参与自动停售（BUG-004）
        return selectList(Wrappers.<DeviceInfo>lambdaQuery()
                .eq(DeviceInfo::getOnlineStatus, onlineStatus)
                .and(w -> w.isNull(DeviceInfo::getSalesLocked).or().eq(DeviceInfo::getSalesLocked, false))
                .isNotNull(DeviceInfo::getLastHeartbeatAt)
                .lt(DeviceInfo::getLastHeartbeatAt, cutoff)
                .orderByAsc(DeviceInfo::getLastHeartbeatAt)
                .last("LIMIT " + lim));
    }

    /** updateById 默认跳过 null 字段，离线置空 online_since 需要显式 SQL。 */
    default void clearOnlineSince(String deviceId) {
        update(null, Wrappers.<DeviceInfo>lambdaUpdate()
                .eq(DeviceInfo::getDeviceId, deviceId)
                .set(DeviceInfo::getOnlineSince, null));
    }

    /**
     * 解绑时清空所属商户（R2-D2）。
     * <p>{@code DeviceAssetService} 解绑只做 {@code setMerchantId(null)} + {@code save}（=updateById
     * 跳 null）⇒ 库里 {@code merchant_id} 原地不动，原商户的设备列表/异常/经营分析/自主补货
     * 仍包含该柜并能继续操作。必须与 {@link #clearSalesLockReason} 一样走显式 SQL。</p>
     */
    default void clearMerchantId(String deviceId) {
        update(null, Wrappers.<DeviceInfo>lambdaUpdate()
                .eq(DeviceInfo::getDeviceId, deviceId)
                .set(DeviceInfo::getMerchantId, null));
    }

    /** 锁机时清空解锁宽限时间戳。 */
    default void clearSalesUnlockedAt(String deviceId) {
        update(null, Wrappers.<DeviceInfo>lambdaUpdate()
                .eq(DeviceInfo::getDeviceId, deviceId)
                .set(DeviceInfo::getSalesUnlockedAt, null));
    }

    /**
     * 解锁时清空停售原因。
     * <p>{@code updateById} 默认跳过 null 字段，服务层 {@code setSalesLockReason(null)} 不会落库，
     * 必须与 {@link #clearSalesUnlockedAt} 一样走显式 SQL；否则解锁后设备会残留
     * 「离线超时自动停售」这类原因，与 {@code sales_locked=false} 自相矛盾。</p>
     */
    default void clearSalesLockReason(String deviceId) {
        update(null, Wrappers.<DeviceInfo>lambdaUpdate()
                .eq(DeviceInfo::getDeviceId, deviceId)
                .set(DeviceInfo::getSalesLockReason, null));
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

    /**
     * **在线**且固件版本 ≠ 期望值的台数（P0-1 阶段 B「边缘盒监控」的固件漂移信号）。
     *
     * <p>NULL 语义：从未上报过固件的设备也算「不合规」（{@code isNull ... or().ne(...)}），
     * 否则一台从没上报固件的盒子会被读成「合规」，把没数据当成没问题。
     */
    default long countByOnlineStatusAndFirmwareVersionNot(String onlineStatus, String firmwareVersion) {
        Long c = selectCount(Wrappers.<DeviceInfo>lambdaQuery()
                .eq(DeviceInfo::getOnlineStatus, onlineStatus)
                .and(w -> w.isNull(DeviceInfo::getFirmwareVersion)
                        .or().ne(DeviceInfo::getFirmwareVersion, firmwareVersion)));
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

    /**
     * 工作台「柜机离线」待办：**设备范围下推** + 排序 + `LIMIT`（上限落在过滤之后）。
     *
     * <p>旧写法是「取全局按 updatedAt 最早的 N 台，再内存过滤设备范围」——窗口是全局的，
     * 域受限账号自己的离线柜排在窗口外就一条都不显示（B13 同族）。计数早已带范围
     * （{@link #countByDeviceIdInAndOnlineStatusNot}），列表不带 → 数字与列表互相矛盾。
     */
    default List<DeviceInfo> findByDeviceIdInAndOnlineStatusNotOrderByUpdatedAtAsc(
            Collection<String> deviceIds, String onlineStatus, int limit) {
        int lim = Math.max(1, Math.min(limit, 500));
        if (deviceIds == null) {
            return findByOnlineStatusNot(onlineStatus, lim);
        }
        if (deviceIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<DeviceInfo>lambdaQuery()
                .in(DeviceInfo::getDeviceId, deviceIds)
                .ne(DeviceInfo::getOnlineStatus, onlineStatus)
                .orderByAsc(DeviceInfo::getUpdatedAt)
                .last("LIMIT " + lim));
    }

    /**
     * 运营端「设备离线」待办：在设备范围之上再叠「投放柜」口径。
     *
     * <p>🔴 lifecycle 判据不能直接写 {@code = 'DEPLOYED'}：{@code DeviceAssetService#normalizeLifecycle}
     * 把 null/非法值都归一成 DEPLOYED，历史行为 null —— 等值比较会把它们全漏掉。
     * 「投放柜」这一维也必须下推，否则窗口会被 INBOUND/退役柜占满，只下推设备范围等于没修。
     */
    default List<DeviceInfo> findDeployedByDeviceIdInAndOnlineStatusNotOrderByUpdatedAtAsc(
            Collection<String> deviceIds, String onlineStatus, int limit) {
        int lim = Math.max(1, Math.min(limit, 500));
        if (deviceIds != null && deviceIds.isEmpty()) {
            return List.of();
        }
        var query = Wrappers.<DeviceInfo>lambdaQuery()
                .ne(DeviceInfo::getOnlineStatus, onlineStatus)
                .and(w -> w.isNull(DeviceInfo::getLifecycleStatus)
                        .or().eq(DeviceInfo::getLifecycleStatus, "DEPLOYED"))
                .orderByAsc(DeviceInfo::getUpdatedAt)
                .last("LIMIT " + lim);
        if (deviceIds != null) {
            query.in(DeviceInfo::getDeviceId, deviceIds);
        }
        return selectList(query);
    }

    /** 运营端离线计数（全量口径）：与 {@link #findDeployedByDeviceIdInAndOnlineStatusNotOrderByUpdatedAtAsc} 同一谓词。 */
    default long countDeployedByOnlineStatusNot(String onlineStatus) {
        Long c = selectCount(Wrappers.<DeviceInfo>lambdaQuery()
                .ne(DeviceInfo::getOnlineStatus, onlineStatus)
                .and(w -> w.isNull(DeviceInfo::getLifecycleStatus)
                        .or().eq(DeviceInfo::getLifecycleStatus, "DEPLOYED")));
        return c == null ? 0 : c;
    }

    /** 运营端离线计数（设备范围口径）：同上，范围下推，不再「全表拉回内存里 filter(count)」。 */
    default long countDeployedByDeviceIdInAndOnlineStatusNot(Collection<String> deviceIds, String onlineStatus) {
        if (deviceIds == null) {
            return countDeployedByOnlineStatusNot(onlineStatus);
        }
        if (deviceIds.isEmpty()) {
            return 0;
        }
        Long c = selectCount(Wrappers.<DeviceInfo>lambdaQuery()
                .in(DeviceInfo::getDeviceId, deviceIds)
                .ne(DeviceInfo::getOnlineStatus, onlineStatus)
                .and(w -> w.isNull(DeviceInfo::getLifecycleStatus)
                        .or().eq(DeviceInfo::getLifecycleStatus, "DEPLOYED")));
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

    /**
     * 门磁回执定点更新（V402 §4.2.16）：只写 door/lock 三列。
     * <p>不用 {@code findById + updateById} 整行回写——那会把读到的旧 online_status 一并写回，
     * 与并发的 ws-online 置位抢写。只更新非 null 的字段，另一侧保留上次值。</p>
     */
    default int updateDoorState(String deviceId, String doorState, String lockState,
                                java.time.Instant reportedAt) {
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<DeviceInfo> wrapper =
                Wrappers.<DeviceInfo>lambdaUpdate()
                        .eq(DeviceInfo::getDeviceId, deviceId)
                        .set(DeviceInfo::getDoorReportedAt, reportedAt)
                        .set(DeviceInfo::getUpdatedAt, reportedAt);
        if (doorState != null) {
            wrapper.set(DeviceInfo::getDoorState, doorState);
        }
        if (lockState != null) {
            wrapper.set(DeviceInfo::getLockState, lockState);
        }
        return update(null, wrapper);
    }

    default Optional<DeviceInfo> findByImei(String imei) {
        if (imei == null || imei.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(selectOne(Wrappers.<DeviceInfo>lambdaQuery()
                .eq(DeviceInfo::getImei, imei.trim())));
    }

    /**
     * 附近柜机候选（R20 P2-16）：把「有坐标 + 生命周期在投放态 + 地理矩形框」三件事下推到 SQL。
     *
     * <p>原实现 {@code findAllOrderByDeviceIdAsc()} 全表载回内存再逐个算 haversine ——
     * 线下柜机越多越慢，且一次「附近柜机」把全表都拉过网络。这里先用**外接矩形**粗筛
     * （SQL 走 latitude/longitude 索引可用区间），再由调用方用 haversine 精筛半径。
     * 矩形是圆的超集，因此不会漏目标柜机，只是候选里会多出矩形角落的几个。
     *
     * <p>经度跨越 ±180（日界线附近）时 SQL 的 {@code BETWEEN} 无法表达环绕，此时调用方传
     * {@code minLon=null} ⇒ **只按纬度过滤**（仍能砍掉绝大部分），精度不受影响（后续 haversine
     * 才是判据）。宁可少用一次索引，也不能因为跨日界线把该出现的柜机漏掉。
     *
     * <p>生命周期判据必须与内存版 {@code isExcludedLifecycle} 逐字对齐：trim + upper 后落在
     * {RETIRED, WAREHOUSE, SCRAPPED, DECOMMISSIONED} 才排除。{@code NULL} 与空串都要**放行**
     * （历史行 lifecycle 为 null 时按 DEPLOYED 处理，见 {@code DeviceAssetService.normalizeLifecycle}），
     * 所以不能写成 {@code NOT IN} 单条件——SQL 三值逻辑里 {@code NULL NOT IN (...)} 是 NULL，
     * 会把历史柜机整批滤掉。
     *
     * <p>{@code is_deleted = false} 手写：{@code @TableLogic} 只对 Wrapper 生效，注解 SQL 不受管。
     */
    @Select("""
            <script>
            SELECT * FROM device_info
            WHERE is_deleted = false
              AND latitude IS NOT NULL AND longitude IS NOT NULL
              AND latitude BETWEEN #{minLat} AND #{maxLat}
              AND (lifecycle_status IS NULL
                   OR UPPER(TRIM(lifecycle_status)) NOT IN
                      ('RETIRED', 'WAREHOUSE', 'SCRAPPED', 'DECOMMISSIONED'))
              <if test="minLon != null">
              AND longitude BETWEEN #{minLon} AND #{maxLon}
              </if>
            ORDER BY device_id ASC
            </script>
            """)
    List<DeviceInfo> findNearbyCandidates(@Param("minLat") double minLat,
                                          @Param("maxLat") double maxLat,
                                          @Param("minLon") Double minLon,
                                          @Param("maxLon") Double maxLon);

}
