package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OpsUserDeviceScopeDto;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;

import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceOpsEventMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.MerchantOpsConfigMapper;
import com.aicabinet.trade.mapper.MerchantRoleTemplateMapper;
import com.aicabinet.trade.mapper.OpsUserDeviceScopeMapper;
import com.aicabinet.trade.mapper.OpsUserDeviceScopePrefMapper;
import com.aicabinet.trade.mapper.OpsUserRouteScopeMapper;
import com.aicabinet.trade.mapper.PhoneVerifyLogMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.service.DistributedLockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * G2-12 ⑥L3/L4/L5 实测修复的回归钉：
 * 货柜范围勾选必须落在「目标账号商户范围（绑定+下级闭包）名下柜机」内、幽灵号/乱填线路码 400 业务驳回
 * （此前：越范围勾选静默交集 0、幽灵号撞 DB 外键成 500），审计 detail 必须带上勾选清单（此前只有 mode）。
 */
@ExtendWith(MockitoExtension.class)
class CompetitiveGapServiceDeviceScopeValidationTest {

    private static final long OPERATOR = 1L;
    private static final long TARGET = 100000045L;
    private static final String LOCK_KEY = CompetitiveGapService.opsDeviceScopeLockKey(TARGET);

    @Mock private OpsUserDeviceScopeMapper deviceScopeMapper;
    @Mock private OpsUserDeviceScopePrefMapper deviceScopePrefMapper;
    @Mock private MerchantOpsConfigMapper opsConfigMapper;
    @Mock private MerchantRoleTemplateMapper roleTemplateMapper;
    @Mock private DeviceOpsEventMapper deviceOpsEventMapper;
    @Mock private DeviceInfoMapper deviceInfoMapper;
    @Mock private PhoneVerifyLogMapper phoneVerifyLogMapper;
    @Mock private MerchantMapper merchantMapper;
    @Mock private CabinetOrderMapper orderMapper;
    @Mock private CabinetOrderLineMapper lineMapper;
    @Mock private MerchantScopeService merchantScopeService;
    @Mock private PermissionService permissionService;
    @Mock private AdminAuditService auditService;
    @Mock private DeviceSalesLockService salesLockService;
    @Mock private OpsUserRouteScopeMapper routeScopeMapper;
    @Mock private DistributedLockService distributedLockService;
    @Mock private SkuCatalogMapper skuCatalogMapper;

    private CompetitiveGapService service;

