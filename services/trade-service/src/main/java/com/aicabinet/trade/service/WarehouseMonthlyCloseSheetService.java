package com.aicabinet.trade.service;

import com.aicabinet.common.dto.WarehouseCloseSheetDto;
import com.aicabinet.common.dto.WarehouseCloseSheetLineDto;
import com.aicabinet.common.dto.WarehouseMonthlyCloseDto;
import com.aicabinet.common.dto.WarehouseMonthlyCloseLineDto;
import com.aicabinet.trade.domain.InventoryWriteOff;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.domain.Warehouse;
import com.aicabinet.trade.domain.WarehouseMonthlyCloseLine;
import com.aicabinet.trade.domain.WarehouseMonthlyCloseSheet;
import com.aicabinet.trade.mapper.InventoryWriteOffMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.WarehouseMapper;
import com.aicabinet.trade.mapper.WarehouseMonthlyCloseLineMapper;
import com.aicabinet.trade.mapper.WarehouseMonthlyCloseSheetMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 仓库月结单服务（V327，E7 缺口 #9；竞品口径 {@code docs/COMPETITOR_BENCHMARK.md} CB-017）。
 *
 * <p><b>两步法（行业口径，思迅差异单 + 管家婆报损单）</b>：
 * ① 生成草稿落库（差异金额化：|gap| × 目录采购成本）→ ② 审批锁单 → ③ 逐行处置
 * （{@code CLAIM} 写 {@code inventory_write_off} 进 E4a 索赔台账 / {@code NORMAL_LOSS} 正常损耗 /
 * {@code SURPLUS} 盘盈待查）。
 *
 * <p>🔴 <b>盘亏不生成应付</b>（CB-017 修正 E7 原始假设）：行业口径盘亏走管理费用/其他应收款，
 * 应付是采购欠款方向。有责任方差异经索赔台账，追偿实际发生再走支付流程（与 E4 判定同源）。
 *
 * <p>🔴 <b>状态机</b>：DRAFT（可删可重生成）→ APPROVED（锁单，处置才可进行）。
 * 处置单向不可逆：CLAIM 已挂账绝不回退；NORMAL_LOSS/SURPLUS 判错走人工（安全优先，一期不做改判）。
 * DRAFT 阶段不允许处置——草稿随时重生成，提前挂索赔会成孤儿记录。
 */
@Service
public class WarehouseMonthlyCloseSheetService {

    private final WarehouseMonthlyCloseSheetMapper sheetMapper;
    private final WarehouseMonthlyCloseLineMapper lineMapper;
    private final WarehouseMonthlyCloseService closeCalculator;
    private final SkuCatalogMapper skuCatalogMapper;
    private final InventoryWriteOffMapper writeOffMapper;
    private final com.aicabinet.trade.mapper.UserInfoMapper userInfoMapper;
    private final WarehouseMapper warehouseMapper;

    public WarehouseMonthlyCloseSheetService(WarehouseMonthlyCloseSheetMapper sheetMapper,
                                             WarehouseMonthlyCloseLineMapper lineMapper,
                                             WarehouseMonthlyCloseService closeCalculator,
                                             SkuCatalogMapper skuCatalogMapper,
                                             InventoryWriteOffMapper writeOffMapper,
                                             com.aicabinet.trade.mapper.UserInfoMapper userInfoMapper,
                                             WarehouseMapper warehouseMapper) {
        this.sheetMapper = sheetMapper;
        this.lineMapper = lineMapper;
        this.closeCalculator = closeCalculator;
        this.skuCatalogMapper = skuCatalogMapper;
        this.writeOffMapper = writeOffMapper;
        this.userInfoMapper = userInfoMapper;
        this.warehouseMapper = warehouseMapper;
    }

