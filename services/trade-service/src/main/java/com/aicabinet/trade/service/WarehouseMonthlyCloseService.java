package com.aicabinet.trade.service;

import com.aicabinet.common.dto.WarehouseMonthlyCloseDto;
import com.aicabinet.common.dto.WarehouseMonthlyCloseLineDto;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.domain.Warehouse;
import com.aicabinet.trade.domain.WarehouseStocktake;
import com.aicabinet.trade.domain.WarehouseStocktakeLine;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.WarehouseMapper;
import com.aicabinet.trade.mapper.WarehouseMovementMapper;
import com.aicabinet.trade.mapper.WarehouseStocktakeLineMapper;
import com.aicabinet.trade.mapper.WarehouseStocktakeMapper;
import com.aicabinet.trade.support.WarehouseMonthlyCloseMath;
import com.aicabinet.trade.support.WarehouseMonthlyCloseMath.Acc;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class WarehouseMonthlyCloseService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final String FORMULA_HINT = "应有=上期+采购入库+调入−退货−调出−上柜；实盘取本月最近一次已完成盘点；与结算金额无关";

    private final WarehouseMapper warehouseRepository;
    private final WarehouseMovementMapper movementRepository;
    private final WarehouseStocktakeMapper stocktakeRepository;
    private final WarehouseStocktakeLineMapper stocktakeLineRepository;
    private final SkuCatalogMapper skuCatalogRepository;

    public WarehouseMonthlyCloseService(WarehouseMapper warehouseRepository,
                                        WarehouseMovementMapper movementRepository,
                                        WarehouseStocktakeMapper stocktakeRepository,
                                        WarehouseStocktakeLineMapper stocktakeLineRepository,
                                        SkuCatalogMapper skuCatalogRepository) {
        this.warehouseRepository = warehouseRepository;
        this.movementRepository = movementRepository;
        this.stocktakeRepository = stocktakeRepository;
        this.stocktakeLineRepository = stocktakeLineRepository;
        this.skuCatalogRepository = skuCatalogRepository;
    }

    public WarehouseMonthlyCloseDto closeSheet(String warehouseId, String yearMonth) {
        if (warehouseId == null || warehouseId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择仓库");
        }
        String wh = warehouseId.trim();
        Warehouse warehouse = warehouseRepository.findById(wh)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "仓库不存在"));
        YearMonth month = parseYearMonth(yearMonth);
        Instant start = month.atDay(1).atStartOfDay(SHANGHAI).toInstant();
        Instant end = month.plusMonths(1).atDay(1).atStartOfDay(SHANGHAI).toInstant();

        Map<String, Acc> bySku = WarehouseMonthlyCloseMath.aggregate(
                movementRepository.findByWarehouseId(wh), start, end);
        Map<String, Integer> counted = countedBySku(wh, start, end);
        for (String skuId : counted.keySet()) {
            bySku.computeIfAbsent(skuId, k -> new Acc());
        }

        List<WarehouseMonthlyCloseLineDto> lines = new ArrayList<>();
        List<String> skuIds = new ArrayList<>(bySku.keySet());
        skuIds.sort(Comparator.naturalOrder());
        for (String skuId : skuIds) {
            Acc acc = bySku.get(skuId);
            int expected = acc.expectedQty();
            Integer countedQty = counted.get(skuId);
            Integer gap = countedQty == null ? null : countedQty - expected;
            lines.add(new WarehouseMonthlyCloseLineDto(
                    skuId,
                    skuName(skuId),
                    acc.openingQty,
                    acc.purchaseInQty,
                    acc.transferInQty,
                    acc.transferOutQty,
                    acc.restockQty,
                    acc.returnQty,
                    acc.lossQty,
                    expected,
                    countedQty,
                    gap
            ));
        }
        String name = warehouse.getWarehouseName();
        return new WarehouseMonthlyCloseDto(
                warehouse.getWarehouseId(),
                name == null || name.isBlank() ? warehouse.getWarehouseId() : name,
                month.toString(),
                FORMULA_HINT,
                lines
        );
    }

    private Map<String, Integer> countedBySku(String warehouseId, Instant start, Instant end) {
        List<WarehouseStocktake> done = stocktakeRepository.selectList(Wrappers.<WarehouseStocktake>lambdaQuery()
                .eq(WarehouseStocktake::getWarehouseId, warehouseId)
                .in(WarehouseStocktake::getStatus, List.of("COMPLETED", "ADJUSTED"))
                .ge(WarehouseStocktake::getCompletedAt, start)
                .lt(WarehouseStocktake::getCompletedAt, end)
                .orderByDesc(WarehouseStocktake::getCompletedAt)
                .orderByDesc(WarehouseStocktake::getStocktakeId));
        if (done.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> counted = new HashMap<>();
        for (WarehouseStocktakeLine line : stocktakeLineRepository.findByStocktakeIdOrderByLineIdAsc(
                done.get(0).getStocktakeId())) {
            if (line.getSkuId() == null || line.getCountedQty() == null) {
                continue;
            }
            counted.merge(line.getSkuId().trim(), line.getCountedQty(), Integer::sum);
        }
        return counted;
    }

    private String skuName(String skuId) {
        return skuCatalogRepository.findById(skuId)
                .map(SkuCatalog::getSkuName)
                .filter(n -> n != null && !n.isBlank())
                .orElse(skuId);
    }

    static YearMonth parseYearMonth(String yearMonth) {
        if (yearMonth == null || yearMonth.isBlank()) {
            return YearMonth.now(SHANGHAI);
        }
        try {
            return YearMonth.parse(yearMonth.trim());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "月份格式须为 YYYY-MM");
        }
    }
}
