package com.aicabinet.trade.service;

import com.aicabinet.common.dto.UpsertMerchantRequest;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.config.ProfitSharingProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.payment.WeChatProfitSharingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantConcurrencyTest {

    @Mock private MerchantMapper merchantRepository;
    @Mock private DeviceInfoMapper deviceRepository;
    @Mock private OrderRevenueSplitMapper splitRepository;
    @Mock private PermissionService permissionService;
    @Mock private AdminAuditService auditService;
    @Mock private MerchantScopeService merchantScopeService;
    @Mock private WeChatProfitSharingService profitSharingService;
    @Mock private RevenueSplitService revenueSplitService;
    @Mock private DistributedLockService distributedLockService;

    private MerchantService service;

    @BeforeEach
    void setUp() {
        service = new MerchantService(merchantRepository, deviceRepository, splitRepository,
                permissionService, auditService, merchantScopeService, profitSharingService,
                revenueSplitService, new ProfitSharingProperties(false, false, false, 20),
                new WeChatPayProperties(false, "", "", "", "", "", "", "", true),
                distributedLockService);
    }

    @Test
    void upsertMerchant_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(MerchantService.merchantLockKey("M-1")), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.upsertMerchant(1L,
                        new UpsertMerchantRequest("M-1", "测试商户", null, 1000, null,
                                "ACTIVE", null, null, null, null, null, null, null)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void upsertMerchant_whenPermissionDenied_unlocksLock() {
        when(distributedLockService.tryLock(
                eq(MerchantService.merchantLockKey("M-2")), eq(60L), eq(5L)))
                .thenReturn(true);
        org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "denied"))
                .when(permissionService).requirePermission(1L, "ops:merchant:edit");

        assertThrows(ResponseStatusException.class,
                () -> service.upsertMerchant(1L,
                        new UpsertMerchantRequest("M-2", "测试商户", null, 1000, null,
                                "ACTIVE", null, null, null, null, null, null, null)));

        verify(distributedLockService).unlock(MerchantService.merchantLockKey("M-2"));
    }
}
