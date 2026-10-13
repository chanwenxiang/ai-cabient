package com.aicabinet.trade.service;

import com.aicabinet.common.dto.WarehouseCloseSheetDto;
import com.aicabinet.common.dto.WarehouseMonthlyCloseDto;
import com.aicabinet.common.dto.WarehouseMonthlyCloseLineDto;
import com.aicabinet.trade.domain.InventoryWriteOff;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.domain.WarehouseMonthlyCloseLine;
import com.aicabinet.trade.domain.WarehouseMonthlyCloseSheet;
import com.aicabinet.trade.mapper.InventoryWriteOffMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.WarehouseMapper;
import com.aicabinet.trade.mapper.WarehouseMonthlyCloseLineMapper;
import com.aicabinet.trade.mapper.WarehouseMonthlyCloseSheetMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V327 仓库月结单测试：两步法（CB-017）——生成金额化、审批锁单、处置状态机、索赔联动、漂移核对。
 * V348：账期制（标签=结束月）、ensureDraft 幂等（DRAFT 不重生成）、草稿行编辑、财务 18 列导出。
 * closeSheet 的件数聚合正确性由既有 {@code WarehouseMonthlyCloseMathTest} 覆盖，这里 mock 计算结果。
 */
@ExtendWith(MockitoExtension.class)
class WarehouseMonthlyCloseSheetServiceTest {

    @Mock private WarehouseMonthlyCloseSheetMapper sheetMapper;
    @Mock private WarehouseMonthlyCloseLineMapper lineMapper;
    @Mock private WarehouseMonthlyCloseService closeCalculator;
    @Mock private SkuCatalogMapper skuCatalogMapper;
    @Mock private InventoryWriteOffMapper writeOffMapper;
    @Mock private com.aicabinet.trade.mapper.UserInfoMapper userInfoMapper;
    @Mock private WarehouseMapper warehouseMapper;

    private WarehouseMonthlyCloseSheetService service;

    @BeforeEach
    void setUp() {
        service = new WarehouseMonthlyCloseSheetService(
                sheetMapper, lineMapper, closeCalculator, skuCatalogMapper, writeOffMapper,
                userInfoMapper, warehouseMapper);
        // toDto 解析仓库名：mock 默认给空（具体测试再覆盖）
        lenient().when(warehouseMapper.findById(any())).thenReturn(Optional.empty());
    }

    private WarehouseMonthlyCloseLineDto computedLine(String skuId, Integer gap) {
        return computedLine(skuId, gap, 500L);
    }

    private WarehouseMonthlyCloseLineDto computedLine(String skuId, Integer gap, Long unitPrice) {
        return new WarehouseMonthlyCloseLineDto(skuId, "SKU-" + skuId,
                10, 5, 2, 1, 3, 1, 0, 12, 12 + (gap == null ? 0 : gap), gap,
                0, 0, 0, 0, unitPrice);
    }

    private WarehouseMonthlyCloseDto computed(String yearMonth, WarehouseMonthlyCloseLineDto... lines) {
        return new WarehouseMonthlyCloseDto("WH-1", "一号仓", yearMonth, "应有=上期+…",
                WarehouseMonthlyCloseSheet.OPENING_PERIOD_CLOSE, List.of(lines));
    }

    private void stubSkuCost(String skuId, Integer cost) {
        SkuCatalog sku = new SkuCatalog();
        sku.setSkuId(skuId);
        sku.setPurchaseCostCents(cost);
        when(skuCatalogMapper.findById(skuId)).thenReturn(Optional.of(sku));
    }

    @Nested
    class Generate {

        @Test
        void shouldMaterializeAmountsAndTotals() {
            when(closeCalculator.closeSheet("WH-1", "2026-10")).thenReturn(computed(
                    "2026-10",
                    computedLine("SKU-A", -3),   // 盘亏 3：金额 3×500=1500（单价快照 500）
                    computedLine("SKU-B", 2, 800L))); // 盘盈 2：金额 2×800=1600
            when(sheetMapper.insert(any())).thenReturn(1);
            when(lineMapper.insert(any())).thenReturn(1);

            WarehouseCloseSheetDto dto = service.generateSheet(1L, "WH-1", "2026-10");

            assertEquals(WarehouseMonthlyCloseSheet.STATUS_DRAFT, dto.status());
            assertEquals(WarehouseMonthlyCloseSheet.OPENING_PERIOD_CLOSE, dto.openingSource());
            assertEquals(3, dto.lossQty());
            assertEquals(2, dto.surplusQty());
            assertEquals(1500L, dto.lossAmountCents());
            assertEquals(1600L, dto.surplusAmountCents());
            assertEquals(2, dto.lineCount());
            // 盘亏行默认 PENDING；盘盈行直接 SURPLUS（无需人工再选）
            assertEquals(WarehouseMonthlyCloseLine.DISP_PENDING, dto.lines().get(0).gapDisposition());
            assertEquals(1500L, dto.lines().get(0).gapAmountCents());
            assertEquals(WarehouseMonthlyCloseLine.DISP_SURPLUS, dto.lines().get(1).gapDisposition());
        }

