package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.UserConsentLog;
import com.aicabinet.trade.mapper.UserConsentLogMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    @Test
    void privacyConsent_requiresNewTransaction_refreshStaysReadOnly() throws Exception {
        Transactional consentTx = UserConsentLogService.class
                .getMethod("recordPrivacyConsent", Long.class)
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
    void accountDelete_joinsWritableOuterTransaction() throws Exception {
        Transactional tx = UserConsentLogService.class
                .getMethod("recordAccountDelete", Long.class)
                .getAnnotation(Transactional.class);
        assertNull(tx, "注销外层已持 UserInfo 行锁，不能另起事务与 FK 检查互锁");
    }

    @Test
    void privacyConsent_insertsIdempotently() {
        new UserConsentLogService(consentLogMapper).recordPrivacyConsent(100L);
        verify(consentLogMapper).insertIgnoreConflict(100L,
                UserConsentLog.TYPE_PRIVACY_POLICY, UserConsentLogService.PRIVACY_POLICY_VERSION);
    }

    @Test
    void privacyConsent_nullUser_skipsWrite() {
        new UserConsentLogService(consentLogMapper).recordPrivacyConsent(null);
        verify(consentLogMapper, never()).insertIgnoreConflict(anyLong(), anyString(), anyString());
    }

    @Test
    void privacyConsent_writeFailure_doesNotBreakLogin() {
        doThrow(new RuntimeException("db down")).when(consentLogMapper)
                .insertIgnoreConflict(anyLong(), anyString(), anyString());
        assertDoesNotThrow(() -> new UserConsentLogService(consentLogMapper).recordPrivacyConsent(100L));
    }
}
