package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.OpsException;
import com.aicabinet.trade.mapper.OpsExceptionMapper;
import com.aicabinet.trade.service.support.OpsExceptionServiceSupport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpsExceptionOfflineAutoLockResolveTest {

    @Test
    void isOfflineAutoLockTitleMatchesPrefix() {
        assertTrue(OpsExceptionService.isOfflineAutoLockTitle("离线超时自动停售"));
        assertTrue(OpsExceptionService.isOfflineAutoLockTitle("离线超时自动停售（补充）"));
        assertFalse(OpsExceptionService.isOfflineAutoLockTitle("门锁故障"));
        assertFalse(OpsExceptionService.isOfflineAutoLockTitle(null));
    }

    @Test
    void resolveOfflineAutoLockFaultClosesMatchingOpenFault() {
        OpsExceptionMapper repository = mock(OpsExceptionMapper.class);
        PermissionService permission = mock(PermissionService.class);
        OpsExceptionServiceSupport support = mock(OpsExceptionServiceSupport.class);
        AdminAuditService audit = mock(AdminAuditService.class);
        DistributedLockService lock = mock(DistributedLockService.class);
        when(lock.tryLock(anyString(), eq(60L), eq(5L))).thenReturn(true);
        when(support.auditService()).thenReturn(audit);

        OpsException open = new OpsException();
        open.setExceptionId("E-1");
        open.setExceptionType("DEVICE_FAULT");
        open.setStatus("OPEN");
        open.setTitle(OpsExceptionService.OFFLINE_AUTO_LOCK_TITLE);
        open.setDedupKey("DEVICE_FAULT:CAB-009");
        when(repository.findFirstByDedupKeyAndStatusIn(eq("DEVICE_FAULT:CAB-009"), any()))
                .thenReturn(Optional.of(open));
        when(repository.findByIdForUpdate("E-1")).thenReturn(Optional.of(open));
        when(repository.save(any(OpsException.class))).thenAnswer(inv -> inv.getArgument(0));

        OpsExceptionService service = new OpsExceptionService(repository, permission,
                mock(MerchantScopeService.class), support, lock, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        service.resolveOfflineAutoLockFault("CAB-009", "测试关闭");

        assertTrue("RESOLVED".equals(open.getStatus()));
        verify(audit).appendLog(eq(0L), eq("OPS_EXCEPTION_AUTO_RESOLVE"), eq("OPS_EXCEPTION"),
                eq("E-1"), eq("测试关闭"));
    }

    @Test
    void resolveOfflineAutoLockFaultSkipsUnrelatedFault() {
        OpsExceptionMapper repository = mock(OpsExceptionMapper.class);
        PermissionService permission = mock(PermissionService.class);
        OpsExceptionServiceSupport support = mock(OpsExceptionServiceSupport.class);
        DistributedLockService lock = mock(DistributedLockService.class);
        when(lock.tryLock(anyString(), eq(60L), eq(5L))).thenReturn(true);

        OpsException open = new OpsException();
        open.setExceptionId("E-2");
        open.setStatus("OPEN");
        open.setTitle("摄像头故障");
        when(repository.findFirstByDedupKeyAndStatusIn(eq("DEVICE_FAULT:CAB-010"), any()))
                .thenReturn(Optional.of(open));

        OpsExceptionService service = new OpsExceptionService(repository, permission,
                mock(MerchantScopeService.class), support, lock, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        service.resolveOfflineAutoLockFault("CAB-010", "不应关闭");

        verify(repository, never()).findByIdForUpdate(anyString());
        verify(repository, never()).save(any());
        assertTrue("OPEN".equals(open.getStatus()));
    }
}