        @Test
        void shouldLeaveAmountNullWhenCostNotConfigured() {
            when(closeCalculator.closeSheet("WH-1", "2026-10"))
                    .thenReturn(computed("2026-10", computedLine("SKU-C", -5, null)));
            stubSkuCost("SKU-C", null); // 成本未配置（单价快照 null → amountFor 兜底也 null）
            when(sheetMapper.insert(any())).thenReturn(1);
            when(lineMapper.insert(any())).thenReturn(1);

            WarehouseCloseSheetDto dto = service.generateSheet(1L, "WH-1", "2026-10");

            assertNull(dto.lines().get(0).gapAmountCents());
            assertEquals(0L, dto.lossAmountCents(), "成本未配置的行不计入合计");
            assertEquals(5, dto.lossQty(), "件数合计与金额无关");
        }

        @Test
        void shouldRejectWhenApprovedSheetExists() {
            WarehouseMonthlyCloseSheet approved = new WarehouseMonthlyCloseSheet();
            approved.setCloseId(9L);
            approved.setStatus(WarehouseMonthlyCloseSheet.STATUS_APPROVED);
            when(closeCalculator.closeSheet("WH-1", "2026-10")).thenReturn(computed("2026-10"));
            when(sheetMapper.selectOne(any())).thenReturn(approved);

            ResponseStatusException e = assertThrows(ResponseStatusException.class,
                    () -> service.generateSheet(1L, "WH-1", "2026-10"));
            assertEquals(409, e.getStatusCode().value());
            verify(sheetMapper, never()).insert(any());
        }

        @Test
        void shouldReplaceDraftOnRegenerate() {
            WarehouseMonthlyCloseSheet draft = new WarehouseMonthlyCloseSheet();
            draft.setCloseId(7L);
            draft.setStatus(WarehouseMonthlyCloseSheet.STATUS_DRAFT);
            when(closeCalculator.closeSheet("WH-1", "2026-10"))
                    .thenReturn(computed("2026-10", computedLine("SKU-A", null)));
            when(sheetMapper.selectOne(any())).thenReturn(draft);
            when(sheetMapper.insert(any())).thenReturn(1);
            when(lineMapper.insert(any())).thenReturn(1);

            service.generateSheet(1L, "WH-1", "2026-10");

            verify(lineMapper).delete(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
            verify(sheetMapper).deleteById(7L);
            verify(sheetMapper).insert(any());
        }
    }

    @Nested
    class Approve {

        @Test
        void shouldTransitionDraftToApprovedAndLock() {
            WarehouseMonthlyCloseSheet draft = draftSheet(11L);
            when(sheetMapper.selectById(11L)).thenReturn(draft);
            com.aicabinet.trade.domain.UserInfo approver = new com.aicabinet.trade.domain.UserInfo();
            approver.setName("仓库主管");
            when(userInfoMapper.findById(1L)).thenReturn(Optional.of(approver));

            WarehouseCloseSheetDto dto = service.approve(1L, 11L);

            assertEquals(WarehouseMonthlyCloseSheet.STATUS_APPROVED, dto.status());
            assertEquals("仓库主管", dto.approvedByName());
            verify(sheetMapper).updateById(draft);
        }

        @Test
        void shouldRejectDoubleApprove() {
            WarehouseMonthlyCloseSheet approved = draftSheet(11L);
            approved.setStatus(WarehouseMonthlyCloseSheet.STATUS_APPROVED);
            when(sheetMapper.selectById(11L)).thenReturn(approved);

            ResponseStatusException e = assertThrows(ResponseStatusException.class,
                    () -> service.approve(1L, 11L));
            assertEquals(409, e.getStatusCode().value());
        }
    }

    @Nested
    class Dispose {

