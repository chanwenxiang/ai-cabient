package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OtaUpgradeProgressDto;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.OtaDeviceReport;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.OtaDeviceReportMapper;
import com.aicabinet.trade.mapper.OtaReleaseMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * O2 升级进度上报：开关 fail-closed、状态白名单、进度收敛、失败原因清理、SUCCESS 落版本。
 */
@ExtendWith(MockitoExtension.class)
class OtaProgressReportTest {

    private static final String DEV = "DEV-001";

    @Mock private OtaReleaseMapper releaseRepository;
    @Mock private DeviceInfoMapper deviceRepository;
    @Mock private OtaDeviceReportMapper reportRepository;
    @Mock private OtaCdnService otaCdnService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private SystemConfigService systemConfigService;

    private OtaService service;

    @BeforeEach
    void setUp() {
        service = new OtaService(releaseRepository, deviceRepository, reportRepository,
                otaCdnService, new ObjectMapper(), distributedLockService, systemConfigService);
    }

    private void switchOn() {
        when(systemConfigService.getBoolean(SystemConfigService.OTA_PROGRESS_ENABLED, false)).thenReturn(true);
    }

    private void lockFree() {
        when(distributedLockService.tryLock(OtaService.otaDeviceVersionLockKey(DEV), 60L, 5L)).thenReturn(true);
    }

    // ── 开关：关闭即「与接入前一致」，一次写都不该发生 ────────────────────────

    @Test
    void reportProgress_whenSwitchOff_writesNothingAndReturnsEmpty() {
        when(systemConfigService.getBoolean(SystemConfigService.OTA_PROGRESS_ENABLED, false)).thenReturn(false);

        assertEquals(Optional.empty(),
                service.reportProgress(DEV, "9.9.9", "DOWNLOADING", 40, null));

        verifyNoInteractions(reportRepository);
        verifyNoInteractions(distributedLockService);
    }

    @Test
    void defaultConfigValue_isClosed() {
        // 不 stub ⇒ 走默认值：期望 false。这条断言锁住「defaultValue 参数就是 false」，
        // 别人把它改成 true 时这里会红（那等于默认打开写库）。
        when(systemConfigService.getBoolean(SystemConfigService.OTA_PROGRESS_ENABLED, false)).thenReturn(false);
        assertEquals(Optional.empty(), service.reportProgress(DEV, "9.9.9", "IDLE", 0, null));
        verify(systemConfigService).getBoolean(SystemConfigService.OTA_PROGRESS_ENABLED, false);
    }

    // ── 非法输入：显式 400，不静默落库 ───────────────────────────────────────

