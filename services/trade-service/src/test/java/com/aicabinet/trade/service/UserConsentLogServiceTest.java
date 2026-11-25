package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.UserConsentLog;
import com.aicabinet.trade.mapper.UserConsentLogMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UserConsentLogServiceTest {

    @Mock private UserConsentLogMapper consentLogMapper;

    private UserConsentLogService service() {
        UserConsentLogService service = new UserConsentLogService(consentLogMapper, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        return service;
    }

    @Test
    void privacyConsent_requiresNewTransaction_refreshStaysReadOnly() throws Exception {
        Transactional consentTx = UserConsentLogService.class
                .getMethod("recordPrivacyConsentInNewTransaction", Long.class)
                .getAnnotation(Transactional.class);
        assertNotNull(consentTx);
        assertEquals(Propagation.REQUIRES_NEW, consentTx.propagation());

        Transactional refreshTx = AuthService.class
                .getMethod("refreshSession", Long.class)
                .getAnnotation(Transactional.class);
        assertNotNull(refreshTx);
        assertTrue(refreshTx.readOnly());
    }

    @Test
    void accountDelete_doesNotStartIndependentTransaction() throws Exception {
        Transactional tx = UserConsentLogService.class
                .getMethod("recordAccountDelete", Long.class)
                .getAnnotation(Transactional.class);
        assertNull(tx, "注销外层已持 UserInfo 行锁，不能另起事务与 FK 检查互锁");
    }

    @Test
    void accountDelete_withinTransaction_defersWriteUntilAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service().recordAccountDelete(100L);

            verify(consentLogMapper, never()).insertIgnoreConflict(anyLong(), anyString(), anyString());
            assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
            TransactionSynchronization synchronization = TransactionSynchronizationManager.getSynchronizations().get(0);
            synchronization.afterCommit();

            verify(consentLogMapper).insertIgnoreConflict(100L,
                    UserConsentLog.TYPE_ACCOUNT_DELETE, UserConsentLogService.PRIVACY_POLICY_VERSION);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void accountDelete_afterCommitFailure_doesNotThrowOrRollbackDeletion() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            doThrow(new RuntimeException("db down")).when(consentLogMapper)
                    .insertIgnoreConflict(anyLong(), anyString(), anyString());
            service().recordAccountDelete(100L);
            TransactionSynchronization synchronization = TransactionSynchronizationManager.getSynchronizations().get(0);

            assertDoesNotThrow(synchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void accountDelete_withoutTransaction_writesImmediatelyAndSwallowsFailure() {
        assertDoesNotThrow(() -> service().recordAccountDelete(100L));
        verify(consentLogMapper).insertIgnoreConflict(100L,
                UserConsentLog.TYPE_ACCOUNT_DELETE, UserConsentLogService.PRIVACY_POLICY_VERSION);
    }

    @Test
    void accountDelete_withoutTransaction_writeFailureIsNonBlocking() {
        doThrow(new RuntimeException("db down")).when(consentLogMapper)
                .insertIgnoreConflict(anyLong(), anyString(), anyString());

        assertDoesNotThrow(() -> service().recordAccountDelete(100L));
    }

    @Test
    void privacyConsent_insertsIdempotently() {
        service().recordPrivacyConsent(100L);
        verify(consentLogMapper).insertIgnoreConflict(100L,
                UserConsentLog.TYPE_PRIVACY_POLICY, UserConsentLogService.PRIVACY_POLICY_VERSION);
    }

    @Test
    void privacyConsent_nullUser_skipsWrite() {
        service().recordPrivacyConsent(null);
        verify(consentLogMapper, never()).insertIgnoreConflict(anyLong(), anyString(), anyString());
    }

    @Test
    void privacyConsent_writeFailure_doesNotBreakLogin() {
        doThrow(new RuntimeException("db down")).when(consentLogMapper)
                .insertIgnoreConflict(anyLong(), anyString(), anyString());
        assertDoesNotThrow(() -> service().recordPrivacyConsent(100L));
    }
}