    @BeforeEach
    void setUp() {
        service = new CompetitiveGapService(deviceScopeMapper, deviceScopePrefMapper, opsConfigMapper,
                roleTemplateMapper, deviceOpsEventMapper, deviceInfoMapper, phoneVerifyLogMapper,
                merchantMapper, orderMapper, lineMapper, merchantScopeService, permissionService,
                auditService, salesLockService, new SecurityProperties(false),
                routeScopeMapper, distributedLockService, skuCatalogMapper, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        lenient().when(distributedLockService.tryLock(LOCK_KEY, 60L, 5L)).thenReturn(true);
        lenient().when(deviceScopePrefMapper.findByIdForUpdate(anyLong())).thenReturn(Optional.empty());
        lenient().when(deviceScopePrefMapper.findById(anyLong())).thenReturn(Optional.empty());
        lenient().when(deviceScopeMapper.findByUserId(anyLong())).thenReturn(List.of());
        lenient().when(routeScopeMapper.findByUserId(anyLong())).thenReturn(List.of());
    }

    private static DeviceInfo device(String deviceId, String routeCode) {
        DeviceInfo d = new DeviceInfo();
        d.setDeviceId(deviceId);
        d.setRouteCode(routeCode);
        return d;
    }

    @Test
    void foreignDevice_rejectedWith400_beforeAnyWrite() {
        when(merchantScopeService.assignableDeviceCandidates(TARGET)).thenReturn(Set.of("EG-OK"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.assignUserDeviceScope(OPERATOR, TARGET,
                        new OpsUserDeviceScopeDto(TARGET, "DEVICE_IDS", List.of("CAB-OTHER"), List.of())));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("不在该账号的商户范围内"), ex.getReason());
        // 先校验后写：pref/两个 delete/insert/审计都不允许发生
        verify(deviceScopeMapper, never()).insert(any());
        verify(deviceScopeMapper, never()).deleteByUserId(anyLong());
        verify(routeScopeMapper, never()).deleteByUserId(anyLong());
        verify(auditService, never()).appendLog(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void ghostDevice_rejectedWith400_insteadOfDbFk500() {
        when(merchantScopeService.assignableDeviceCandidates(TARGET)).thenReturn(Set.of("EG-OK"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.assignUserDeviceScope(OPERATOR, TARGET,
                        new OpsUserDeviceScopeDto(TARGET, "DEVICE_IDS", List.of("EG-GHOST-S12"), List.of())));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("EG-GHOST-S12"), ex.getReason());
    }

    @Test
    void validDevices_stored_andAuditDetailCarriesCheckedList() {
        when(merchantScopeService.assignableDeviceCandidates(TARGET)).thenReturn(Set.of("EG-A", "EG-B"));

        service.assignUserDeviceScope(OPERATOR, TARGET,
                new OpsUserDeviceScopeDto(TARGET, "DEVICE_IDS", List.of("EG-B", "EG-A"), List.of()));

        verify(deviceScopeMapper, times(2)).insert(any());
        verify(auditService).appendLog(OPERATOR, "OPS_USER_DEVICE_SCOPE", "USER", String.valueOf(TARGET),
                "mode=DEVICE_IDS, devices=[EG-A, EG-B], routes=[]");
    }

    @Test
    void allMode_auditDetailStaysModeOnly() {
        when(merchantScopeService.assignableDeviceCandidates(TARGET)).thenReturn(Set.of());

        service.assignUserDeviceScope(OPERATOR, TARGET,
                new OpsUserDeviceScopeDto(TARGET, "ALL", List.of(), List.of()));

        verify(auditService).appendLog(OPERATOR, "OPS_USER_DEVICE_SCOPE", "USER", String.valueOf(TARGET),
                "mode=ALL");
    }

    @Test
    void globalTarget_ghostDevice_stillRejectedByExistenceCheck() {
        when(merchantScopeService.assignableDeviceCandidates(TARGET)).thenReturn(null);
        when(deviceInfoMapper.selectBatchIds(anyCollection())).thenReturn(List.of());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.assignUserDeviceScope(OPERATOR, TARGET,
                        new OpsUserDeviceScopeDto(TARGET, "DEVICE_IDS", List.of("EG-GHOST-S12"), List.of())));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("不存在"), ex.getReason());
    }

    @Test
    void unknownRouteCode_rejectedWith400() {
        when(merchantScopeService.assignableDeviceCandidates(TARGET)).thenReturn(Set.of("EG-A"));
        when(deviceInfoMapper.selectBatchIds(anyCollection())).thenReturn(List.of(device("EG-A", "R-1")));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.assignUserDeviceScope(OPERATOR, TARGET,
                        new OpsUserDeviceScopeDto(TARGET, "ROUTE", List.of(), List.of("R-UAT-S12"))));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("R-UAT-S12"), ex.getReason());
        verify(routeScopeMapper, never()).insert(any());
    }

    @Test
    void knownRouteCode_stored_andAuditDetailCarriesRoute() {
        when(merchantScopeService.assignableDeviceCandidates(TARGET)).thenReturn(Set.of("EG-A"));
        when(deviceInfoMapper.selectBatchIds(anyCollection())).thenReturn(List.of(device("EG-A", "R-1")));

        service.assignUserDeviceScope(OPERATOR, TARGET,
                new OpsUserDeviceScopeDto(TARGET, "ROUTE", List.of(), List.of("R-1")));

        verify(routeScopeMapper).insert(any());
        verify(auditService).appendLog(OPERATOR, "OPS_USER_DEVICE_SCOPE", "USER", String.valueOf(TARGET),
                "mode=ROUTE, devices=[], routes=[R-1]");
    }

    @Test
    void globalTarget_routeValidatedAgainstAllDevices() {
        when(merchantScopeService.assignableDeviceCandidates(TARGET)).thenReturn(null);
        when(deviceInfoMapper.selectList(any())).thenReturn(List.of(device("EG-A", "R-1")));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.assignUserDeviceScope(OPERATOR, TARGET,
                        new OpsUserDeviceScopeDto(TARGET, "ROUTE", List.of(), List.of("R-9"))));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("R-9"), ex.getReason());
    }
}