        @Test
        void claimShouldWriteInventoryWriteOffAndBackfillId() {
            WarehouseMonthlyCloseSheet approved = draftSheet(11L);
            approved.setStatus(WarehouseMonthlyCloseSheet.STATUS_APPROVED);
            approved.setWarehouseId("WH-1");
            when(sheetMapper.selectById(11L)).thenReturn(approved);
            WarehouseMonthlyCloseLine lossRow = sheetLine(21L, 11L, "SKU-A", -3, 1500L);
            when(lineMapper.selectById(21L)).thenReturn(lossRow);
            // 模拟 MP insert 回填主键
            doAnswer(inv -> {
                InventoryWriteOff wo = inv.getArgument(0);
                wo.setWriteOffId(88L);
                return 1;
            }).when(writeOffMapper).insert(any());

            service.disposeLine(1L, 11L, 21L, WarehouseMonthlyCloseLine.DISP_CLAIM,
                    "搬运工张三", "CLM-2026-001");

            ArgumentCaptor<InventoryWriteOff> captor = ArgumentCaptor.forClass(InventoryWriteOff.class);
            verify(writeOffMapper).insert(captor.capture());
            InventoryWriteOff wo = captor.getValue();
            assertEquals("WH-1", wo.getWarehouseId());
            assertNull(wo.getDeviceId(), "仓库侧索赔不设 deviceId（ck_write_off_location）");
            assertEquals("SKU-A", wo.getSkuId());
            assertEquals(3, wo.getQuantity(), "索赔数量=|gap|");
            assertEquals("OTHER", wo.getReason());
            assertEquals("SHRINKAGE", wo.getReasonCategory(), "盘点短缺=SHRINKAGE");
            assertEquals("搬运工张三", wo.getResponsibleParty());
            assertEquals(1500L, wo.getClaimAmountCents());
            assertEquals(88L, lossRow.getClaimWriteOffId(), "回填关联 ID");
            assertEquals(WarehouseMonthlyCloseLine.DISP_CLAIM, lossRow.getGapDisposition());
        }

        @Test
        void claimShouldRequireResponsibleParty() {
            WarehouseMonthlyCloseSheet approved = draftSheet(11L);
            approved.setStatus(WarehouseMonthlyCloseSheet.STATUS_APPROVED);
            when(sheetMapper.selectById(11L)).thenReturn(approved);
            when(lineMapper.selectById(21L)).thenReturn(sheetLine(21L, 11L, "SKU-A", -3, 1500L));

            ResponseStatusException e = assertThrows(ResponseStatusException.class,
                    () -> service.disposeLine(1L, 11L, 21L, WarehouseMonthlyCloseLine.DISP_CLAIM,
                            "  ", null));
            assertEquals(400, e.getStatusCode().value());
            verify(writeOffMapper, never()).insert(any());
        }

        @Test
        void surplusRowCannotClaim() {
            WarehouseMonthlyCloseSheet approved = draftSheet(11L);
            approved.setStatus(WarehouseMonthlyCloseSheet.STATUS_APPROVED);
            when(sheetMapper.selectById(11L)).thenReturn(approved);
            when(lineMapper.selectById(22L)).thenReturn(sheetLine(22L, 11L, "SKU-B", 2, 1600L));

            ResponseStatusException e = assertThrows(ResponseStatusException.class,
                    () -> service.disposeLine(1L, 11L, 22L, WarehouseMonthlyCloseLine.DISP_CLAIM,
                            "供应商", null));
            assertEquals(400, e.getStatusCode().value(), "盘盈行不可进索赔（只有盘亏可索赔）");
        }

        @Test
        void disposeRequiresApprovedSheet() {
            WarehouseMonthlyCloseSheet draft = draftSheet(11L);
            when(sheetMapper.selectById(11L)).thenReturn(draft);

            ResponseStatusException e = assertThrows(ResponseStatusException.class,
                    () -> service.disposeLine(1L, 11L, 21L, WarehouseMonthlyCloseLine.DISP_NORMAL_LOSS,
                            null, null));
            assertEquals(409, e.getStatusCode().value(), "DRAFT 阶段不可处置（草稿随时重生成）");
        }

