package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.WarehouseInTransit;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WarehouseInTransitMapper extends BaseTradeMapper<WarehouseInTransit> {

    List<WarehouseInTransit> findByOutboundIdAndDeviceIdAndStatusForUpdateRaw(
            @Param("outboundId") Long outboundId,
            @Param("deviceId") String deviceId,
            @Param("status") String status);

    default List<WarehouseInTransit> findByOutboundIdAndDeviceIdAndStatusForUpdate(
            Long outboundId, String deviceId, String status) {
        return findByOutboundIdAndDeviceIdAndStatusForUpdateRaw(outboundId, deviceId, status);
    }

    default List<WarehouseInTransit> findByDeviceIdAndStatus(String deviceId, String status) {
    return selectList(Wrappers.<WarehouseInTransit>lambdaQuery().eq(WarehouseInTransit::getDeviceId, deviceId).eq(WarehouseInTransit::getStatus, status));
    }

    default List<WarehouseInTransit> findByOutboundIdAndDeviceIdAndStatus(Long outboundId, String deviceId, String status) {
    return selectList(Wrappers.<WarehouseInTransit>lambdaQuery().eq(WarehouseInTransit::getOutboundId, outboundId).eq(WarehouseInTransit::getDeviceId, deviceId).eq(WarehouseInTransit::getStatus, status));
    }

    default List<WarehouseInTransit> findByStatusOrderByCreatedAtAsc(String status) {
    return selectList(Wrappers.<WarehouseInTransit>lambdaQuery().eq(WarehouseInTransit::getStatus, status).orderByAsc(WarehouseInTransit::getCreatedAt));
    }

    default List<WarehouseInTransit> findByStatusAndCreatedAtBefore(String status, java.time.Instant cutoff, int limit) {
        int lim = Math.max(1, Math.min(limit, 500));
        return selectList(Wrappers.<WarehouseInTransit>lambdaQuery()
                .eq(WarehouseInTransit::getStatus, status)
                .lt(WarehouseInTransit::getCreatedAt, cutoff)
                .orderByAsc(WarehouseInTransit::getCreatedAt)
                .last("LIMIT " + lim));
    }

    default long countByStatusAndCreatedAtBefore(String status, java.time.Instant cutoff) {
        Long c = selectCount(Wrappers.<WarehouseInTransit>lambdaQuery()
                .eq(WarehouseInTransit::getStatus, status)
                .lt(WarehouseInTransit::getCreatedAt, cutoff));
        return c == null ? 0 : c;
    }

    default long countByStatusAndCreatedAtBeforeAndDeviceIdIn(
            String status, java.time.Instant cutoff, java.util.Collection<String> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            return 0;
        }
        Long c = selectCount(Wrappers.<WarehouseInTransit>lambdaQuery()
                .eq(WarehouseInTransit::getStatus, status)
                .lt(WarehouseInTransit::getCreatedAt, cutoff)
                .in(WarehouseInTransit::getDeviceId, deviceIds));
        return c == null ? 0 : c;
    }

    /**
     * 运营工作台「在途超时」告警：状态 + 超时 + **设备范围**一起下推，`LIMIT` 落在过滤之后。
     *
     * <p>旧写法 {@code findByStatusAndCreatedAtBefore(…, 500)} 再内存过滤 deviceId：窗口是全局
     * 最早的 500 行，域受限账号自己的超时行排在窗口外就一条都不显示（B13 同族）。
     */
    default List<WarehouseInTransit> findByStatusAndCreatedAtBeforeAndDeviceIdInOrderByCreatedAtAsc(
            String status, java.time.Instant cutoff, java.util.Collection<String> deviceIds, int limit) {
        int lim = Math.max(1, Math.min(limit, 500));
        if (deviceIds == null) {
            return findByStatusAndCreatedAtBefore(status, cutoff, lim);
        }
        if (deviceIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<WarehouseInTransit>lambdaQuery()
                .eq(WarehouseInTransit::getStatus, status)
                .lt(WarehouseInTransit::getCreatedAt, cutoff)
                .in(WarehouseInTransit::getDeviceId, deviceIds)
                .orderByAsc(WarehouseInTransit::getCreatedAt)
                .last("LIMIT " + lim));
    }

    /**
     * 在途超时计数：口径是「按出库单去重」（无出库单号时退化到 transit_id），与告警明细同一谓词。
     * 旧写法取 500 行样本在内存里去重计数，超时行超过 500 时数字封顶。
     */
    @org.apache.ibatis.annotations.Select("""
            <script>
            SELECT COUNT(DISTINCT COALESCE(outbound_id, transit_id)) FROM warehouse_in_transit
            WHERE status = #{status} AND created_at &lt; #{cutoff}
            <!-- P3-r19-4（2026-11-12）：空集合必须收起整段谓词。
                 `deviceIds=[]` 时旧写法生成 `IN ()` —— PostgreSQL 语法错，整条计数 500。
                 空集语义 = 「没有可见设备」⇒ 计 0：用 1=0 兜住，别让 SQL 报错被上层当成功。 -->
            <if test="deviceIds != null and deviceIds.size() > 0">
              AND device_id IN <foreach collection="deviceIds" item="__item" open="(" separator="," close=")">#{__item}</foreach>
            </if>
            <if test="deviceIds != null and deviceIds.size() == 0">
              AND 1 = 0
            </if>
            </script>
            """)
    long countDistinctOutboundOverdue(
            @Param("status") String status,
            @Param("cutoff") java.time.Instant cutoff,
            @Param("deviceIds") java.util.Collection<String> deviceIds);

    default boolean existsByOutboundIdAndDeviceIdAndStatus(Long outboundId, String deviceId, String status) {
    return selectCount(Wrappers.<WarehouseInTransit>lambdaQuery().eq(WarehouseInTransit::getOutboundId, outboundId).eq(WarehouseInTransit::getDeviceId, deviceId).eq(WarehouseInTransit::getStatus, status)) > 0;
    }

    /** page 为 0-based。 */
    default Page<WarehouseInTransit> searchPage(String deviceId, String status, int page, int size) {
        return searchPage(deviceId, status, null, page, size);
    }

    /**
     * D28-B5：{@code createdBefore} 可选（超时筛选走服务端）。口径＝「createdAt 早于 now-24h」，
     * 与前端 {@code TRANSIT_OVERDUE_HOURS = 24}、OpsWorkbenchQueryService.IN_TRANSIT_OVERDUE_HOURS 一致。
     */
    default Page<WarehouseInTransit> searchPage(
            String deviceId, String status, java.time.Instant createdBefore, int page, int size) {
        String st = status != null && !status.isBlank() ? status.trim().toUpperCase() : "IN_TRANSIT";
        var query = Wrappers.<WarehouseInTransit>lambdaQuery()
                .eq(WarehouseInTransit::getStatus, st)
                .orderByAsc(WarehouseInTransit::getCreatedAt);
        if (createdBefore != null) {
            query.lt(WarehouseInTransit::getCreatedAt, createdBefore);
        }
        if (deviceId != null && !deviceId.isBlank()) {
            query.eq(WarehouseInTransit::getDeviceId, deviceId.trim());
        }
        return selectPage(new Page<>(page + 1L, size), query);
    }

}
