package com.aicabinet.trade.service;

import com.aicabinet.common.dto.UpsertSiteRentSplitRulesRequest;
import com.aicabinet.trade.mapper.SiteContractMapper;
import com.aicabinet.trade.mapper.SiteRentSplitRuleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SiteRentSplitConcurrencyTest {

    @Mock private SiteRentSplitRuleMapper ruleMapper;
    @Mock private SiteContractMapper contractMapper;
    @Mock private PermissionService permissionService;
    @Mock private AdminAuditService auditService;
    @Mock private DistributedLockService distributedLockService;

    private SiteRentSplitService service;

    @BeforeEach
    void setUp() {
        service = new SiteRentSplitService(ruleMapper, contractMapper, permissionService,
                auditService, distributedLockService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    void replaceRules_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                SiteRentSplitService.contractRulesLockKey(5L), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.replaceRules(1L, 5L,
                        new UpsertSiteRentSplitRulesRequest(List.of(
                                new UpsertSiteRentSplitRulesRequest.Rule(
                                        null, "PLATFORM", null, 10000, 0, "ACTIVE", null, null)))));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void replaceRules_whenContractNotFound_unlocksLock() {
        when(distributedLockService.tryLock(
                SiteRentSplitService.contractRulesLockKey(6L), 60L, 5L))
                .thenReturn(true);
        when(contractMapper.findByIdForUpdate(6L)).thenReturn(java.util.Optional.empty());

        assertThrows(ResponseStatusException.class,
                () -> service.replaceRules(1L, 6L,
                        new UpsertSiteRentSplitRulesRequest(List.of(
                                new UpsertSiteRentSplitRulesRequest.Rule(
                                        null, "PLATFORM", null, 10000, 0, "ACTIVE", null, null)))));

        verify(distributedLockService).unlock(SiteRentSplitService.contractRulesLockKey(6L));
    }
}
