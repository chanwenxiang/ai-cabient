package com.aicabinet.trade.service;

import com.aicabinet.common.dto.UpsertMerchantOnboardingRequest;
import com.aicabinet.trade.config.AlipayProperties;
import com.aicabinet.trade.config.PayScoreProperties;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.MerchantPaymentOnboardingMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantOnboardingConcurrencyTest {

    @Mock private MerchantPaymentOnboardingMapper onboardingMapper;
    @Mock private MerchantMapper merchantMapper;
    @Mock private PermissionService permissionService;
    @Mock private MerchantScopeService merchantScopeService;
    @Mock private AdminAuditService auditService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private ApprovalWorkflowService approvalWorkflowService;

    private MerchantOnboardingService service;

    @BeforeEach
    void setUp() {
        service = new MerchantOnboardingService(onboardingMapper, merchantMapper, permissionService,
                merchantScopeService, auditService, new SecurityProperties(true),
                new WeChatPayProperties(false, "", "", "", "", "", "", "", true),
                new AlipayProperties(false, "", "", "", "", "", "", "", "", "", ""),
                new PayScoreProperties(false, false, 550, false, "", ""),
                distributedLockService, approvalWorkflowService);
    }

    @Test
    void upsert_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                MerchantOnboardingService.onboardingLockKey("M-1", "WECHAT"), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.upsert(1L, null,
                        new UpsertMerchantOnboardingRequest("M-1", "WECHAT", "DRAFT", null, null, null)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void upsert_whenMerchantNotFound_unlocksLock() {
        when(distributedLockService.tryLock(
                MerchantOnboardingService.onboardingLockKey("M-2", "ALIPAY"), 60L, 5L))
                .thenReturn(true);
        when(merchantMapper.findById("M-2")).thenReturn(java.util.Optional.empty());

        assertThrows(ResponseStatusException.class,
                () -> service.upsert(1L, null,
                        new UpsertMerchantOnboardingRequest("M-2", "ALIPAY", "DRAFT", null, null, null)));

        verify(distributedLockService).unlock(
                MerchantOnboardingService.onboardingLockKey("M-2", "ALIPAY"));
    }
}
