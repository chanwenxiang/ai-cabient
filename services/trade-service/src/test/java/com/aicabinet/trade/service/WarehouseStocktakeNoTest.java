package com.aicabinet.trade.service;

import com.aicabinet.common.dto.CreateStocktakeRequest;
import com.aicabinet.trade.domain.Warehouse;
import com.aicabinet.trade.domain.WarehouseStocktake;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.WarehouseInventoryMapper;
import com.aicabinet.trade.mapper.WarehouseMapper;
import com.aicabinet.trade.mapper.WarehouseStocktakeLineMapper;
import com.aicabinet.trade.mapper.WarehouseStocktakeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 盘点单号 {@code PDyyyyMMdd-NNN}（2026-10-28 起，替代 BizIds 22 位纯数字）：
 * 按北京时间自然日重置、三位流水、跨单自查占用。锁语义见 {@link #create}。
 */
@ExtendWith(MockitoExtension.class)
class WarehouseStocktakeNoTest {

    private static final ZoneId BIZ = ZoneId.of("Asia/Shanghai");
    private static final String TODAY =
            LocalDate.now(BIZ).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    private static final String PREFIX = "PD" + TODAY + "-";

    @Mock private PermissionService permissionService;
    @Mock private WarehouseStocktakeMapper stocktakeRepository;
    @Mock private WarehouseStocktakeLineMapper lineRepository;
    @Mock private WarehouseMapper warehouseRepository;
    @Mock private WarehouseInventoryMapper inventoryRepository;
    @Mock private SkuCatalogMapper skuCatalogRepository;
    @Mock private WarehouseService warehouseService;
    @Mock private DistributedLockService distributedLockService;

    private WarehouseStocktakeService service;

    @BeforeEach
    void setUp() {
        service = new WarehouseStocktakeService(permissionService, stocktakeRepository,
                lineRepository, warehouseRepository, inventoryRepository, skuCatalogRepository,
                warehouseService, distributedLockService, null);
        ReflectionTestUtils.setField(service, "self", service);
        lenient().when(distributedLockService.tryLock(anyString(), eq(60L), eq(5L))).thenReturn(true);
        lenient().when(warehouseRepository.findById("WH-001")).thenReturn(Optional.of(warehouse()));
        lenient().when(inventoryRepository.findByWarehouseIdOrderByExpiryDateAsc("WH-001"))
                .thenReturn(List.of());
        lenient().when(stocktakeRepository.save(any())).thenAnswer(inv -> {
            WarehouseStocktake st = inv.getArgument(0);
            st.setStocktakeId(9L);
            return st;
        });
        lenient().when(lineRepository.findByStocktakeIdOrderByLineIdAsc(9L)).thenReturn(List.of());
    }

    @Test
    void firstOfDay_gets001() {
        when(stocktakeRepository.findMaxNoByPrefix(PREFIX)).thenReturn(null);
        when(stocktakeRepository.existsByNo(anyString())).thenReturn(false);

        assertEquals(PREFIX + "001", create());
    }

    @Test
    void afterExisting007_gets008() {
        when(stocktakeRepository.findMaxNoByPrefix(PREFIX)).thenReturn(PREFIX + "007");
        when(stocktakeRepository.existsByNo(anyString())).thenReturn(false);

        assertEquals(PREFIX + "008", create());
    }

    @Test
    void skipsNumberTakenByConcurrentOrLegacyRow() {
        when(stocktakeRepository.findMaxNoByPrefix(PREFIX)).thenReturn(PREFIX + "007");
        when(stocktakeRepository.existsByNo(PREFIX + "008")).thenReturn(true);
        when(stocktakeRepository.existsByNo(PREFIX + "009")).thenReturn(false);

        assertEquals(PREFIX + "009", create());
    }

    @Test
    void dirtySuffix_restartsFrom001AndStillAvoidsCollision() {
        // 历史脏号（前缀对、后缀非数字）不得让建单 500：回落 001 起，逐号自查
        when(stocktakeRepository.findMaxNoByPrefix(PREFIX)).thenReturn(PREFIX + "ABC");
        when(stocktakeRepository.existsByNo(PREFIX + "001")).thenReturn(true);
        when(stocktakeRepository.existsByNo(PREFIX + "002")).thenReturn(false);

        assertEquals(PREFIX + "002", create());
    }

    @Test
    void lockBusy_rejectsWithConflict_andNeverPersists() {
        when(distributedLockService.tryLock(anyString(), eq(60L), eq(5L))).thenReturn(false);

        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, this::create);

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(stocktakeRepository, never()).save(any());
    }

    @Test
    void failureInsideTransaction_stillReleasesNoLock() {
        when(warehouseRepository.findById("WH-001")).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class, this::create);

        verify(distributedLockService).unlock(eq(WarehouseStocktakeService.stocktakeNoLockKey(
                LocalDate.now(BIZ))));
    }

    private String create() {
        return service.create(1L, new CreateStocktakeRequest("WH-001", "OPEN", null)).stocktakeNo();
    }

    private static Warehouse warehouse() {
        Warehouse w = new Warehouse();
        w.setWarehouseId("WH-001");
        w.setWarehouseName("测试仓");
        w.setStatus("ACTIVE");
        return w;
    }
}
