package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.OtaDeviceReport;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OtaDeviceReportMapper extends BaseTradeMapper<OtaDeviceReport> {

    default Optional<OtaDeviceReport> findByDeviceId(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(selectById(deviceId.trim()));
    }

    default List<OtaDeviceReport> findByUpgradeStatusOrderByUpdatedAtDesc(String upgradeStatus) {
        return selectList(Wrappers.<OtaDeviceReport>lambdaQuery()
                .eq(OtaDeviceReport::getUpgradeStatus, upgradeStatus)
                .orderByDesc(OtaDeviceReport::getUpdatedAt));
    }

    default List<OtaDeviceReport> findAllOrderByUpdatedAtDesc(int limit) {
        int lim = Math.max(1, Math.min(limit, 500));
        return selectList(Wrappers.<OtaDeviceReport>lambdaQuery()
                .orderByDesc(OtaDeviceReport::getUpdatedAt)
                .last("LIMIT " + lim));
    }

    /**
     * 显式整行改写进度列。
     *
     * <p>🔴 **不能**用 {@code updateById}：它默认跳过 null 字段，而 {@code target_version}
     * （升级结束后要清空）与 {@code error_message}（非 FAILED 时必须清空）恰恰要靠写 null
     * 来表达 —— 走 {@code updateById} 会让上一次的失败原因永久残留，和
     * {@code upgrade_status=DOWNLOADING} 自相矛盾（设备离线自动锁机那边踩过同一个坑，
     * 见 {@code DeviceInfoMapper.clearSalesLockReason}）。
     */
    default int updateProgress(String deviceId,
                               String targetVersion,
                               String upgradeStatus,
                               int progressPercent,
                               String errorMessage,
                               Instant updatedAt) {
        return update(null, Wrappers.<OtaDeviceReport>lambdaUpdate()
                .eq(OtaDeviceReport::getDeviceId, deviceId)
                .set(OtaDeviceReport::getTargetVersion, targetVersion)
                .set(OtaDeviceReport::getUpgradeStatus, upgradeStatus)
                .set(OtaDeviceReport::getProgressPercent, progressPercent)
                .set(OtaDeviceReport::getErrorMessage, errorMessage)
                .set(OtaDeviceReport::getUpdatedAt, updatedAt));
    }
}
