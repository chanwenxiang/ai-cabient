package com.aicabinet.trade.service;

import com.aicabinet.common.dto.WarehouseMonthlyCloseDto;
import com.aicabinet.common.dto.WarehouseMonthlyCloseLineDto;
import com.aicabinet.common.dto.WarehouseMonthlyCloseRestockLineDto;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.ExternalChannelFlow;
import com.aicabinet.trade.domain.ExternalImportTemplate;
import com.aicabinet.trade.domain.InventoryWriteOff;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.domain.Warehouse;
import com.aicabinet.trade.domain.WarehouseMovement;
import com.aicabinet.trade.domain.WarehouseMonthlyCloseLine;
import com.aicabinet.trade.domain.WarehouseMonthlyCloseSheet;
import com.aicabinet.trade.domain.WarehouseOutboundLine;
import com.aicabinet.trade.domain.WarehouseStocktake;
import com.aicabinet.trade.domain.WarehouseStocktakeLine;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.ExternalChannelFlowMapper;
import com.aicabinet.trade.mapper.InventoryWriteOffMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.mapper.WarehouseMapper;
import com.aicabinet.trade.mapper.WarehouseMovementMapper;
import com.aicabinet.trade.mapper.WarehouseMonthlyCloseLineMapper;
import com.aicabinet.trade.mapper.WarehouseMonthlyCloseSheetMapper;
import com.aicabinet.trade.mapper.WarehouseOutboundLineMapper;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

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
    private static final DateTimeFormatter STAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(SHANGHAI);
    /** 发运流水（= 月结「上柜」列的唯一来源，见 {@link WarehouseMonthlyCloseMath#applyInMonth}）。 */
    private static final String MOVEMENT_OUTBOUND_SHIP = "OUTBOUND_SHIP";
    private static final String FORMULA_HINT =
            "应有=上期+采购入库+调入−退货−调出−领货（四损耗列单独展示不进应有）；"
                    + "「领货」=出库单从本仓带出的件数，其中没进柜的部分点该列下钻看「领而未上」（CB-074）；"
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
    private final UserInfoMapper userMapper;
    private final DeviceInfoMapper deviceMapper;
    private final WarehouseOutboundLineMapper outboundLineMapper;

    public WarehouseMonthlyCloseService(WarehouseMapper warehouseRepository,
                                        WarehouseMovementMapper movementRepository,
                                        WarehouseStocktakeMapper stocktakeRepository,
                                        WarehouseStocktakeLineMapper stocktakeLineRepository,
                                        SkuCatalogMapper skuCatalogRepository,
                                        WarehouseMonthlyCloseSheetMapper sheetMapper,
                                        WarehouseMonthlyCloseLineMapper lineMapper,
                                        InventoryWriteOffMapper writeOffMapper,
                                        ExternalChannelFlowMapper flowMapper,
                                        UserInfoMapper userMapper,
                                        DeviceInfoMapper deviceMapper,
                                        WarehouseOutboundLineMapper outboundLineMapper) {
        this.warehouseRepository = warehouseRepository;
        this.movementRepository = movementRepository;
        this.stocktakeRepository = stocktakeRepository;
        this.stocktakeLineRepository = stocktakeLineRepository;
        this.skuCatalogRepository = skuCatalogRepository;
        this.sheetMapper = sheetMapper;
        this.lineMapper = lineMapper;
        this.writeOffMapper = writeOffMapper;
        this.flowMapper = flowMapper;
        this.userMapper = userMapper;
        this.deviceMapper = deviceMapper;
        this.outboundLineMapper = outboundLineMapper;
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

        List<String> skuIds = new ArrayList<>(bySku.keySet());
        skuIds.sort(Comparator.naturalOrder());
        Map<String, SkuCatalog> catalog = catalogBySku(skuIds);
        List<WarehouseMonthlyCloseLineDto> lines = new ArrayList<>();
        for (String skuId : skuIds) {
            Acc acc = bySku.get(skuId);
            SkuCatalog sku = catalog.get(skuId);
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
                    skuName(sku, skuId),
                    sku == null ? null : blankToNull(sku.getBarcode()),
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
                    unitPriceCents(sku)
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
                managerUserName(warehouse.getManagerUserId()),
                Math.toIntExact(deviceMapper.selectCount(Wrappers.<DeviceInfo>lambdaQuery()
                        .eq(DeviceInfo::getHomeWarehouseId, warehouse.getWarehouseId()))),
                period.toString(),
                hint,
                openingFromPeriod
                        ? WarehouseMonthlyCloseSheet.OPENING_PERIOD_CLOSE
                        : WarehouseMonthlyCloseSheet.OPENING_BOOK,
                lines
        );
    }

    /**
     * 月结「上柜」列的柜机下钻明细（件数）。
     *
     * <p>🔴 骨架是 {@code OUTBOUND_SHIP} 流水而不是出库单：月结的 restockQty 就是这批流水逐条累加出来的，
     * 换任何别的真源都会造出「明细合计 ≠ 列值」——那种差异比「某台柜没归属」更难向运营解释。
     * 柜机归属与领而未上差额由出库行回填：{@code WarehouseService.ship} 逐 line 写流水（{@code :862-869}），
     * 故 {@code (outboundId, skuId, batchNo)} 能与流水精确配对。
     *
     * <p>CB-074：列值是**领货量**（出了分仓多少），不是进柜量。下钻里把
     * 领货 / 上柜实收 / 领而未上 三列拆开，运营才看得见「带了 10 件只上了 7 件」这 3 件去哪了
     * ——它既不在分仓账（已发运扣减）也不在柜机账（现场没确认），是未结差额。
     *
     * <p>归属可能为空是数据现状而非缺陷：{@code device_info.home_warehouse_id} 决定柜机挂在哪个仓，
     * 但出库行本身带 {@code device_id}，所以明细按出库单归属、不经该列；未填只影响「按仓筛柜」。
     */
    public List<WarehouseMonthlyCloseRestockLineDto> restockDetail(String warehouseId, String yearMonth) {
        if (warehouseId == null || warehouseId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择仓库");
        }
        String wh = warehouseId.trim();
        YearMonth period = parseYearMonth(yearMonth);
        Instant start = period.minusMonths(1).atDay(26).atStartOfDay(SHANGHAI).toInstant();
        Instant end = period.atDay(26).atStartOfDay(SHANGHAI).toInstant();

        List<WarehouseMovement> ships = movementRepository.selectList(
                        Wrappers.<WarehouseMovement>lambdaQuery()
                                .eq(WarehouseMovement::getWarehouseId, wh)
                                .eq(WarehouseMovement::getMovementType, MOVEMENT_OUTBOUND_SHIP)
                                .ge(WarehouseMovement::getCreatedAt, start)
                                .lt(WarehouseMovement::getCreatedAt, end)
                                .orderByAsc(WarehouseMovement::getCreatedAt)
                                .orderByAsc(WarehouseMovement::getMovementId))
                .stream()
                // 客情用水在月结里是独立列、不进上柜 ⇒ 下钻必须同样剔除，否则明细合计大于列值
                .filter(m -> !WarehouseMonthlyCloseMath.REF_GUEST_WATER.equalsIgnoreCase(
                        m.getRefType() == null ? "" : m.getRefType().trim()))
                .toList();
        if (ships.isEmpty()) {
            return List.of();
        }

        Map<String, WarehouseOutboundLine> lineByKey = outboundLineByMovement(ships);
        Map<String, RestockAcc> grouped = new TreeMap<>();
        for (WarehouseMovement m : ships) {
            int qty = Math.max(-m.getDeltaQty(), 0);
            if (qty == 0) {
                continue;
            }
            WarehouseOutboundLine line = lineByKey.get(lineKey(m.getRefId(), m.getSkuId(), m.getBatchNo()));
            String deviceId = line == null ? null : blankToNull(line.getDeviceId());
            RestockAcc acc = grouped.computeIfAbsent(
                    (deviceId == null ? "" : deviceId) + "|" + m.getSkuId(), k -> new RestockAcc());
            acc.deviceId = deviceId;
            acc.skuId = m.getSkuId();
            acc.qty += qty;
            // 出库行缺失（历史/外部单据）时不猜差额：按全量上柜出账，差额列留 0
            if (line != null) {
                acc.remainderQty += Math.max(0, Math.min(line.getRemainderQty(), qty));
            }
            String refId = blankToNull(m.getRefId());
            if (refId != null) {
                acc.outboundIds.add(refId);
            }
            if (m.getCreatedAt() != null && (acc.lastShippedAt == null
                    || m.getCreatedAt().isAfter(acc.lastShippedAt))) {
                acc.lastShippedAt = m.getCreatedAt();
            }
        }

        Map<String, String> deviceNames = deviceNames(grouped.values());
        Map<String, SkuCatalog> catalog = catalogBySku(grouped.values().stream()
                .map(a -> a.skuId).distinct().sorted().toList());

        List<WarehouseMonthlyCloseRestockLineDto> rows = new ArrayList<>();
        for (RestockAcc acc : grouped.values()) {
            SkuCatalog sku = catalog.get(acc.skuId);
            rows.add(new WarehouseMonthlyCloseRestockLineDto(
                    acc.deviceId,
                    acc.deviceId == null ? null
                            : deviceNames.getOrDefault(acc.deviceId, acc.deviceId),
                    acc.skuId,
                    skuName(sku, acc.skuId),
                    sku == null ? null : blankToNull(sku.getBarcode()),
                    acc.qty,
                    acc.qty - acc.remainderQty,
                    acc.remainderQty,
                    String.join("、", acc.outboundIds),
                    acc.lastShippedAt == null ? null : STAMP_FMT.format(acc.lastShippedAt)
            ));
        }
        return rows;
    }

    /** 流水 → 出库行：按 {@code (outboundId, skuId, batchNo)} 回查，用于取柜机归属与领而未上差额。 */
    private Map<String, WarehouseOutboundLine> outboundLineByMovement(List<WarehouseMovement> ships) {
        Set<Long> outboundIds = new LinkedHashSet<>();
        for (WarehouseMovement m : ships) {
            String ref = blankToNull(m.getRefId());
            if (ref == null) {
                continue;
            }
            try {
                outboundIds.add(Long.parseLong(ref));
            } catch (NumberFormatException ignored) {
                // 非数字 refId（历史/外部单据）不参与柜机回填，明细仍按流水出账，归属留空
            }
        }
        if (outboundIds.isEmpty()) {
            return Map.of();
        }
        Map<String, WarehouseOutboundLine> byLine = new HashMap<>();
        for (WarehouseOutboundLine line : outboundLineMapper.selectList(
                Wrappers.<WarehouseOutboundLine>lambdaQuery()
                        .in(WarehouseOutboundLine::getOutboundId, outboundIds))) {
            byLine.put(lineKey(String.valueOf(line.getOutboundId()), line.getSkuId(), line.getBatchNo()), line);
        }
        return byLine;
    }

    private Map<String, String> deviceNames(Iterable<RestockAcc> accs) {
        List<String> ids = new ArrayList<>();
        for (RestockAcc acc : accs) {
            if (acc.deviceId != null && !ids.contains(acc.deviceId)) {
                ids.add(acc.deviceId);
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, String> names = new HashMap<>();
        for (DeviceInfo device : deviceMapper.selectBatchIds(ids)) {
            names.put(device.getDeviceId(),
                    device.getDeviceName() == null || device.getDeviceName().isBlank()
                            ? device.getDeviceId() : device.getDeviceName());
        }
        return names;
    }

    private static String lineKey(String outboundId, String skuId, String batchNo) {
        return blankToNull(outboundId) + "|" + blankToNull(skuId) + "|"
                + (batchNo == null ? "" : batchNo.trim());
    }

    private static final class RestockAcc {
        private String deviceId;
        private String skuId;
        private int qty;
        /** 领而未上（CB-074）：本账期该柜×商品出库行上未结的差额合计。 */
        private int remainderQty;
        private final Set<String> outboundIds = new LinkedHashSet<>();
        private Instant lastShippedAt;
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

    /**
     * 一次批量取目录：月结一行要读名称/条码/单价三个字段，逐字段 {@code findById} 会把每行
     * 两次查询涨到三次，改成按行集一次 {@code selectBatchIds}。
     */
    private Map<String, SkuCatalog> catalogBySku(List<String> skuIds) {
        if (skuIds.isEmpty()) {
            return Map.of();
        }
        Map<String, SkuCatalog> byId = new HashMap<>();
        for (SkuCatalog sku : skuCatalogRepository.selectBatchIds(skuIds)) {
            byId.put(sku.getSkuId(), sku);
        }
        return byId;
    }

    private static String skuName(SkuCatalog sku, String skuId) {
        return sku == null || sku.getSkuName() == null || sku.getSkuName().isBlank()
                ? skuId : sku.getSkuName();
    }

    /** 单价快照 = 目录采购成本（未配置 → null，行级留空）。 */
    private static Long unitPriceCents(SkuCatalog sku) {
        if (sku == null || sku.getPurchaseCostCents() == null || sku.getPurchaseCostCents() <= 0) {
            return null;
        }
        return sku.getPurchaseCostCents().longValue();
    }

    /** 分仓主责人姓名；未绑定或查不到时 null（前端留空，不显示「未知」）。 */
    private String managerUserName(Long managerUserId) {
        if (managerUserId == null) {
            return null;
        }
        return userMapper.findById(managerUserId)
                .map(UserInfo::getName)
                .filter(n -> !n.isBlank())
                .orElse(null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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