    /** 生成（或重新生成 DRAFT）月结单。APPROVED 已存在 → 409 拒绝（锁单不可重生成）。 */
    @Transactional
    public WarehouseCloseSheetDto generateSheet(Long operatorId, String warehouseId, String yearMonth) {
        WarehouseMonthlyCloseDto computed = closeCalculator.closeSheet(warehouseId, yearMonth);
        WarehouseMonthlyCloseSheet existing = findSheet(warehouseId, computed.yearMonth());
        if (existing != null) {
            if (WarehouseMonthlyCloseSheet.STATUS_APPROVED.equals(existing.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "该仓库该月月结单已审批锁定，不可重新生成");
            }
            // DRAFT 重生成：删单（行级联 ON DELETE CASCADE）重建
            // 🔴 先赋显式类型变量：BaseMapper.delete(Wrapper) 与 BaseTradeMapper.delete(T) 重载，
            //   直接链式 Wrappers.<T>lambdaQuery() 会让两个重载都匹配（泛型推断歧义）编译失败。
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WarehouseMonthlyCloseLine> draftLines =
                    Wrappers.<WarehouseMonthlyCloseLine>lambdaQuery()
                            .eq(WarehouseMonthlyCloseLine::getCloseId, existing.getCloseId());
            lineMapper.delete(draftLines);
            sheetMapper.deleteById(existing.getCloseId());
        }
        return insertSheet(operatorId, computed);
    }

    /**
     * 自动任务幂等入口（V348 拍板 A）：无单 → 生成；已有 DRAFT/APPROVED → 跳过。
     * 🔴 与 {@link #generateSheet} 的区别：DRAFT <b>不重生成</b>——自动任务每日跑，
     * 重生成会覆盖财务在草稿上的手工补录（摇机损耗/费用抵扣/实盘修正）。
     *
     * @return true = 本次新生成
     */
    @Transactional
    public boolean ensureDraft(Long operatorId, String warehouseId, String yearMonth) {
        String normalized = WarehouseMonthlyCloseService.parseYearMonth(yearMonth).toString();
        if (findSheet(warehouseId, normalized) != null) {
            return false;
        }
        generateSheet(operatorId, warehouseId, normalized);
        return true;
    }

    /** 审批通过：DRAFT → APPROVED，锁单。审批人显示名服务端解析（照 OpsCatalogAdminService 模式）。 */
    @Transactional
    public WarehouseCloseSheetDto approve(Long operatorId, Long closeId) {
        WarehouseMonthlyCloseSheet sheet = requireSheet(closeId);
        if (!WarehouseMonthlyCloseSheet.STATUS_DRAFT.equals(sheet.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该月结单已审批，不可重复审批");
        }
        sheet.setStatus(WarehouseMonthlyCloseSheet.STATUS_APPROVED);
        sheet.setApprovedBy(operatorId);
        sheet.setApprovedByName(operatorDisplayName(operatorId));
        sheet.setApprovedAt(Instant.now());
        sheetMapper.updateById(sheet);
        return toDto(sheet, true);
    }

    /** 显示名解析：姓名 → 手机号 → 「账号 ID」兜底（与 SKU 目录更新人同款链路，不各自造）。 */
    private String operatorDisplayName(Long operatorId) {
        if (operatorId == null || operatorId <= 0L) {
            return "系统";
        }
        return userInfoMapper.findById(operatorId)
                .map(u -> {
                    String n = u.getName();
                    if (n == null || n.isBlank()) {
                        n = u.getPhoneNumber() != null && !u.getPhoneNumber().isBlank()
                                ? u.getPhoneNumber() : ("账号 " + operatorId);
                    }
                    return n;
                })
                .orElse("账号 " + operatorId);
    }

    /**
     * 逐行差异处置（两步法第二步）。仅 APPROVED 单可处置（拍板：审批定案金额，处置是生效后动作）。
     *
     * @param responsibleParty CLAIM 必填（索赔要有对象）
     */
    @Transactional
    public WarehouseCloseSheetDto disposeLine(Long operatorId, Long closeId, Long lineId,
                                              String disposition,
                                              String responsibleParty, String claimNo) {
        WarehouseMonthlyCloseSheet sheet = requireSheet(closeId);
        if (!WarehouseMonthlyCloseSheet.STATUS_APPROVED.equals(sheet.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "月结单审批后才可处置差异");
        }
        WarehouseMonthlyCloseLine line = lineMapper.selectById(lineId);
        if (line == null || !closeId.equals(line.getCloseId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "月结行不存在");
        }
        if (line.getGapQty() == null || line.getGapQty() == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该行无差异，无需处置");
        }
        if (!WarehouseMonthlyCloseLine.DISP_PENDING.equals(line.getGapDisposition())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该行已处置（" + line.getGapDisposition() + "），处置不可逆");
        }
        switch (disposition == null ? "" : disposition) {
            case WarehouseMonthlyCloseLine.DISP_CLAIM -> doClaim(operatorId, sheet, line, responsibleParty, claimNo);
            case WarehouseMonthlyCloseLine.DISP_NORMAL_LOSS, WarehouseMonthlyCloseLine.DISP_SURPLUS ->
                    line.setGapDisposition(disposition);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "disposition 须为 CLAIM / NORMAL_LOSS / SURPLUS");
        }
        lineMapper.updateById(line);
        return toDto(sheet, true);
    }