    @Test
    void reportProgress_whenStatusUnknown_rejectsWithBadRequest() {
        switchOn();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.reportProgress(DEV, "9.9.9", "MAYBE_DONE", 10, null));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verifyNoInteractions(reportRepository);
    }

    @Test
    void reportProgress_whenDeviceIdBlank_rejectsWithBadRequest() {
        switchOn();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.reportProgress("   ", "9.9.9", "IDLE", 0, null));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void reportProgress_whenLockBusy_rejectsWithConflict() {
        switchOn();
        when(distributedLockService.tryLock(OtaService.otaDeviceVersionLockKey(DEV), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.reportProgress(DEV, "9.9.9", "DOWNLOADING", 40, null));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    // ── 纯函数：状态归一化与进度收敛 ────────────────────────────────────────

    @Test
    void normalizeStatus_blankIsIdle_lowercaseAccepted() {
        assertEquals(OtaService.PROGRESS_IDLE, OtaService.normalizeProgressStatus(null));
        assertEquals(OtaService.PROGRESS_IDLE, OtaService.normalizeProgressStatus("  "));
        assertEquals(OtaService.PROGRESS_DOWNLOADING, OtaService.normalizeProgressStatus("downloading"));
        assertEquals(OtaService.PROGRESS_FAILED, OtaService.normalizeProgressStatus(" failed "));
    }

    @Test
    void clampPercent_convergesToRange() {
        assertEquals(0, OtaService.clampProgressPercent(null));
        assertEquals(0, OtaService.clampProgressPercent(-3));
        assertEquals(37, OtaService.clampProgressPercent(37));
        assertEquals(100, OtaService.clampProgressPercent(101));
    }

    // ── 落库：首次 INSERT，其后显式整行 UPDATE ──────────────────────────────

    @Test
    void reportProgress_firstReport_insertsRow() {
        switchOn();
        lockFree();
        when(reportRepository.selectById(DEV)).thenReturn(null);

        OtaUpgradeProgressDto dto = service.reportProgress(DEV, "9.9.9", "DOWNLOADING", 40, null)
                .orElseThrow();

        assertEquals(DEV, dto.deviceId());
        assertEquals("DOWNLOADING", dto.upgradeStatus());
        assertEquals(40, dto.progressPercent());
        assertEquals("9.9.9", dto.targetVersion());
        verify(reportRepository).insert(any(OtaDeviceReport.class));
        verify(reportRepository, never()).updateProgress(
                any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void reportProgress_secondReport_updatesWithoutClearingViaUpdateById() {
        switchOn();
        lockFree();
        OtaDeviceReport existing = new OtaDeviceReport();
        existing.setDeviceId(DEV);
        existing.setReportedAt(Instant.parse("2026-09-01T00:00:00Z"));
        existing.setUpgradeStatus("DOWNLOADING");
        when(reportRepository.selectById(DEV)).thenReturn(existing);

        OtaUpgradeProgressDto dto = service.reportProgress(DEV, "9.9.9", "INSTALLING", 80, null)
                .orElseThrow();

        assertEquals("INSTALLING", dto.upgradeStatus());
        // 必须走显式 set 的整行 UPDATE（updateById 会跳过 null，清不掉 target_version/error_message）
        verify(reportRepository).updateProgress(
                eq(DEV), eq("9.9.9"), eq("INSTALLING"), eq(80), isNull(), any(Instant.class));
        verify(reportRepository, never()).insert(any(OtaDeviceReport.class));
        assertEquals(Instant.parse("2026-09-01T00:00:00Z"), dto.reportedAt(), "首次上报时间不应被改写");
    }

    @Test
    void reportProgress_whenNotFailed_clearsStaleErrorMessage() {
        switchOn();
        lockFree();
        OtaDeviceReport existing = new OtaDeviceReport();
        existing.setDeviceId(DEV);
        existing.setErrorMessage("上一次安装失败的原因");
        when(reportRepository.selectById(DEV)).thenReturn(existing);

        service.reportProgress(DEV, "9.9.9", "DOWNLOADING", 10, "设备不该在下载态带失败原因");

        // 非 FAILED ⇒ 失败原因必须清空，否则运营台会看到「下载中 + 有失败原因」的自相矛盾
        verify(reportRepository).updateProgress(
                eq(DEV), eq("9.9.9"), eq("DOWNLOADING"), eq(10), isNull(), any(Instant.class));
    }

    @Test
    void reportProgress_whenFailed_keepsErrorMessage() {
        switchOn();
        lockFree();
        when(reportRepository.selectById(DEV)).thenReturn(null);

        OtaUpgradeProgressDto dto = service.reportProgress(DEV, "9.9.9", "FAILED", 60, "  sha256 校验失败  ")
                .orElseThrow();

        assertEquals("sha256 校验失败", dto.errorMessage());
        assertEquals("FAILED", dto.upgradeStatus());
    }

    // ── SUCCESS：与既有 reportVersion 落同一个字段，不制造第二套「当前版本」 ──

    @Test
    void reportProgress_whenSuccess_updatesDeviceAppVersion() {
        switchOn();
        lockFree();
        when(reportRepository.selectById(DEV)).thenReturn(null);
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId(DEV);
        device.setAppVersion("9.0.0");
        when(deviceRepository.findByIdForUpdate(DEV)).thenReturn(Optional.of(device));

        service.reportProgress(DEV, "9.9.9", "SUCCESS", 100, null);

        assertEquals("9.9.9", device.getAppVersion());
        verify(deviceRepository).save(device);
    }

    @Test
    void reportProgress_whenSuccessWithoutTargetVersion_doesNotTouchDeviceVersion() {
        switchOn();
        lockFree();
        when(reportRepository.selectById(DEV)).thenReturn(null);

        service.reportProgress(DEV, null, "SUCCESS", 100, null);

        verifyNoInteractions(deviceRepository);
    }

    @Test
    void reportProgress_whenNotSuccess_doesNotTouchDeviceVersion() {
        switchOn();
        lockFree();
        when(reportRepository.selectById(DEV)).thenReturn(null);

        service.reportProgress(DEV, "9.9.9", "DOWNLOADING", 99, null);

        verifyNoInteractions(deviceRepository);
    }

    // ── 读侧：关闭开关也不隐藏历史进度 ──────────────────────────────────────

    @Test
    void listProgress_withoutStatus_readsAll() {
        OtaDeviceReport row = new OtaDeviceReport();
        row.setDeviceId(DEV);
        row.setUpgradeStatus("FAILED");
        row.setErrorMessage("下载超时");
        when(reportRepository.findAllOrderByUpdatedAtDesc(200)).thenReturn(List.of(row));

        List<OtaUpgradeProgressDto> list = service.listProgress(null, 200);

        assertEquals(1, list.size());
        assertEquals("下载超时", list.get(0).errorMessage());
        // 读侧刻意不查开关：关掉开关是「不再写入新进度」，不该把历史进度也藏起来
        verifyNoInteractions(systemConfigService);
    }

    @Test
    void listProgress_withStatus_normalizesAndFilters() {
        when(reportRepository.findByUpgradeStatusOrderByUpdatedAtDesc("FAILED")).thenReturn(List.of());

        assertTrue(service.listProgress(" failed ", 200).isEmpty());
        verify(reportRepository).findByUpgradeStatusOrderByUpdatedAtDesc("FAILED");
    }

    @Test
    void listProgress_withUnknownStatus_rejectsWithBadRequest() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.listProgress("NOPE", 200));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void progressDto_keepsNullsAsNull() {
        OtaDeviceReport row = new OtaDeviceReport();
        row.setDeviceId(DEV);
        row.setUpgradeStatus("IDLE");
        when(reportRepository.findAllOrderByUpdatedAtDesc(10)).thenReturn(List.of(row));

        OtaUpgradeProgressDto dto = service.listProgress(null, 10).get(0);

        assertNull(dto.targetVersion());
        assertNull(dto.errorMessage());
        assertNull(dto.appVersion());
    }
}
