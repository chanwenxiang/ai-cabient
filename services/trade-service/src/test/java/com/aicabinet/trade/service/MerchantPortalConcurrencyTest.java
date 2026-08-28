package com.aicabinet.trade.service;

import com.aicabinet.common.dto.UpdateMerchantDeviceSettingsRequest;
import com.aicabinet.trade.support.MerchantPortalGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantPortalConcurrencyTest {

    @Mock private MerchantFinanceService merchantFinanceService;
    @Mock private PermissionService permissionService;
    @Mock private MerchantScopeService merchantScopeService;
    @Mock private MerchantPortalGuard merchantPortalGuard;
    @Mock private com.aicabinet.trade.mapper.UserInfoMapper userInfoRepository;
    @Mock private com.aicabinet.trade.mapper.UserAccountMapper userAccountRepository;
    @Mock private com.aicabinet.trade.mapper.OpsUserMerchantMapper userMerchantRepository;
    @Mock private com.aicabinet.trade.mapper.OpsUserRoleMapper userRoleRepository;
    @Mock private com.aicabinet.trade.mapper.OpsRoleMapper roleRepository;
    @Mock private com.aicabinet.trade.mapper.OpsPermissionMapper permissionRepository;
    @Mock private com.aicabinet.trade.mapper.MerchantMapper merchantRepository;
    @Mock private com.aicabinet.trade.mapper.DeviceInfoMapper deviceRepository;
    @Mock private com.aicabinet.trade.mapper.CabinetOrderMapper orderRepository;
    @Mock private com.aicabinet.trade.mapper.OrderRevenueSplitMapper splitRepository;
    @Mock private com.aicabinet.trade.mapper.ShoppingSessionMapper sessionRepository;
    @Mock private com.aicabinet.trade.mapper.ReplenishmentTaskMapper replenishmentTaskRepository;
    @Mock private com.aicabinet.trade.mapper.ReplenishmentTaskLineMapper replenishmentTaskLineRepository;
    @Mock private com.aicabinet.trade.mapper.DisputeTicketMapper disputeRepository;
    @Mock private com.aicabinet.trade.mapper.DeviceSkuInventoryMapper inventoryRepository;
    @Mock private com.aicabinet.trade.mapper.PullOffTaskMapper pullOffTaskRepository;
    @Mock private DeviceSlotService deviceSlotService;
    @Mock private InventoryLotService inventoryLotService;
    @Mock private AdminAuditService auditService;
    @Mock private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @Mock private com.aicabinet.trade.mapper.DeviceTemperatureReadingMapper temperatureReadingRepository;
    @Mock private com.aicabinet.trade.client.DeviceServiceClient deviceServiceClient;
    @Mock private com.aicabinet.trade.payment.WeChatProfitSharingService profitSharingService;
    @Mock private com.aicabinet.trade.config.ProfitSharingProperties profitSharingProperties;
    @Mock private com.aicabinet.trade.config.WeChatPayProperties weChatPayProperties;
    @Mock private OperatorUserIdAllocator operatorUserIdAllocator;
    @Mock private MerchantSelfServiceGate merchantSelfServiceGate;
    @Mock private MerchantFeaturePackService merchantFeaturePackService;
    @Mock private DistributedLockService distributedLockService;

    private MerchantPortalService service;

    @BeforeEach
    void setUp() {
        service = new MerchantPortalService(merchantFinanceService, permissionService, merchantScopeService,
                merchantPortalGuard, userInfoRepository, userAccountRepository, userMerchantRepository,
                userRoleRepository, roleRepository, permissionRepository, merchantRepository, deviceRepository,
                orderRepository, splitRepository, sessionRepository, replenishmentTaskRepository,
                replenishmentTaskLineRepository, disputeRepository, inventoryRepository, pullOffTaskRepository,
                deviceSlotService, inventoryLotService, auditService, passwordEncoder, temperatureReadingRepository,
                deviceServiceClient, profitSharingService, profitSharingProperties, weChatPayProperties,
                operatorUserIdAllocator, merchantSelfServiceGate, merchantFeaturePackService, distributedLockService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    void updateDeviceSettings_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                DeviceAssetService.deviceAssetLockKey("CAB-MP"), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.updateDeviceSettings(1L, "CAB-MP",
                        new UpdateMerchantDeviceSettingsRequest("name", null, null, null, null)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void createTeamUser_whenPhoneLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                MerchantPortalService.merchantTeamPhoneLockKey("13900009999"), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.createTeamUser(1L,
                        new com.aicabinet.common.dto.CreateMerchantUserRequest(
                                "13900009999", "pass12", "成员", "merchant_staff")));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }
}
