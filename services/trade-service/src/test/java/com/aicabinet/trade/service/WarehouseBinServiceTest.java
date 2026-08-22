package com.aicabinet.trade.service;

import com.aicabinet.common.dto.BinInboundRequest;
import com.aicabinet.common.dto.BinMoveRequest;
import com.aicabinet.common.dto.UpsertWarehouseBinRequest;
import com.aicabinet.trade.domain.Warehouse;
import com.aicabinet.trade.domain.WarehouseBin;
import com.aicabinet.trade.domain.WarehouseBinStock;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.WarehouseBinMapper;
import com.aicabinet.trade.mapper.WarehouseBinStockMapper;
import com.aicabinet.trade.mapper.WarehouseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WarehouseBinServiceTest {

    @Mock private PermissionService permissionService;
    @Mock private WarehouseBinMapper binRepository;
    @Mock private WarehouseBinStockMapper binStockRepository;
    @Mock private WarehouseMapper warehouseRepository;
    @Mock private SkuCatalogMapper skuCatalogRepository;
    @Mock private WarehouseService warehouseService;
    @Mock private DistributedLockService distributedLockService;

    private WarehouseBinService service;

    @BeforeEach
    void setUp() {
        service = new WarehouseBinService(permissionService, binRepository,
                binStockRepository, warehouseRepository, skuCatalogRepository, warehouseService,
                distributedLockService);
        when(distributedLockService.tryLock(anyString(), eq(60L), eq(5L))).thenReturn(true);
    }

    @Test
    void upsertBin_shouldCreateNewBin() {
        when(warehouseRepository.findById("WH-001")).thenReturn(Optional.of(warehouse()));
        when(binRepository.findByWarehouseIdAndBinCode("WH-001", "A-01"))
                .thenReturn(Optional.empty());
        when(binRepository.save(any())).thenAnswer(a -> a.getArgument(0));

        var dto = service.upsertBin(1L,
                new UpsertWarehouseBinRequest("WH-001", "A-01", "货架A第1层", "ACTIVE"));

        assertEquals("A-01", dto.binCode());
        assertEquals("货架A第1层", dto.binName());
        assertEquals("WH-001", dto.warehouseId());
        verify(permissionService).requirePermission(1L, "ops:warehouse:edit");
    }

    @Test
    void upsertBin_shouldUpdateExisting() {
        WarehouseBin existing = bin(1L, "WH-001", "A-01", "旧名");
        when(warehouseRepository.findById("WH-001")).thenReturn(Optional.of(warehouse()));
        when(binRepository.findByWarehouseIdAndBinCode("WH-001", "A-01"))
                .thenReturn(Optional.of(existing));
        when(binRepository.save(any())).thenAnswer(a -> a.getArgument(0));

        service.upsertBin(1L, new UpsertWarehouseBinRequest("WH-001", "A-01", "新名", "INACTIVE"));

        assertEquals("新名", existing.getBinName());
        assertEquals("INACTIVE", existing.getStatus());
    }

    @Test
    void listBinStock_shouldMergeRowsAndSortByExpiry() {
        WarehouseBin binA = bin(1L, "WH-001", "A-01", null);
        WarehouseBin binB = bin(2L, "WH-001", "A-02", null);
        WarehouseBinStock late = binStock(binA, "SKU-A", "B1", LocalDate.now().plusDays(30), 5);
        WarehouseBinStock early = binStock(binB, "SKU-A", "B1", LocalDate.now().plusDays(3), 2);
        when(binRepository.findAll()).thenReturn(List.of(binA, binB));
        when(binStockRepository.findByBinIdOrderByExpiryDateAsc(1L)).thenReturn(List.of(late));
        when(binStockRepository.findByBinIdOrderByExpiryDateAsc(2L)).thenReturn(List.of(early));

        var result = service.listBinStock(1L, "WH-001", null);

        assertEquals(2, result.size());
        assertTrue(result.get(0).binCode().equals("A-02"));
        assertEquals(2, result.get(0).quantity());
        verify(permissionService).requirePermission(1L, "ops:warehouse:list");
    }

    @Test
    void inboundToBin_shouldSyncAggregateAndAddBinStock() {
        WarehouseBin bin = bin(1L, "WH-001", "A-01", null);
        when(binRepository.findByWarehouseIdAndBinCode("WH-001", "A-01"))
                .thenReturn(Optional.of(bin));
        when(skuCatalogRepository.existsById("SKU-A")).thenReturn(true);
        when(binStockRepository.findByBinIdAndSkuIdAndBatchNoForUpdate(1L, "SKU-A", "B1"))
                .thenReturn(Optional.empty());
        List<WarehouseBinStock> saved = new ArrayList<>();
        when(binStockRepository.save(any())).thenAnswer(a -> {
            saved.add(a.getArgument(0));
            return a.getArgument(0);
        });

        service.inboundToBin(1L, new BinInboundRequest(
                "WH-001", "A-01", "SKU-A", "B1",
                LocalDate.now().minusDays(2), LocalDate.now().plusDays(60), 6));

        verify(warehouseService).binStockChange(
                "WH-001", "SKU-A", "B1",
                LocalDate.now().minusDays(2), LocalDate.now().plusDays(60),
                6, 1L, "BIN_INBOUND", "1");
        assertEquals(1, saved.size());
        assertEquals(6, saved.get(0).getQuantity());
        assertEquals(1L, saved.get(0).getBinId());
    }

    @Test
    void inboundToBin_shouldRejectInactiveBin() {
        WarehouseBin inactive = bin(1L, "WH-001", "A-01", null);
        inactive.setStatus("INACTIVE");
        when(binRepository.findByWarehouseIdAndBinCode("WH-001", "A-01"))
                .thenReturn(Optional.of(inactive));
        when(skuCatalogRepository.existsById("SKU-A")).thenReturn(true);

        assertThrows(ResponseStatusException.class, () -> service.inboundToBin(1L,
                new BinInboundRequest("WH-001", "A-01", "SKU-A", "B1", null,
                        LocalDate.now().plusDays(30), 1)));
    }

    @Test
    void moveBetweenBins_shouldDeductSourceAndAddTarget() {
        WarehouseBin from = bin(1L, "WH-001", "A-01", null);
        WarehouseBin to = bin(2L, "WH-001", "A-02", null);
        WarehouseBinStock row = binStock(from, "SKU-A", "B1", LocalDate.now().plusDays(30), 10);
        when(binRepository.findById(1L)).thenReturn(Optional.of(from));
        when(binRepository.findById(2L)).thenReturn(Optional.of(to));
        when(binStockRepository.findByBinIdAndSkuIdAndBatchNoForUpdate(1L, "SKU-A", "B1"))
                .thenReturn(Optional.of(row));
        when(binStockRepository.findByBinIdAndSkuIdAndBatchNoForUpdate(2L, "SKU-A", "B1"))
                .thenReturn(Optional.empty());
        when(binStockRepository.save(any())).thenAnswer(a -> a.getArgument(0));

        service.moveBetweenBins(1L, new BinMoveRequest(1L, 2L, "SKU-A", "B1", 4));

        assertEquals(6, row.getQuantity());
        ArgumentCaptor<WarehouseBinStock> captor = ArgumentCaptor.forClass(WarehouseBinStock.class);
        verify(binStockRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        WarehouseBinStock target = captor.getAllValues().stream()
                .filter(r -> r.getBinId() == 2L)
                .findFirst()
                .orElseThrow();
        assertEquals(4, target.getQuantity());
    }

    @Test
    void moveBetweenBins_shouldRejectInsufficientStock() {
        WarehouseBin from = bin(1L, "WH-001", "A-01", null);
        WarehouseBin to = bin(2L, "WH-001", "A-02", null);
        WarehouseBinStock row = binStock(from, "SKU-A", "B1", LocalDate.now().plusDays(30), 2);
        when(binRepository.findById(1L)).thenReturn(Optional.of(from));
        when(binRepository.findById(2L)).thenReturn(Optional.of(to));
        when(binStockRepository.findByBinIdAndSkuIdAndBatchNoForUpdate(1L, "SKU-A", "B1"))
                .thenReturn(Optional.of(row));

        assertThrows(ResponseStatusException.class,
                () -> service.moveBetweenBins(1L, new BinMoveRequest(1L, 2L, "SKU-A", "B1", 5)));
    }

    private static Warehouse warehouse() {
        Warehouse w = new Warehouse();
        w.setWarehouseId("WH-001");
        w.setWarehouseName("主仓");
        return w;
    }

    private static WarehouseBin bin(Long id, String wh, String code, String name) {
        WarehouseBin b = new WarehouseBin();
        b.setBinId(id);
        b.setWarehouseId(wh);
        b.setBinCode(code);
        b.setBinName(name);
        b.setStatus("ACTIVE");
        return b;
    }

    private static WarehouseBinStock binStock(WarehouseBin bin, String skuId, String batchNo,
                                              LocalDate expiry, int qty) {
        WarehouseBinStock s = new WarehouseBinStock();
        s.setId(1L);
        s.setBinId(bin.getBinId());
        s.setSkuId(skuId);
        s.setBatchNo(batchNo);
        s.setExpiryDate(expiry);
        s.setQuantity(qty);
        return s;
    }
}