    /**
     * 草稿阶段行编辑（V348）：仅 DRAFT 可改——实盘修正、摇机损耗/费用抵扣手工补录、行备注。
     * 编辑后重算差异与金额、行级 editedFlag=true（漂移核对跳过），单级损耗/盘盈汇总重算。
     * null 字段 = 不修改（部分更新）。
     */
    @Transactional
    public WarehouseCloseSheetDto updateLine(Long operatorId, Long closeId, Long lineId,
                                             Integer countedQty, Integer rockLossQty,
                                             Integer feeDeductQty, String lineRemark) {
        WarehouseMonthlyCloseSheet sheet = requireSheet(closeId);
        if (!WarehouseMonthlyCloseSheet.STATUS_DRAFT.equals(sheet.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "月结单审批后不可编辑，请重新生成草稿");
        }
        WarehouseMonthlyCloseLine line = lineMapper.selectById(lineId);
        if (line == null || !closeId.equals(line.getCloseId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "月结行不存在");
        }
        if (countedQty != null) {
            if (countedQty < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "实盘数不能为负");
            }
            line.setCountedQty(countedQty);
            line.setGapQty(countedQty - line.getExpectedQty());
        }
        if (rockLossQty != null) {
            if (rockLossQty < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "摇机损耗不能为负");
            }
            line.setRockLossQty(rockLossQty);
        }
        if (feeDeductQty != null) {
            if (feeDeductQty < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "费用抵扣不能为负");
            }
            line.setFeeDeductQty(feeDeductQty);
        }
        if (lineRemark != null) {
            line.setLineRemark(lineRemark.isBlank() ? null : lineRemark.trim());
        }
        line.setEditedFlag(true);
        if (line.getGapQty() != null) {
            // 金额重算优先行单价快照（生成时已落库）；无快照再退目录成本
            Long amount = null;
            if (line.getGapQty() != 0) {
                if (line.getUnitPriceCents() != null) {
                    amount = line.getUnitPriceCents() * Math.abs(line.getGapQty());
                } else {
                    amount = amountFor(line.getSkuId(), Math.abs(line.getGapQty()));
                }
            }
            line.setGapAmountCents(amount);
        }
        lineMapper.updateById(line);
        recalcSheetTotals(sheet);
        return toDto(sheet, true);
    }

    /** 单级汇总重算（行编辑/生成后共用；gap 空的行不计）。 */
    private void recalcSheetTotals(WarehouseMonthlyCloseSheet sheet) {
        int lossQty = 0;
        int surplusQty = 0;
        long lossAmount = 0;
        long surplusAmount = 0;
        for (WarehouseMonthlyCloseLine r : lineMapper.selectList(Wrappers
                .<WarehouseMonthlyCloseLine>lambdaQuery()
                .eq(WarehouseMonthlyCloseLine::getCloseId, sheet.getCloseId()))) {
            if (r.getGapQty() == null || r.getGapQty() == 0) {
                continue;
            }
            long amount = r.getGapAmountCents() == null ? 0 : r.getGapAmountCents();
            if (r.getGapQty() < 0) {
                lossQty += -r.getGapQty();
                lossAmount += amount;
            } else {
                surplusQty += r.getGapQty();
                surplusAmount += amount;
            }
        }
        sheet.setLossQty(lossQty);
        sheet.setSurplusQty(surplusQty);
        sheet.setLossAmountCents(lossAmount);
        sheet.setSurplusAmountCents(surplusAmount);
        sheetMapper.updateById(sheet);
    }

    /**
     * 财务 18 列导出（V348，对齐财务现行 Excel）：库存月份/分仓名称/商品sku/商品全称/上期盘点/
     * 采购入库/仓库调入/仓库外调/机器补货/摇机损耗/过期损耗/客情用水/费用抵扣/仓库应有/
     * 实际盘点数/差异/单价/金额。
     */
    @Transactional(readOnly = true)
    public byte[] exportXlsx(Long closeId) {
        WarehouseCloseSheetDto sheet = getSheet(closeId);
        String[] headers = {"库存月份", "分仓名称", "商品sku", "商品全称", "上期盘点", "采购入库",
                "仓库调入", "仓库外调", "机器补货", "摇机损耗", "过期损耗", "客情用水", "费用抵扣",
                "仓库应有", "实际盘点数", "差异", "单价", "金额"};
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet xs = wb.createSheet("月结");
            CellStyle headStyle = wb.createCellStyle();
            headStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font bold = wb.createFont();
            bold.setBold(true);
            headStyle.setFont(bold);
            Row head = xs.createRow(0);
            for (int c = 0; c < headers.length; c++) {
                Cell cell = head.createCell(c);
                cell.setCellValue(headers[c]);
                cell.setCellStyle(headStyle);
                xs.setColumnWidth(c, 12 * 256);
            }
            int r = 1;
            for (WarehouseCloseSheetLineDto l : sheet.lines()) {
                Row row = xs.createRow(r++);
                int c = 0;
                row.createCell(c++).setCellValue(sheet.yearMonth());
                row.createCell(c++).setCellValue(sheet.warehouseName());
                row.createCell(c++).setCellValue(l.skuId());
                row.createCell(c++).setCellValue(l.skuName());
                row.createCell(c++).setCellValue(l.openingQty());
                row.createCell(c++).setCellValue(l.purchaseInQty());
                row.createCell(c++).setCellValue(l.transferInQty());
                row.createCell(c++).setCellValue(l.transferOutQty());
                row.createCell(c++).setCellValue(l.restockQty());
                row.createCell(c++).setCellValue(l.rockLossQty());
                row.createCell(c++).setCellValue(l.expiredLossQty());
                row.createCell(c++).setCellValue(l.guestWaterQty());
                row.createCell(c++).setCellValue(l.feeDeductQty());
                row.createCell(c++).setCellValue(l.expectedQty());
                if (l.countedQty() != null) {
                    row.createCell(c).setCellValue(l.countedQty());
                }
                c++;
                if (l.gapQty() != null) {
                    row.createCell(c).setCellValue(l.gapQty());
                }
                c++;
                if (l.unitPriceCents() != null) {
                    row.createCell(c).setCellValue(l.unitPriceCents() / 100.0);
                }
                c++;
                if (l.gapAmountCents() != null) {
                    row.createCell(c).setCellValue(l.gapAmountCents() / 100.0);
                }
            }
            wb.write(out);
            return out.toByteArray();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("月结导出失败：" + e.getMessage(), e);
        }
    }

    /** CLAIM：写 {@code inventory_write_off}（责任方 + 索赔额）进 E4a 台账，回填关联 ID。 */
    private void doClaim(Long operatorId, WarehouseMonthlyCloseSheet sheet,
                         WarehouseMonthlyCloseLine line, String responsibleParty, String claimNo) {
        if (responsibleParty == null || responsibleParty.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "进索赔台账必须填责任方");
        }
        int qty = Math.abs(line.getGapQty());
        if (line.getGapQty() >= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只有盘亏行可进索赔台账（盘盈走 SURPLUS 待查）");
        }
        InventoryWriteOff wo = new InventoryWriteOff();
        wo.setWarehouseId(sheet.getWarehouseId());
        // 🔴 不设 deviceId：仓库侧留空（ck_write_off_location 保证恰好一边非空，照 InventoryOpsService 先例）
        wo.setSkuId(line.getSkuId());
        wo.setQuantity(qty);
        wo.setReason("OTHER"); // 盘点短缺：白名单兜底桶，细分靠 reasonCategory（SHRINKAGE=盘点缩水）
        wo.setReasonCategory(com.aicabinet.common.constants.WriteOffReasonCategory.SHRINKAGE);
        wo.setResponsibleParty(responsibleParty.trim());
        wo.setClaimNo(claimNo == null || claimNo.isBlank() ? null : claimNo.trim());
        if (line.getGapAmountCents() != null) {
            if (line.getGapAmountCents() > Integer.MAX_VALUE) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "差异金额超出单行上限");
            }
            wo.setCostCents(line.getGapAmountCents().intValue());
            // 索赔额 = 差异金额（盘盈行不会走到 CLAIM——服务层只对 gap<0 开放 CLAIM 见下）
            wo.setClaimAmountCents(line.getGapAmountCents());
        }
        wo.setOperatorId(operatorId);
        writeOffMapper.insert(wo);
        line.setClaimWriteOffId(wo.getWriteOffId());
        line.setGapDisposition(WarehouseMonthlyCloseLine.DISP_CLAIM);
    }

    /** 分页列表（不含行）。 */
    @Transactional(readOnly = true)
    public com.aicabinet.common.dto.PageResult<WarehouseCloseSheetDto> listSheets(String warehouseId,
                                                                                  int page, int size) {
        Page<WarehouseMonthlyCloseSheet> p = sheetMapper.selectPage(
                new Page<>(Math.max(page, 0) + 1L, Math.min(Math.max(size, 1), 100)),
                Wrappers.<WarehouseMonthlyCloseSheet>lambdaQuery()
                        .eq(warehouseId != null && !warehouseId.isBlank(),
                                WarehouseMonthlyCloseSheet::getWarehouseId, warehouseId)
                        .orderByDesc(WarehouseMonthlyCloseSheet::getCloseId));
        List<WarehouseCloseSheetDto> items = p.getRecords().stream().map(s -> toDto(s, false)).toList();
        return new com.aicabinet.common.dto.PageResult<>(items, page, size, p.getTotal());
    }

    /** 单据详情（含行）。 */
    @Transactional(readOnly = true)
    public WarehouseCloseSheetDto getSheet(Long closeId) {
        return toDto(requireSheet(closeId), true);
    }

    /**
     * 重算漂移核对：用当前实时数据重跑 closeSheet，与已落库行比对。
     * 不一致 = 审批后底层数据又动了（或生成后发生了新业务）⇒ 前端亮红提示。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> recalcDrift(Long closeId) {
        WarehouseMonthlyCloseSheet sheet = requireSheet(closeId);
        WarehouseMonthlyCloseDto now = closeCalculator.closeSheet(sheet.getWarehouseId(), sheet.getYearMonth());
        List<WarehouseMonthlyCloseLine> rows = lineMapper.selectList(Wrappers
                .<WarehouseMonthlyCloseLine>lambdaQuery()
                .eq(WarehouseMonthlyCloseLine::getCloseId, closeId));
        Map<String, int[]> saved = new HashMap<>(); // skuId → [expected, counted]
        List<String> editedSkuIds = new ArrayList<>();
        for (WarehouseMonthlyCloseLine r : rows) {
            // V348：草稿阶段人工编辑过的行（实盘修正/手工补录）不参与漂移比对——
            // 编辑值是财务的权威录入，与实时重算天然不一致
            if (r.isEditedFlag()) {
                editedSkuIds.add(r.getSkuId());
                continue;
            }
            saved.put(r.getSkuId(), new int[]{r.getExpectedQty(),
                    r.getCountedQty() == null ? Integer.MIN_VALUE : r.getCountedQty()});
        }
        List<String> driftedSkus = new ArrayList<>();
        for (WarehouseMonthlyCloseLineDto l : now.lines()) {
            if (editedSkuIds.contains(l.skuId())) {
                continue; // 编辑行不参与比对
            }
            int[] old = saved.get(l.skuId());
            int countedNow = l.countedQty() == null ? Integer.MIN_VALUE : l.countedQty();
            if (old == null || old[0] != l.expectedQty() || old[1] != countedNow) {
                driftedSkus.add(l.skuId());
            }
        }
        boolean consistent = driftedSkus.isEmpty();
        Map<String, Object> out = new HashMap<>();
        out.put("closeId", closeId);
        out.put("consistent", consistent);
        out.put("driftedSkuCount", driftedSkus.size());
        out.put("driftedSkuIds", driftedSkus.stream().limit(50).toList());
        out.put("editedSkuIds", editedSkuIds.stream().limit(50).toList());
        return out;
    }

    // ── 组装 ─────────────────────────────────────────────────────────────────

    private WarehouseCloseSheetDto insertSheet(Long operatorId, WarehouseMonthlyCloseDto computed) {
        WarehouseMonthlyCloseSheet sheet = new WarehouseMonthlyCloseSheet();
        sheet.setWarehouseId(computed.warehouseId());
        sheet.setYearMonth(computed.yearMonth());
        sheet.setStatus(WarehouseMonthlyCloseSheet.STATUS_DRAFT);
        sheet.setOpeningSource(computed.openingSource() == null
                ? WarehouseMonthlyCloseSheet.OPENING_BOOK : computed.openingSource());
        sheetMapper.insert(sheet);

        int lossQty = 0;
        int surplusQty = 0;
        long lossAmount = 0;
        long surplusAmount = 0;
        List<WarehouseCloseSheetLineDto> lineDtos = new ArrayList<>();
        for (WarehouseMonthlyCloseLineDto l : computed.lines()) {
            WarehouseMonthlyCloseLine row = new WarehouseMonthlyCloseLine();
            row.setCloseId(sheet.getCloseId());
            row.setSkuId(l.skuId());
            row.setSkuName(l.skuName());
            row.setOpeningQty(l.openingQty());
            row.setPurchaseInQty(l.purchaseInQty());
            row.setTransferInQty(l.transferInQty());
            row.setTransferOutQty(l.transferOutQty());
            row.setRestockQty(l.restockQty());
            row.setReturnQty(l.returnQty());
            row.setLossQty(l.lossQty());
            row.setExpectedQty(l.expectedQty());
            row.setCountedQty(l.countedQty());
            row.setGapQty(l.gapQty());
            row.setGapDisposition(WarehouseMonthlyCloseLine.DISP_PENDING);
            row.setRockLossQty(l.rockLossQty());
            row.setExpiredLossQty(l.expiredLossQty());
            row.setGuestWaterQty(l.guestWaterQty());
            row.setFeeDeductQty(l.feeDeductQty());
            row.setUnitPriceCents(l.unitPriceCents());
            if (l.gapQty() != null && l.gapQty() != 0) {
                // 🔴 不能写三元 `unitPrice!=null ? long : amountFor()`：数值提升会把 false 分支的
                //   null Long 自动拆箱 NPE（JLS 条件表达式 numeric promotion）
                Long amount;
                if (l.unitPriceCents() != null) {
                    amount = l.unitPriceCents() * Math.abs(l.gapQty());
                } else {
                    amount = amountFor(l.skuId(), Math.abs(l.gapQty()));
                }
                row.setGapAmountCents(amount);
                if (l.gapQty() < 0) {
                    lossQty += -l.gapQty();
                    if (amount != null) lossAmount += amount;
                } else {
                    surplusQty += l.gapQty();
                    if (amount != null) surplusAmount += amount;
                    row.setGapDisposition(WarehouseMonthlyCloseLine.DISP_SURPLUS);
                }
            }
            lineMapper.insert(row);
            lineDtos.add(toLineDto(row));
        }
        sheet.setLossQty(lossQty);
        sheet.setSurplusQty(surplusQty);
        sheet.setLossAmountCents(lossAmount);
        sheet.setSurplusAmountCents(surplusAmount);
        sheet.setLineCount(lineDtos.size());
        sheetMapper.updateById(sheet);
        return new WarehouseCloseSheetDto(sheet.getCloseId(), sheet.getWarehouseId(), computed.warehouseName(),
                sheet.getYearMonth(), sheet.getStatus(), sheet.getOpeningSource(), lossQty, surplusQty,
                lossAmount, surplusAmount, lineDtos.size(), null, null, null, sheet.getCreatedAt(), lineDtos);
    }

    /** 差异金额 = 数量 × 目录采购成本；成本未配置 → null（行级留空，合计不计入）。 */
    private Long amountFor(String skuId, int qty) {
        Integer unitCost = skuCatalogMapper.findById(skuId)
                .map(SkuCatalog::getPurchaseCostCents)
                .orElse(null);
        if (unitCost == null) {
            return null;
        }
        return (long) unitCost * qty;
    }

    private WarehouseCloseSheetDto toDto(WarehouseMonthlyCloseSheet sheet, boolean withLines) {
        List<WarehouseCloseSheetLineDto> lines = withLines
                ? lineMapper.selectList(Wrappers.<WarehouseMonthlyCloseLine>lambdaQuery()
                        .eq(WarehouseMonthlyCloseLine::getCloseId, sheet.getCloseId())
                        .orderByAsc(WarehouseMonthlyCloseLine::getSkuId))
                .stream().map(this::toLineDto).toList()
                : List.of();
        String warehouseName = warehouseMapper.findById(sheet.getWarehouseId())
                .map(Warehouse::getWarehouseName)
                .filter(n -> n != null && !n.isBlank())
                .orElse(sheet.getWarehouseId());
        return new WarehouseCloseSheetDto(sheet.getCloseId(), sheet.getWarehouseId(), warehouseName,
                sheet.getYearMonth(), sheet.getStatus(), sheet.getOpeningSource(),
                sheet.getLossQty(), sheet.getSurplusQty(),
                sheet.getLossAmountCents(), sheet.getSurplusAmountCents(), sheet.getLineCount(),
                sheet.getApprovedByName(), sheet.getApprovedAt(), sheet.getRemark(),
                sheet.getCreatedAt(), lines);
    }

    private WarehouseCloseSheetLineDto toLineDto(WarehouseMonthlyCloseLine r) {
        return new WarehouseCloseSheetLineDto(r.getLineId(), r.getSkuId(), r.getSkuName(),
                r.getOpeningQty(), r.getPurchaseInQty(), r.getTransferInQty(), r.getTransferOutQty(),
                r.getRestockQty(), r.getReturnQty(), r.getLossQty(), r.getExpectedQty(),
                r.getCountedQty(), r.getGapQty(), r.getGapAmountCents(),
                r.getGapDisposition(), r.getClaimWriteOffId(),
                r.getRockLossQty(), r.getExpiredLossQty(), r.getGuestWaterQty(), r.getFeeDeductQty(),
                r.getUnitPriceCents(), r.isEditedFlag(), r.getLineRemark());
    }

    private WarehouseMonthlyCloseSheet findSheet(String warehouseId, String yearMonth) {
        return sheetMapper.selectOne(Wrappers.<WarehouseMonthlyCloseSheet>lambdaQuery()
                .eq(WarehouseMonthlyCloseSheet::getWarehouseId, warehouseId)
                .eq(WarehouseMonthlyCloseSheet::getYearMonth, yearMonth));
    }

    private WarehouseMonthlyCloseSheet requireSheet(Long closeId) {
        WarehouseMonthlyCloseSheet sheet = sheetMapper.selectById(closeId);
        if (sheet == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "月结单不存在");
        }
        return sheet;
    }
}
