package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.MerchantMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantIdServiceTest {

    @Mock private MerchantMapper merchantRepository;
    @Mock private DistributedLockService distributedLockService;

    @Test
    void resolveForCreate_rejectsManualInput() {
        MerchantIdService service = new MerchantIdService(merchantRepository, distributedLockService);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.resolveForCreate("MCH-EAST"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void allocateRandomMerchantId_returnsTwelveDigits() {
        when(distributedLockService.tryLock("merchant:id:allocate", 30L, 5L)).thenReturn(true);
        when(merchantRepository.selectById(anyString())).thenReturn(null);

        MerchantIdService service = new MerchantIdService(merchantRepository, distributedLockService);
        String id = service.resolveForCreate(null);

        assertTrue(MerchantIdService.isStandardMerchantId(id));
        verify(distributedLockService).unlock("merchant:id:allocate");
    }
}
