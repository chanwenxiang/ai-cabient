package com.aicabinet.trade.service;

import com.aicabinet.common.dto.WarehouseMonthlyCloseDto;
import com.aicabinet.common.dto.WarehouseMonthlyCloseLineDto;
import com.aicabinet.trade.domain.ExternalChannelFlow;
import com.aicabinet.trade.domain.ExternalImportTemplate;
import com.aicabinet.trade.domain.InventoryWriteOff;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.domain.Warehouse;
import com.aicabinet.trade.domain.WarehouseMonthlyCloseLine;
import com.aicabinet.trade.domain.WarehouseMonthlyCloseSheet;
import com.aicabinet.trade.domain.WarehouseStocktake;
import com.aicabinet.trade.domain.WarehouseStocktakeLine;
import com.aicabinet.trade.mapper.ExternalChannelFlowMapper;
import com.aicabinet.trade.mapper.InventoryWriteOffMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.WarehouseMapper;
import com.aicabinet.trade.mapper.WarehouseMovementMapper;
import com.aicabinet.trade.mapper.WarehouseMonthlyCloseLineMapper;
import com.aicabinet.trade.mapper.WarehouseMonthlyCloseSheetMapper;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分仓月结计算服务（V348 账期制返工，2026-10-13 拍板）。
 *
 * <p>🔴 <b>账期制</b>：账期 P = 上月 26 日 00:00 ~ 本月 25 日 24:00（上海时区），
 * {@code yearMonth} 参数 = <b>结束月标签</b>（与外部对账 CSV 统计时间/文件命名一致）。
 *
 * <p>🔴 <b>期初结转</b>（用户拍板「跟老系统一致」）：优先取上一账期月结单实盘（{@code PERIOD_CLOSE}），
 * 其次取外部导入「期末盘点」流水（导入口径：N 期末盘点所属月份填下一账期、操作类型=仓库盘点）；
 * 都没有则账面流水结转（{@code BOOK}，账期级告警「期初为账面值」）。
 *
 * <p>🔴 <b>实盘取数优先级</b>：账期内系统盘点（最近一次 COMPLETED/ADJUSTED）→
 * 外部导入盘点流水（period=下一账期的 WAREHOUSE_STOCKTAKE，即本账期期末盘点）→ 未盘。
 *
 * <p>🔴 <b>损耗四列归集</b>（字典 {@code monthly_loss_type}，前端 label；映射为应用层设计判断）：
 * <ul>
 *   <li>过期损耗 ← 仓库报损核销 {@code inventory_write_off.reasonCategory=EXPIRED}</li>
 *   <li>摇机损耗 ← 核销 {@code reasonCategory=DAMAGED}（摇机=外力损坏场景的财务称呼，
 *       自己的设计判断，可经字典/后续调整）+ 草稿阶段手工补录</li>
 *   <li>客情用水 ← 外部导入 {@code GUEST_WATER} 流水（movement 侧 {@code refType=GUEST_WATER}
 *       预留，补货目的链路待补货模块返工）</li>
 *   <li>费用抵扣 ← 财务审批前草稿阶段手工补录</li>
 * </ul>
 * 核销总量（movement {@code WRITE_OFF}）扣除过期/摇机后的余量兜底并入 lossQty
 * （SHRINKAGE/LOST/SAMPLING/OTHER 不进 18 列，维持差异台账化口径）。
 * 四列与 lossQty 一样<b>不进 expected</b>——expected 保持「损耗前应有」，与实盘对差异。
 */
