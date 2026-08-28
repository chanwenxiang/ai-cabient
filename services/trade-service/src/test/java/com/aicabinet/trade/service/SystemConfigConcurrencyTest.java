package com.aicabinet.trade.service;

import com.aicabinet.trade.config.AlipayProperties;
import com.aicabinet.trade.config.PayScoreProperties;
import com.aicabinet.trade.config.QrProperties;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.WeChatMiniAppProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.config.WeChatWebProperties;
import com.aicabinet.trade.mapper.SystemConfigMapper;
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
class SystemConfigConcurrencyTest {

    @Mock private SystemConfigMapper repository;
    @Mock private DistributedLockService distributedLockService;

    private SystemConfigService service;

    @BeforeEach
    void setUp() {
        service = new SystemConfigService(
                repository,
                new SecurityProperties(false),
                new AlipayProperties(false, "", "", "", "", "", "", "", "", ""),
                new WeChatPayProperties(false, "", "", "", "", "", "", "", true),
                new PayScoreProperties(false, false, 550, false, "", ""),
                new WeChatWebProperties(false, "", ""),
                new WeChatMiniAppProperties(false, "", "", "", "", "", ""),
                new QrProperties("", "", "", "", ""),
                distributedLockService,
                null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    void upsert_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                SystemConfigService.systemConfigLockKey("test.key"), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.upsert("test.key", "v", "desc"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void delete_whenNotFound_unlocksLock() {
        when(distributedLockService.tryLock(
                SystemConfigService.systemConfigLockKey("missing.key"), 60L, 5L))
                .thenReturn(true);
        when(repository.findByIdForUpdate("missing.key")).thenReturn(java.util.Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.delete("missing.key"));

        verify(distributedLockService).unlock(SystemConfigService.systemConfigLockKey("missing.key"));
    }
}