        @Test
        void disposeIrreversibleOnceDone() {
            WarehouseMonthlyCloseSheet approved = draftSheet(11L);
            approved.setStatus(WarehouseMonthlyCloseSheet.STATUS_APPROVED);
            when(sheetMapper.selectById(11L)).thenReturn(approved);
            WarehouseMonthlyCloseLine done = sheetLine(23L, 11L, "SKU-C", -1, 500L);
            done.setGapDisposition(WarehouseMonthlyCloseLine.DISP_NORMAL_LOSS);
            when(lineMapper.selectById(23L)).thenReturn(done);

            ResponseStatusException e = assertThrows(ResponseStatusException.class,
                    () -> service.disposeLine(1L, 11L, 23L, WarehouseMonthlyCloseLine.DISP_CLAIM,
                            "某人", null));
            assertEquals(409, e.getStatusCode().value(), "处置单向不可逆");
        }
    }

    @Nested
    class Drift {

        @Test
        void shouldDetectCountedQtyDrift() {
            WarehouseMonthlyCloseSheet approved = draftSheet(11L);
            approved.setWarehouseId("WH-1");
            approved.setYearMonth("2026-10");
            approved.setStatus(WarehouseMonthlyCloseSheet.STATUS_APPROVED);
            when(sheetMapper.selectById(11L)).thenReturn(approved);
            // 落库时实盘 9，现在重算实盘 7 ⇒ 漂移
            when(lineMapper.selectList(any())).thenReturn(List.of(sheetLine(21L, 11L, "SKU-A", -3, 1500L)));
            WarehouseMonthlyCloseLineDto nowLine = new WarehouseMonthlyCloseLineDto(
                    "SKU-A", "SKU-SKU-A", 10, 5, 2, 1, 3, 1, 0, 12, 7, -3, 0, 0, 0, 0, 500L);
            when(closeCalculator.closeSheet("WH-1", "2026-10"))
                    .thenReturn(new WarehouseMonthlyCloseDto("WH-1", "一号仓", "2026-10", "hint",
                            WarehouseMonthlyCloseSheet.OPENING_BOOK, List.of(nowLine)));

            var out = service.recalcDrift(11L);

            assertFalse((Boolean) out.get("consistent"), "审批后实盘数又变了必须亮红");
            assertEquals(1, out.get("driftedSkuCount"));
        }

        @Test
        void shouldReportConsistentWhenNothingChanged() {
            WarehouseMonthlyCloseSheet approved = draftSheet(11L);
            approved.setWarehouseId("WH-1");
            approved.setYearMonth("2026-10");
            when(sheetMapper.selectById(11L)).thenReturn(approved);
            WarehouseMonthlyCloseLine row = sheetLine(21L, 11L, "SKU-A", -3, 1500L);
            row.setCountedQty(9);
            when(lineMapper.selectList(any())).thenReturn(List.of(row));
            WarehouseMonthlyCloseLineDto nowLine = new WarehouseMonthlyCloseLineDto(
                    "SKU-A", "SKU-SKU-A", 10, 5, 2, 1, 3, 1, 0, 12, 9, -3, 0, 0, 0, 0, 500L);
            when(closeCalculator.closeSheet("WH-1", "2026-10"))
                    .thenReturn(new WarehouseMonthlyCloseDto("WH-1", "一号仓", "2026-10", "hint",
                            WarehouseMonthlyCloseSheet.OPENING_BOOK, List.of(nowLine)));

            var out = service.recalcDrift(11L);

            assertTrue((Boolean) out.get("consistent"));
        }

        @Test
        void shouldSkipEditedRowsInDrift() {
            WarehouseMonthlyCloseSheet approved = draftSheet(11L);
            approved.setWarehouseId("WH-1");
            approved.setYearMonth("2026-10");
            when(sheetMapper.selectById(11L)).thenReturn(approved);
            // 财务草稿阶段编辑过的行：权威录入，与实时重算不一致是预期 ⇒ 不亮红
            WarehouseMonthlyCloseLine row = sheetLine(21L, 11L, "SKU-A", -3, 1500L);
            row.setCountedQty(5);
            row.setEditedFlag(true);
            when(lineMapper.selectList(any())).thenReturn(List.of(row));
            when(closeCalculator.closeSheet("WH-1", "2026-10"))
                    .thenReturn(computed("2026-10", computedLine("SKU-A", 7)));

            var out = service.recalcDrift(11L);

            assertTrue((Boolean) out.get("consistent"), "编辑行不参与漂移比对");
            assertEquals(List.of("SKU-A"), out.get("editedSkuIds"));
        }
    }

    @Nested
    class EnsureDraft {