@Service
public class WarehouseMonthlyCloseService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyyMM");
    private static final String FORMULA_HINT =
            "应有=上期+采购入库+调入−退货−调出−上柜（四损耗列单独展示不进应有）；"
                    + "实盘=账期内最近一次已完成盘点或期末盘点导入；账期=上月26日~本月25日；与结算金额无关";

    private final WarehouseMapper warehouseRepository;
    private final WarehouseMovementMapper movementRepository;
    private final WarehouseStocktakeMapper stocktakeRepository;
    private final WarehouseStocktakeLineMapper stocktakeLineRepository;
    private final SkuCatalogMapper skuCatalogRepository;
    private final WarehouseMonthlyCloseSheetMapper sheetMapper;
    private final WarehouseMonthlyCloseLineMapper lineMapper;
    private final InventoryWriteOffMapper writeOffMapper;
    private final ExternalChannelFlowMapper flowMapper;

    public WarehouseMonthlyCloseService(WarehouseMapper warehouseRepository,
                                        WarehouseMovementMapper movementRepository,
                                        WarehouseStocktakeMapper stocktakeRepository,
                                        WarehouseStocktakeLineMapper stocktakeLineRepository,
                                        SkuCatalogMapper skuCatalogRepository,
                                        WarehouseMonthlyCloseSheetMapper sheetMapper,
                                        WarehouseMonthlyCloseLineMapper lineMapper,
                                        InventoryWriteOffMapper writeOffMapper,
                                        ExternalChannelFlowMapper flowMapper) {
        this.warehouseRepository = warehouseRepository;
        this.movementRepository = movementRepository;
        this.stocktakeRepository = stocktakeRepository;
        this.stocktakeLineRepository = stocktakeLineRepository;
        this.skuCatalogRepository = skuCatalogRepository;
        this.sheetMapper = sheetMapper;
        this.lineMapper = lineMapper;
        this.writeOffMapper = writeOffMapper;
        this.flowMapper = flowMapper;
    }

    public WarehouseMonthlyCloseDto closeSheet(String warehouseId, String yearMonth) {
        if (warehouseId == null || warehouseId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择仓库");
        }
        String wh = warehouseId.trim();
        Warehouse warehouse = warehouseRepository.findById(wh)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "仓库不存在"));
        YearMonth period = parseYearMonth(yearMonth);
        // 账期 P = 上月 26 日 00:00 ~ 本月 26 日 00:00（左闭右开）
        Instant start = period.minusMonths(1).atDay(26).atStartOfDay(SHANGHAI).toInstant();
        Instant end = period.atDay(26).atStartOfDay(SHANGHAI).toInstant();
        String periodLabel = period.format(PERIOD_FMT);
        String prevLabel = period.minusMonths(1).format(PERIOD_FMT);
        String nextLabel = period.plusMonths(1).format(PERIOD_FMT);

        Map<String, Acc> bySku = WarehouseMonthlyCloseMath.aggregate(
                movementRepository.findByWarehouseId(wh), start, end);

        // ── 期初结转：上期单实盘 → 外部期末盘点流水 → 账面 ─────────────────────────────
        Map<String, Integer> prevCounted = prevSheetCounted(wh, period);
        boolean prevFromSheet = !prevCounted.isEmpty();
        Map<String, Integer> prevFlowStocktake = prevFromSheet
                ? Map.of() : flowStocktakeBySku(prevLabel, wh);
        boolean openingFromPeriod = prevFromSheet || !prevFlowStocktake.isEmpty();

        // ── 实盘：系统盘点 → 外部期末盘点流水（period=下一账期 = 本账期期末盘点导入口径）────
        Map<String, Integer> counted = countedBySku(wh, start, end);
        boolean countedFromFlow = false;
        if (counted.isEmpty()) {
            counted = flowStocktakeBySku(nextLabel, wh);
            countedFromFlow = !counted.isEmpty();
        }
        for (String skuId : counted.keySet()) {
            bySku.computeIfAbsent(skuId, k -> new Acc());
        }

        // ── 损耗四列归集 ────────────────────────────────────────────────────────────────
        Map<String, Integer> expiredBySku = writeOffBySku(wh, start, end,
                com.aicabinet.common.constants.WriteOffReasonCategory.EXPIRED);
        Map<String, Integer> rockBySku = writeOffBySku(wh, start, end,
                com.aicabinet.common.constants.WriteOffReasonCategory.DAMAGED);
        Map<String, Integer> guestFlowBySku = flowBySku(periodLabel, wh,
                ExternalImportTemplate.OP_GUEST_WATER);
        Map<String, Integer> countedFinal = counted;

        List<WarehouseMonthlyCloseLineDto> lines = new ArrayList<>();
        List<String> skuIds = new ArrayList<>(bySku.keySet());
        skuIds.sort(Comparator.naturalOrder());
        for (String skuId : skuIds) {
            Acc acc = bySku.get(skuId);
            int expired = expiredBySku.getOrDefault(skuId, 0);
            int rock = rockBySku.getOrDefault(skuId, 0);
            int guest = acc.guestWaterQty + guestFlowBySku.getOrDefault(skuId, 0);
            // 核销余量（SHRINKAGE/LOST/SAMPLING/OTHER）兜底进 lossQty；负值=数据越界，钳 0
            int residualWriteOff = Math.max(0, acc.writeOffQty - expired - rock);
            int lossQty = acc.lossQty + residualWriteOff;

            // 期初：上期实盘（单/流水）覆盖账面结转
            Integer opening;
            if (prevCounted.containsKey(skuId)) {
                opening = prevCounted.get(skuId);
            } else if (prevFlowStocktake.containsKey(skuId)) {
                opening = prevFlowStocktake.get(skuId);
            } else {
                opening = acc.openingQty;
            }
            acc.openingQty = opening;
            int expected = acc.expectedQty();
            Integer countedQty = countedFinal.get(skuId);
            Integer gap = countedQty == null ? null : countedQty - expected;
            lines.add(new WarehouseMonthlyCloseLineDto(
                    skuId,
                    skuName(skuId),
                    opening,
                    acc.purchaseInQty,
                    acc.transferInQty,
                    acc.transferOutQty,
                    acc.restockQty,
                    acc.returnQty,
                    lossQty,
                    expected,
                    countedQty,
                    gap,
                    rock,
                    expired,
                    Math.max(guest, 0),
                    0,
                    unitPriceCents(skuId)
            ));
        }
        String name = warehouse.getWarehouseName();
        boolean countedExternal = countedFromFlow;
        String hint = countedExternal
                ? FORMULA_HINT + "；实盘来自期末盘点导入（外部渠道对账导入）"
                : FORMULA_HINT;
        return new WarehouseMonthlyCloseDto(
                warehouse.getWarehouseId(),
                name == null || name.isBlank() ? warehouse.getWarehouseId() : name,
                period.toString(),
                hint,
                openingFromPeriod
                        ? WarehouseMonthlyCloseSheet.OPENING_PERIOD_CLOSE
                        : WarehouseMonthlyCloseSheet.OPENING_BOOK,
                lines
        );
    }

    /** 上一账期月结单的实盘列（APPROVED/DRAFT 均可——期初结转要的是「上一期期末数」）。 */
    private Map<String, Integer> prevSheetCounted(String warehouseId, YearMonth period) {
        WarehouseMonthlyCloseSheet prev = sheetMapper.selectOne(Wrappers
                .<WarehouseMonthlyCloseSheet>lambdaQuery()
                .eq(WarehouseMonthlyCloseSheet::getWarehouseId, warehouseId)
                .eq(WarehouseMonthlyCloseSheet::getYearMonth, period.minusMonths(1).toString()));
        if (prev == null) {
            return Map.of();
        }
        Map<String, Integer> counted = new HashMap<>();
        for (WarehouseMonthlyCloseLine line : lineMapper.selectList(Wrappers
                .<WarehouseMonthlyCloseLine>lambdaQuery()
                .eq(WarehouseMonthlyCloseLine::getCloseId, prev.getCloseId()))) {
            if (line.getSkuId() != null && line.getCountedQty() != null) {
                counted.put(line.getSkuId().trim(), line.getCountedQty());
            }
        }
        return counted;
    }

    /**
     * 外部导入盘点流水（{@code WAREHOUSE_STOCKTAKE}）：仓×SKU 聚合 qty。
     * 导入口径：N 期末盘点所属月份填<b>下一账期</b>——所以本期期末盘点在 {@code nextLabel}，
     * 本期期初（=上期末盘点）在 {@code periodLabel}。
     */
    private Map<String, Integer> flowStocktakeBySku(String periodLabel, String warehouseId) {
        return flowBySku(periodLabel, warehouseId, ExternalImportTemplate.OP_WAREHOUSE_STOCKTAKE);
    }

    private Map<String, Integer> flowBySku(String periodLabel, String warehouseId, String opType) {
        List<ExternalChannelFlow> flows = flowMapper.selectList(Wrappers.<ExternalChannelFlow>lambdaQuery()
                .eq(ExternalChannelFlow::getPeriodLabel, periodLabel)
                .eq(ExternalChannelFlow::getWarehouseId, warehouseId)
                .eq(ExternalChannelFlow::getOpType, opType));
        if (flows.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> bySku = new HashMap<>();
        for (ExternalChannelFlow flow : flows) {
            if (flow.getSkuId() == null || flow.getSkuId().isBlank()) {
                continue;
            }
            bySku.merge(flow.getSkuId().trim(), Math.max(flow.getQty(), 0), Integer::sum);
        }
        return bySku;
    }

    /** 仓库报损核销按 reasonCategory 归集（quantity 汇总；createdAt 在账期内）。 */
    private Map<String, Integer> writeOffBySku(String warehouseId, Instant start, Instant end,
                                               String reasonCategory) {
        List<InventoryWriteOff> rows = writeOffMapper.selectList(Wrappers.<InventoryWriteOff>lambdaQuery()
                .eq(InventoryWriteOff::getWarehouseId, warehouseId)
                .eq(InventoryWriteOff::getReasonCategory, reasonCategory)
                .ge(InventoryWriteOff::getCreatedAt, start)
                .lt(InventoryWriteOff::getCreatedAt, end));
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> bySku = new HashMap<>();
        for (InventoryWriteOff row : rows) {
            if (row.getSkuId() == null || row.getSkuId().isBlank()) {
                continue;
            }
            bySku.merge(row.getSkuId().trim(), Math.max(row.getQuantity(), 0), Integer::sum);
        }
        return bySku;
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

    /** 单价快照 = 目录采购成本（未配置 → null，行级留空）。 */
    private Long unitPriceCents(String skuId) {
        return skuCatalogRepository.findById(skuId)
                .map(SkuCatalog::getPurchaseCostCents)
                .filter(c -> c != null && c > 0)
                .map(Integer::longValue)
                .orElse(null);
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
