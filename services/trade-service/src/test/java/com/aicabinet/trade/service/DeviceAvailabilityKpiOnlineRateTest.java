package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceDailyOnlineRate;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.OpsException;
import com.aicabinet.trade.mapper.AdminAuditLogMapper;
import com.aicabinet.trade.mapper.DeviceAvailabilityKpiDailyMapper;
import com.aicabinet.trade.mapper.DeviceDailyOnlineRateMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.OpsExceptionMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CB-018 ②：柜机×日在线率。负向验证口径纯函数（跨天裁剪/进行中截断/向上取整），
 * 主流程钉「分母=已注册设备、离线区间扣减、rate∈[0,1]」。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeviceAvailabilityKpiOnlineRateTest {
    static {
        // service 用 Wrappers.<DeviceInfo>lambdaQuery().select(DeviceInfo::getDeviceId)：
        // 纯 Mockito 环境无 MP 启动流程，需手动注册 TableInfo 缓存（同 RevenueSplitServiceTest 写法）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                DeviceInfo.class);
    }

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.parse("2026-10-08");

    @Mock private DeviceInfoMapper deviceRepository;
    @Mock private OpsExceptionMapper exceptionRepository;
    @Mock private AdminAuditLogMapper auditRepository;
    @Mock private DeviceAvailabilityKpiDailyMapper kpiRepository;
    @Mock private DeviceDailyOnlineRateMapper onlineRateRepository;
    @Mock private DistributedLockService distributedLockService;

    private DeviceAvailabilityKpiService service;

    @BeforeEach
    void setUp() {
        service = new DeviceAvailabilityKpiService(deviceRepository, exceptionRepository,
                auditRepository, kpiRepository, onlineRateRepository, distributedLockService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        when(distributedLockService.tryLock(
                DeviceAvailabilityKpiService.deviceOnlineRateLockKey(DAY), 60L, 5L)).thenReturn(true);
        when(deviceRepository.selectList(any())).thenAnswer(inv -> {
            DeviceInfo a = new DeviceInfo();
            a.setDeviceId("CAB-A");
            DeviceInfo b = new DeviceInfo();
            b.setDeviceId("CAB-B");
            return List.of(a, b);
        });
    }

    private static OpsException offlineSegment(String deviceId, Instant from, Instant to) {
        OpsException e = new OpsException();
        e.setExceptionType("DEVICE_OFFLINE");
        e.setDeviceId(deviceId);
        e.setCreatedAt(from);
        e.setResolvedAt(to);
        return e;
    }

    // —— 纯函数口径（负向验证：真实违规/边界值必须算得对） ——

    @Test
    void clipOfflineMinutes_fullyInsideWindow() {
        Instant dayStart = DAY.atStartOfDay(ZONE).toInstant();
        long m = DeviceAvailabilityKpiService.clipOfflineMinutes(
                dayStart.plusSeconds(3600), dayStart.plusSeconds(7200),
                dayStart, dayStart.plusSeconds(86400));
        assertEquals(60, m);
    }

    @Test
    void clipOfflineMinutes_segmentStartsBeforeDay_clippedToWindow() {
        Instant dayStart = DAY.atStartOfDay(ZONE).toInstant();
        // 段从前一天 22:00 持续到当天 02:00 → 只计当天 0:00–2:00 = 120 分钟
        long m = DeviceAvailabilityKpiService.clipOfflineMinutes(
                dayStart.minusSeconds(2 * 3600), dayStart.plusSeconds(2 * 3600),
                dayStart, dayStart.plusSeconds(86400));
        assertEquals(120, m);
    }

    @Test
    void clipOfflineMinutes_segmentEndsAfterDay_clippedToWindow() {
        Instant dayStart = DAY.atStartOfDay(ZONE).toInstant();
        long m = DeviceAvailabilityKpiService.clipOfflineMinutes(
                dayStart.plusSeconds(23 * 3600), dayStart.plusSeconds(30 * 3600),
                dayStart, dayStart.plusSeconds(86400));
        assertEquals(60, m);
    }

    @Test
    void clipOfflineMinutes_disjoint_returnsZero() {
        Instant dayStart = DAY.atStartOfDay(ZONE).toInstant();
        long m = DeviceAvailabilityKpiService.clipOfflineMinutes(
                dayStart.plusSeconds(86400), dayStart.plusSeconds(90000),
                dayStart, dayStart.plusSeconds(86400));
        assertEquals(0, m);
    }

    @Test
    void clipOfflineMinutes_subMinute_roundsUp() {
        Instant dayStart = DAY.atStartOfDay(ZONE).toInstant();
        // 30 秒离线也计 1 分钟（离线多算的保守口径）
        long m = DeviceAvailabilityKpiService.clipOfflineMinutes(
                dayStart, dayStart.plusSeconds(30), dayStart, dayStart.plusSeconds(86400));
        assertEquals(1, m);
    }

    // —— 主流程 ——

    @Test
    void snapshotDeviceOnlineRates_writesRowPerDeviceWithOfflineDeduction() {
        Instant dayStart = DAY.atStartOfDay(ZONE).toInstant();
        Instant dayEnd = dayStart.plusSeconds(86400);
        // CAB-A：窗内离线 2 小时（7200s → 120 分钟）；CAB-B 全天在线
        when(exceptionRepository.findOfflineSegmentsOverlapping(dayStart, dayEnd))
                .thenReturn(List.of(offlineSegment("CAB-A",
                        dayStart.plusSeconds(3600), dayStart.plusSeconds(3 * 3600))));

        service.snapshotDeviceOnlineRates(DAY);

        ArgumentCaptor<DeviceDailyOnlineRate> captor = ArgumentCaptor.forClass(DeviceDailyOnlineRate.class);
        verify(onlineRateRepository, org.mockito.Mockito.times(2)).upsert(captor.capture());
        List<DeviceDailyOnlineRate> rows = captor.getAllValues();
        DeviceDailyOnlineRate a = rows.get(0).getDeviceId().equals("CAB-A") ? rows.get(0) : rows.get(1);
        DeviceDailyOnlineRate b = rows.get(0).getDeviceId().equals("CAB-B") ? rows.get(0) : rows.get(1);
        assertEquals(120, a.getOfflineMinutes());
        assertEquals(1320, a.getOnlineMinutes());
        assertEquals(1320 / 1440.0, a.getRate(), 1e-9);
        assertEquals(0, b.getOfflineMinutes());
        assertEquals(1440, b.getOnlineMinutes());
        assertEquals(1.0, b.getRate(), 1e-9);
    }

    @Test
    void snapshotDeviceOnlineRates_ongoingSegment_truncatedToNow() {
        LocalDate today = LocalDate.now(ZONE);
        Instant dayStart = today.atStartOfDay(ZONE).toInstant();
        when(distributedLockService.tryLock(
                DeviceAvailabilityKpiService.deviceOnlineRateLockKey(today), 60L, 5L)).thenReturn(true);
        when(deviceRepository.selectList(any())).thenAnswer(inv -> {
            DeviceInfo a = new DeviceInfo();
            a.setDeviceId("CAB-A");
            return List.of(a);
        });
        // 🔴 时间竞争：service 内部自取 Instant.now()，测试不能拿自己的 now 当 stub 参数——
        // 否则 stub 永远匹配不上真实调用（拿到空列表 → 0 分钟）。宽松匹配 + 事后 captor 取真实窗口。
        when(exceptionRepository.findOfflineSegmentsOverlapping(eq(dayStart), any()))
                .thenReturn(List.of(offlineSegment("CAB-A", dayStart.minusSeconds(3600), null)));

        service.snapshotDeviceOnlineRates(today);

        // 段从今天开始前 1 小时发起、仍未恢复（resolvedAt=null）→ 截断到 service 实际取的 now
        ArgumentCaptor<Instant> endCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(exceptionRepository).findOfflineSegmentsOverlapping(eq(dayStart), endCaptor.capture());
        Instant serviceNow = endCaptor.getValue();
        long expectedTotal = java.time.Duration.between(dayStart, serviceNow).toMinutes();
        assertTrue(expectedTotal > 0, "测试窗口应为当日已流逝时段");
        // 期望值用被测纯函数按真实窗口计算（该纯函数已单独负向验证）；截断/取整口径差异由 min 兜住
        long expectedOffline = Math.min(DeviceAvailabilityKpiService.clipOfflineMinutes(
                dayStart.minusSeconds(3600), serviceNow, dayStart, serviceNow), expectedTotal);
        ArgumentCaptor<DeviceDailyOnlineRate> captor = ArgumentCaptor.forClass(DeviceDailyOnlineRate.class);
        verify(onlineRateRepository).upsert(captor.capture());
        DeviceDailyOnlineRate row = captor.getValue();
        // 全天离线：离线分钟（秒累计向上取整）被钳到总窗（toMinutes 截断），故在线为 0
        assertEquals(expectedOffline, row.getOfflineMinutes().longValue());
        assertEquals(0, row.getOnlineMinutes());
        assertEquals(0.0, row.getRate(), 1e-9);
    }

    @Test
    void snapshotDeviceOnlineRates_lockBusy_returnsZeroWithoutWrite() {
        when(distributedLockService.tryLock(
                DeviceAvailabilityKpiService.deviceOnlineRateLockKey(DAY), 60L, 5L)).thenReturn(false);

        int written = service.snapshotDeviceOnlineRates(DAY);

        assertEquals(0, written);
        verify(onlineRateRepository, org.mockito.Mockito.never())
                .upsert(any(DeviceDailyOnlineRate.class));
    }
}
