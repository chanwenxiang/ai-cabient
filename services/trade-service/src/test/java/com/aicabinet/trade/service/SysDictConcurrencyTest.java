package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DictDtos;
import com.aicabinet.trade.mapper.SysDictDataMapper;
import com.aicabinet.trade.mapper.SysDictTypeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SysDictConcurrencyTest {

    @Mock private SysDictTypeMapper typeRepository;
    @Mock private SysDictDataMapper dataRepository;
    @Mock private PermissionService permissionService;
    @Mock private DistributedLockService distributedLockService;

    private SysDictService service;

    @BeforeEach
    void setUp() {
        doNothing().when(permissionService).requirePermission(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
        service = new SysDictService(typeRepository, dataRepository, permissionService, null, distributedLockService);
    }

    @Test
    void upsertType_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                SysDictService.dictTypeLockKey("order_status"), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.upsertType(1L, new DictDtos.DictTypeUpsertRequest(
                        "order_status", "订单状态", "ACTIVE", null, 0)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void deleteItem_whenNotFound_unlocksLock() {
        when(distributedLockService.tryLock(
                SysDictService.dictDataIdLockKey(9L), 60L, 5L))
                .thenReturn(true);
        when(dataRepository.findByIdForUpdate(9L)).thenReturn(java.util.Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.deleteItem(1L, 9L));

        verify(distributedLockService).unlock(SysDictService.dictDataIdLockKey(9L));
    }
}
