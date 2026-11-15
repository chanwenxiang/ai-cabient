package com.aicabinet.trade.service;

import com.aicabinet.common.dto.SiteContractDto;
import com.aicabinet.common.dto.UpsertSiteContractRequest;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.SiteContract;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.SiteContractMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SiteContractServiceTest {

    private static final long OPERATOR_ID = 1900000001L;

    @Mock private SiteContractMapper contractRepository;
    @Mock private DeviceInfoMapper deviceRepository;
    @Mock private PermissionService permissionService;
    @Mock private AdminAuditService auditService;
    @Mock private DistributedLockService distributedLockService;

    private SiteContractService service;

    @BeforeEach
    void setUp() {
        // MA-224 的显式清列用 Wrappers.<SiteContract>lambdaUpdate()；纯单测里需先注册
        // MyBatis-Plus 的 lambda 列缓存，否则抛 "can not find lambda cache"。
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                SiteContract.class);
        org.mockito.Mockito.lenient().when(distributedLockService.tryLock(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        service = new SiteContractService(contractRepository, deviceRepository,
                permissionService, auditService, distributedLockService);
    }

    @Test
    void statusFor_shouldMarkExpiringAndExpired() {
        LocalDate today = LocalDate.now();
        assertEquals("ACTIVE", SiteContractService.statusFor(today.plusDays(60)));
        assertEquals("EXPIRING", SiteContractService.statusFor(today.plusDays(20)));
        assertEquals("EXPIRED", SiteContractService.statusFor(today.minusDays(1)));
        assertEquals("ACTIVE", SiteContractService.statusFor(null));
    }

    /**
     * R20 P2-16：合同列表原来为了拿「柜机名」把整张 device_info 全表载入。
     * 现在只按当页涉及到的 deviceId 取；查不到的那台回落成 deviceId 本身（与旧行为一致）。
     */
    @Test
    void listPage_loadsDeviceNamesOnlyForDevicesOnThisPage() {
        when(contractRepository.searchPage(0, 10)).thenReturn(pageOf(
                contract("CAB-001"), contract("CAB-404")));
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("CAB-001");
        device.setDeviceName("门店一号柜");
        when(deviceRepository.findByDeviceIdIn(any())).thenReturn(java.util.List.of(device));

        var result = service.listPage(OPERATOR_ID, 0, 10);

        assertEquals(2, result.items().size());
        assertEquals("门店一号柜", result.items().get(0).deviceName());
        assertEquals("CAB-404", result.items().get(1).deviceName(), "查不到设备时回落成 deviceId");
        verify(deviceRepository, org.mockito.Mockito.never()).findAllOrderByDeviceIdAsc();
    }

    /** 一页合同都没有时连设备表都不该碰（全表载入的旧实现在这里也会白跑一次）。 */
    @Test
    void listPage_withoutContracts_skipsDeviceQuery() {
        when(contractRepository.searchPage(0, 10)).thenReturn(pageOf());

        var result = service.listPage(OPERATOR_ID, 0, 10);

        assertEquals(0, result.items().size());
        verify(deviceRepository, org.mockito.Mockito.never()).findAllOrderByDeviceIdAsc();
        verify(deviceRepository, org.mockito.Mockito.never()).findByDeviceIdIn(any());
    }

    private static com.baomidou.mybatisplus.extension.plugins.pagination.Page<SiteContract> pageOf(
            SiteContract... contracts) {
        var page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<SiteContract>(1, 10);
        page.setRecords(java.util.List.of(contracts));
        page.setTotal(contracts.length);
        return page;
    }

    private static SiteContract contract(String deviceId) {
        SiteContract c = new SiteContract();
        c.setDeviceId(deviceId);
        c.setSiteName("点位-" + deviceId);
        c.setStatus("ACTIVE");
        c.setMonthlyFeeCents(50000);
        return c;
    }

    @Test
    void upsert_shouldCreateWithComputedStatus() {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("CAB-001");
        device.setDeviceName("门店一号柜");
        when(deviceRepository.findById("CAB-001")).thenReturn(Optional.of(device));
        when(contractRepository.findByDeviceIdForUpdate("CAB-001")).thenReturn(Optional.empty());

        SiteContractDto dto = service.upsert(OPERATOR_ID, "CAB-001",
                new UpsertSiteContractRequest("科技园 A 座", "深圳", "张先生", "13800138000",
                        LocalDate.now(), LocalDate.now().plusDays(15), 50000, null));

        assertEquals("门店一号柜", dto.deviceName());
        assertEquals("EXPIRING", dto.status());
        verify(contractRepository).insert(any(SiteContract.class));
        verify(auditService).appendLog(eq(OPERATOR_ID), any(), any(), any(), any());
    }
    /**
     * MA-224：运营清空起租/到期时，updateById 会跳过 null 列 ⇒ DB 留旧日期而 status 已按
     * null 重算 ACTIVE（页面与状态分叉，MA-273 的出账判据也跟着错）。必须显式 set(col,null)。
     */
    @Test
    void upsert_whenDatesCleared_explicitlyNullsDateColumns() {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("CAB-001");
        device.setDeviceName("门店一号柜");
        when(deviceRepository.findById("CAB-001")).thenReturn(Optional.of(device));
        SiteContract existing = new SiteContract();
        existing.setContractId(7L);
        existing.setDeviceId("CAB-001");
        existing.setSiteName("旧名");
        existing.setStartDate(LocalDate.now().minusDays(10));
        existing.setEndDate(LocalDate.now().plusDays(10));
        existing.setStatus("EXPIRING");
        when(contractRepository.findByDeviceIdForUpdate("CAB-001")).thenReturn(Optional.of(existing));

        service.upsert(OPERATOR_ID, "CAB-001",
                new UpsertSiteContractRequest("门店一号柜", null, null, null,
                        null, null, 50000, null));

        verify(contractRepository).update(
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any());
        assertEquals("ACTIVE", existing.getStatus(), "status 必须按清空后的 endDate 重算");
    }

}