        @Test
        void shouldSkipWhenSheetAlreadyExists() {
            when(sheetMapper.selectOne(any())).thenReturn(draftSheet(11L));

            assertFalse(service.ensureDraft(0L, "WH-1", "2026-10"),
                    "已有 DRAFT/APPROVED 一律跳过（DRAFT 重生成会覆盖手工补录）");
            verify(closeCalculator, never()).closeSheet(any(), any());
        }

        @Test
        void shouldGenerateWhenMissing() {
            when(sheetMapper.selectOne(any())).thenReturn(null);
            when(closeCalculator.closeSheet("WH-1", "2026-10")).thenReturn(computed("2026-10"));
            when(sheetMapper.insert(any())).thenReturn(1);

            assertTrue(service.ensureDraft(0L, "WH-1", "2026-10"));
            verify(sheetMapper).insert(any());
        }
    }

    @Nested
    class UpdateLine {

        @Test
        void shouldRecalcGapAndFlagEdited() {
            WarehouseMonthlyCloseSheet draft = draftSheet(11L);
            when(sheetMapper.selectById(11L)).thenReturn(draft);
            WarehouseMonthlyCloseLine row = sheetLine(21L, 11L, "SKU-A", -3, 1500L);
            row.setUnitPriceCents(500L);
            when(lineMapper.selectById(21L)).thenReturn(row);
            when(lineMapper.selectList(any())).thenReturn(List.of(row));

            WarehouseCloseSheetDto dto = service.updateLine(1L, 11L, 21L, 9, 2, 1, "摇机 2 件、费用抵扣 1 件");

            assertTrue(row.isEditedFlag(), "人工编辑过必须置位（漂移核对跳过）");
            assertEquals(9, row.getCountedQty());
            assertEquals(-3, row.getGapQty(), "实盘 9 vs 应有 12 ⇒ gap=-3");
            assertEquals(1500L, row.getGapAmountCents(), "金额按行单价快照重算");
            assertEquals(2, row.getRockLossQty());
            assertEquals(1, row.getFeeDeductQty());
            assertEquals("摇机 2 件、费用抵扣 1 件", dto.lines().get(0).lineRemark());
        }

        @Test
        void shouldRejectWhenApproved() {
            WarehouseMonthlyCloseSheet approved = draftSheet(11L);
            approved.setStatus(WarehouseMonthlyCloseSheet.STATUS_APPROVED);
            when(sheetMapper.selectById(11L)).thenReturn(approved);

            ResponseStatusException e = assertThrows(ResponseStatusException.class,
                    () -> service.updateLine(1L, 11L, 21L, 9, null, null, null));
            assertEquals(409, e.getStatusCode().value(), "审批后锁单，编辑只能走重新生成");
        }
    }

    @Nested
    class Export {

        @Test
        void shouldProduceXlsxWith18FinancialColumns() throws Exception {
            WarehouseMonthlyCloseSheet sheet = draftSheet(11L);
            when(sheetMapper.selectById(11L)).thenReturn(sheet);
            WarehouseMonthlyCloseLine row = sheetLine(21L, 11L, "SKU-A", -3, 1500L);
            when(lineMapper.selectList(any())).thenReturn(List.of(row));

            byte[] bytes = service.exportXlsx(11L);

            assertTrue(bytes.length > 0);
            try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                assertEquals(18, wb.getSheetAt(0).getRow(0).getLastCellNum(), "财务 18 列对齐");
            }
        }
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private WarehouseMonthlyCloseSheet draftSheet(Long closeId) {
        WarehouseMonthlyCloseSheet s = new WarehouseMonthlyCloseSheet();
        s.setCloseId(closeId);
        s.setWarehouseId("WH-1");
        s.setYearMonth("2026-10");
        s.setStatus(WarehouseMonthlyCloseSheet.STATUS_DRAFT);
        return s;
    }

    private WarehouseMonthlyCloseLine sheetLine(Long lineId, Long closeId, String skuId,
                                                Integer gap, Long amount) {
        WarehouseMonthlyCloseLine l = new WarehouseMonthlyCloseLine();
        l.setLineId(lineId);
        l.setCloseId(closeId);
        l.setSkuId(skuId);
        l.setSkuName("SKU-" + skuId);
        l.setExpectedQty(12);
        l.setCountedQty(12 + gap);
        l.setGapQty(gap);
        l.setGapAmountCents(amount);
        l.setGapDisposition(WarehouseMonthlyCloseLine.DISP_PENDING);
        return l;
    }
}
