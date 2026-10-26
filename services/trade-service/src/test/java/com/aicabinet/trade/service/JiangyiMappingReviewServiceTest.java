package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.JiangyiClassMapping;
import com.aicabinet.trade.domain.JiangyiDevice;
import com.aicabinet.trade.mapper.JiangyiClassMappingMapper;
import com.aicabinet.trade.mapper.JiangyiDeviceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 重训后映射重算 + 强制复核单测（V400，台账 H-27）。
 *
 * <p>核心契约：<b>映射的身份是 textName，不是 classId</b>——重训插入类目会把后面所有
 * classId 整体平移（实测可乐/雪碧 22/25 → 26/29），重算必须按识别名把 SKU 挪到新编号；
 * 同编号换了商品的「交换平移」也不得被当成 KEEP。应用成功后写复核戳；版本令牌不符/
 * 冲突/柜机版本不一致一律拒绝且不落任何写。</p>
 */
@ExtendWith(MockitoExtension.class)
class JiangyiMappingReviewServiceTest {

    private static final String DEVICE = "EG943322621391";

    @Mock private JiangyiDeviceMapper deviceMapper;
    @Mock private JiangyiClassMappingMapper mappingMapper;
    @Mock private JiangyiModelSyncService modelSyncService;
    @Mock private AdminAuditService adminAuditService;
    @Mock private DistributedLockService lockService;

    private JiangyiMappingReviewService service;

    @BeforeEach
    void setUp() {
        service = new JiangyiMappingReviewService(deviceMapper, mappingMapper,
                modelSyncService, adminAuditService, lockService);
    }

    // ---------- 差异计算 ----------

    @Test
    void retrainShiftMovesSkuByNameAndParksVanishedText() {
        // 65 类 → 69 类实况：两款饮料从 26/27 平移到 30/31，26 被新类目占用
        List<JiangyiClassMapping> existing = List.of(
                mapping(75, 26, "kkkl_jdmw_pttz_330ml", "100016", "ACTIVE"),
                mapping(76, 27, "kkkl_qs_jdmw_500ml", "100017", "ACTIVE"),
                mapping(99, 0, "vendor_dropped_flavor", "100099", "ACTIVE"));

        JiangyiMappingReviewService.RebuildPlan plan = JiangyiMappingReviewService.computePlan(
                device(null), "model-v2",
                parsed("v2", row(26, "brand_new_flavor"),
                        row(30, "kkkl_jdmw_pttz_330ml"),
                        row(31, "kkkl_qs_jdmw_500ml")),
                existing);

        assertEquals(new JiangyiMappingReviewService.Counts(0, 2, 1, 1, 0), plan.counts());
        Map<String, JiangyiMappingReviewService.RebuildRow> byText = byText(plan);
        JiangyiMappingReviewService.RebuildRow moved = byText.get("kkkl_jdmw_pttz_330ml");
        assertEquals(JiangyiMappingReviewService.ACTION_MOVE, moved.action());
        assertEquals(30, moved.classId());
        assertEquals(26, moved.oldClassId());
        assertEquals("100016", moved.skuId(), "SKU 跟随识别名，不跟随编号");
        assertEquals(JiangyiMappingReviewService.ACTION_NEW, byText.get("brand_new_flavor").action());
        assertEquals("DISABLED", byText.get("brand_new_flavor").status(), "新类目先停用等人工挂 SKU");

        JiangyiMappingReviewService.RebuildRow orphan = byText.get("vendor_dropped_flavor");
        assertEquals(JiangyiMappingReviewService.ACTION_ORPHAN, orphan.action());
        assertNull(orphan.classId(), "失效行腾出编号");
        assertEquals(0, orphan.oldClassId());
        assertEquals("100099", orphan.skuId(), "挂接留档不丢");
        assertEquals("DISABLED", orphan.status());

        assertTrue(plan.adoptVersion(), "柜机无 classes_version（厂商直推）→ 本次审阅认领版本");
        assertFalse(plan.versionMismatch());
        assertFalse(plan.verified());
    }

    @Test
    void swappedClassIdsAreTwoMovesNotKeeps() {
        // 最险的形态：两个编号都还在，只是各自身上的商品换了
        List<JiangyiClassMapping> existing = List.of(
                mapping(1, 22, "cola", "SKU-COLA", "ACTIVE"),
                mapping(2, 25, "sprite", "SKU-SPRITE", "ACTIVE"));

        JiangyiMappingReviewService.RebuildPlan plan = JiangyiMappingReviewService.computePlan(
                device("v1"), "model-v1", parsed("v1", row(22, "sprite"), row(25, "cola")), existing);

        assertEquals(new JiangyiMappingReviewService.Counts(0, 2, 0, 0, 0), plan.counts());
        JiangyiMappingReviewService.RebuildRow cola = plan.rows().stream()
                .filter(r -> "cola".equals(r.textName())).findFirst().orElseThrow();
        assertEquals(JiangyiMappingReviewService.ACTION_MOVE, cola.action());
        assertEquals(25, cola.classId());
        assertEquals("SKU-COLA", cola.skuId());
    }

    @Test
    void duplicateTextNameInsideClassesIsConflict() {
        JiangyiMappingReviewService.RebuildPlan plan = JiangyiMappingReviewService.computePlan(
                device("v1"), "m",
                parsed("v1", row(22, "cola"), row(30, "cola")),
                List.of(mapping(1, 22, "cola", "SKU-COLA", "ACTIVE")));

        assertEquals(1, plan.counts().conflicts());
        assertEquals(1, plan.counts().kept());
    }

    @Test
    void multipleExistingRowsForSameTextIsConflictPerRow() {
        JiangyiMappingReviewService.RebuildPlan plan = JiangyiMappingReviewService.computePlan(
                device("v1"), "m",
                parsed("v1", row(22, "cola")),
                List.of(mapping(1, 22, "cola", "SKU-A", "ACTIVE"),
                        mapping(2, 23, "cola", "SKU-B", "DISABLED")));

        assertEquals(2, plan.counts().conflicts());
        assertEquals(0, plan.counts().kept());
    }

    @Test
    void classIdZeroIsAValidClass() {
        JiangyiMappingReviewService.RebuildPlan plan = JiangyiMappingReviewService.computePlan(
                device("v1"), "m", parsed("v1", row(0, "cola")),
                List.of(mapping(1, 0, "cola", "SKU-COLA", "ACTIVE")));

        assertEquals(1, plan.counts().kept());
        assertEquals(0, plan.counts().orphaned());
    }

    @Test
    void deviceRunningAnotherVersionIsFlagged() {
        JiangyiMappingReviewService.RebuildPlan plan = JiangyiMappingReviewService.computePlan(
                device("v-old"), "m", parsed("v-new", row(22, "cola")), List.of());

        assertTrue(plan.versionMismatch());
        assertFalse(plan.adoptVersion());
    }

    @Test
    void verifiedOnlyWhenDeviceVersionEqualsStampedVersion() {
        JiangyiDevice verified = device("v1");
        verified.setMappingVerifiedVersion("v1");
        assertTrue(JiangyiMappingReviewService.computePlan(verified, "m",
                parsed("v1", row(22, "cola")), List.of()).verified());

        JiangyiDevice stale = device("v2");
        stale.setMappingVerifiedVersion("v1");
        assertFalse(JiangyiMappingReviewService.computePlan(stale, "m",
                parsed("v2", row(22, "cola")), List.of()).verified());
    }

    // ---------- 复核态 ----------

    @Test
    void statusUnknownDeviceAndNullVersionAreUnverified() {
        when(deviceMapper.selectById("nope")).thenReturn(null);
        assertFalse(service.status("nope").verified());

        when(deviceMapper.selectById(DEVICE)).thenReturn(device(null));
        assertFalse(service.isVerified(DEVICE), "厂商直推设备没有 classes_version ⇒ 未复核");
    }

    @Test
    void statusVerifiedWhenVersionsMatch() {
        JiangyiDevice d = device("v1");
        d.setMappingVerifiedVersion("v1");
        when(deviceMapper.selectById(DEVICE)).thenReturn(d);

        JiangyiMappingReviewService.MappingStatus status = service.status(DEVICE);
        assertTrue(status.verified());
        assertEquals("v1", status.classesVersion());
    }

    // ---------- 应用（人工确认） ----------

    @Test
    void applyRejectsStaleReviewTokenWithoutWriting() {
        when(lockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        when(deviceMapper.selectById(DEVICE)).thenReturn(device("v1"));
        when(modelSyncService.parseClassesForReview("https://x/classes.txt", 3, 0))
                .thenReturn(parsed("v2", row(22, "cola")));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                service.apply(DEVICE, "m", "https://x/classes.txt", 3, 0, "v1", 9L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(mappingMapper, never()).parkForRelocation(anyLong(), anyInt(), any());
        verify(mappingMapper, never()).relocateClassId(anyLong(), anyInt(), any());
        verify(deviceMapper, never()).stampMappingVerified(anyString(), anyString(), any());
        verify(lockService).unlock(anyString());
    }

    @Test
    void applyRejectsDeviceVersionMismatchWithoutWriting() {
        when(lockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        when(deviceMapper.selectById(DEVICE)).thenReturn(device("v-other"));
        when(modelSyncService.parseClassesForReview("https://x/classes.txt", 3, 0))
                .thenReturn(parsed("v1", row(22, "cola")));
        when(mappingMapper.listByDevice(DEVICE)).thenReturn(List.of());

        assertThrows(ResponseStatusException.class, () ->
                service.apply(DEVICE, "m", "https://x/classes.txt", 3, 0, "v1", 9L));

        verify(mappingMapper, never()).parkForRelocation(anyLong(), anyInt(), any());
        verify(deviceMapper, never()).stampMappingVerified(anyString(), anyString(), any());
    }

    @Test
    void applyRejectsConflictsWithoutWriting() {
        when(lockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        when(deviceMapper.selectById(DEVICE)).thenReturn(device("v1"));
        when(modelSyncService.parseClassesForReview("https://x/classes.txt", 3, 0))
                .thenReturn(parsed("v1", row(22, "cola"), row(30, "cola")));
        when(mappingMapper.listByDevice(DEVICE)).thenReturn(List.of());

        assertThrows(ResponseStatusException.class, () ->
                service.apply(DEVICE, "m", "https://x/classes.txt", 3, 0, "v1", 9L));

        verify(mappingMapper, never()).parkForRelocation(anyLong(), anyInt(), any());
        verify(deviceMapper, never()).stampMappingVerified(anyString(), anyString(), any());
    }

    @Test
    void applyParksThenRelocatesAndStampsVerifiedVersion() {
        when(lockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        when(deviceMapper.selectById(DEVICE)).thenReturn(device(null));
        when(modelSyncService.parseClassesForReview("https://x/classes.txt", 4, 0))
                .thenReturn(parsed("v1", row(30, "cola"), row(31, "sprite")));
        when(mappingMapper.listByDevice(DEVICE)).thenReturn(List.of(
                mapping(75, 26, "cola", "100016", "ACTIVE"),
                mapping(76, 27, "sprite", "100017", "ACTIVE")));
        when(deviceMapper.adoptClassesVersionIfAbsent(eq(DEVICE), eq("v1"), any())).thenReturn(1);

        JiangyiMappingReviewService.ApplyResult result = service.apply(
                DEVICE, "m", "https://x/classes.txt", 4, 0, "v1", 9L);

        assertEquals("v1", result.classesVersion());
        assertTrue(result.adoptedVersion());
        assertEquals(new JiangyiMappingReviewService.Counts(0, 2, 0, 0, 0), result.counts());
        // 两阶段：先挪负哨兵腾位（-行 id），再落新编号——否则连环平移撞 UNIQUE(device_id, class_id)
        verify(mappingMapper).parkForRelocation(eq(75L), eq(-75), any());
        verify(mappingMapper).parkForRelocation(eq(76L), eq(-76), any());
        verify(mappingMapper).relocateClassId(eq(75L), eq(30), any());
        verify(mappingMapper).relocateClassId(eq(76L), eq(31), any());
        verify(deviceMapper).stampMappingVerified(eq(DEVICE), eq("v1"), any());
        verify(adminAuditService).appendLog(eq(9L), eq("JIANGYI_MAPPING_REVIEW"),
                eq("jiangyi_device"), eq(DEVICE), anyString());
        verify(lockService).unlock(anyString());
    }

    @Test
    void applyRefusesWhenAdoptionLostRaceToAnotherVersion() {
        when(lockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        when(deviceMapper.selectById(DEVICE)).thenReturn(device(null), device("v-racer"));
        when(modelSyncService.parseClassesForReview("https://x/classes.txt", 1, 0))
                .thenReturn(parsed("v1", row(22, "cola")));
        when(mappingMapper.listByDevice(DEVICE)).thenReturn(List.of());
        when(deviceMapper.adoptClassesVersionIfAbsent(eq(DEVICE), eq("v1"), any())).thenReturn(0);

        assertThrows(ResponseStatusException.class, () ->
                service.apply(DEVICE, "m", "https://x/classes.txt", 1, 0, "v1", 9L));

        verify(deviceMapper, never()).stampMappingVerified(anyString(), anyString(), any());
    }

    // ---------- 自动复核（重训回执后） ----------

    @Test
    void autoRebuildAppliesPureShiftAndStamps() {
        when(lockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        when(deviceMapper.selectById(DEVICE)).thenReturn(device("v2"));
        when(modelSyncService.parseClassesForReview("https://x/classes.txt", 0, 0))
                .thenReturn(parsed("v2", row(30, "cola"), row(31, "sprite")));
        when(mappingMapper.listByDevice(DEVICE)).thenReturn(List.of(
                mapping(75, 26, "cola", "100016", "ACTIVE"),
                mapping(76, 27, "sprite", "100017", "ACTIVE")));

        JiangyiMappingReviewService.AutoResult result =
                service.autoRebuild(DEVICE, "m", "https://x/classes.txt", "v2");

        assertTrue(result.applied(), "类目集没变、只是行号平移 ⇒ 不需要人判断");
        assertEquals(new JiangyiMappingReviewService.Counts(0, 2, 0, 0, 0), result.counts());
        assertEquals("v2", result.classesVersion());
        verify(mappingMapper).parkForRelocation(eq(75L), eq(-75), any());
        verify(mappingMapper).parkForRelocation(eq(76L), eq(-76), any());
        verify(mappingMapper).relocateClassId(eq(75L), eq(30), any());
        verify(mappingMapper).relocateClassId(eq(76L), eq(31), any());
        verify(deviceMapper).stampMappingVerified(eq(DEVICE), eq("v2"), any());
        verify(adminAuditService, never()).appendLog(any(), anyString(), anyString(), anyString(), anyString());
        verify(lockService).unlock(anyString());
    }

    @Test
    void autoRebuildCreatesDisabledRowForNewCategoryAndKeepsOldSelling() {
        when(lockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        when(deviceMapper.selectById(DEVICE)).thenReturn(device("v2"));
        when(modelSyncService.parseClassesForReview("https://x/classes.txt", 0, 0))
                .thenReturn(parsed("v2", row(26, "cola"), row(30, "kkkl_xb_qsnmw_pttz_330ml")));
        when(mappingMapper.listByDevice(DEVICE)).thenReturn(List.of(
                mapping(75, 26, "cola", "100016", "ACTIVE")));
        ArgumentCaptor<JiangyiClassMapping> inserted = ArgumentCaptor.forClass(JiangyiClassMapping.class);

        JiangyiMappingReviewService.AutoResult result =
                service.autoRebuild(DEVICE, "m", "https://x/classes.txt", "v2");

        assertTrue(result.applied(), "新增类目不该拦住老商品恢复可售");
        assertEquals(new JiangyiMappingReviewService.Counts(1, 0, 1, 0, 0), result.counts());
        assertEquals(1, result.pendingManual(), "新类目待挂 SKU 的工作量要带回去");
        verify(mappingMapper).insert(inserted.capture());
        JiangyiClassMapping fresh = inserted.getValue();
        assertEquals(30, fresh.getClassId());
        assertEquals("kkkl_xb_qsnmw_pttz_330ml", fresh.getTextName());
        assertNull(fresh.getSkuId(), "新行不挂 SKU");
        assertEquals("DISABLED", fresh.getStatus(), "新行不启用 ⇒ 不可能错扣款");
        verify(deviceMapper).stampMappingVerified(eq(DEVICE), eq("v2"), any());
        verify(lockService).unlock(anyString());
    }

    @Test
    void autoRebuildParksVanishedClassSoReusedNumberCannotChargeWrongSku() {        when(lockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        when(deviceMapper.selectById(DEVICE)).thenReturn(device("v2"));
        when(modelSyncService.parseClassesForReview("https://x/classes.txt", 0, 0))
                .thenReturn(parsed("v2", row(22, "cola")));
        when(mappingMapper.listByDevice(DEVICE)).thenReturn(List.of(
                mapping(75, 22, "cola", "100016", "ACTIVE"),
                mapping(76, 23, "vendor_renamed_flavor", "100017", "ACTIVE")));

        JiangyiMappingReviewService.AutoResult result =
                service.autoRebuild(DEVICE, "m", "https://x/classes.txt", "v2");

        assertTrue(result.applied(), "类目消失要腾编号，但不该拦住其它商品");
        assertEquals(1, result.freshOrphans());
        assertEquals(1, result.pendingManual(), "消失类目可能是改名，欠人工确认");
        verify(mappingMapper).parkAsOrphan(eq(76L), eq(-76), any());
        verify(mappingMapper, never()).relocateClassId(eq(76L), anyInt(), any());
        verify(deviceMapper).stampMappingVerified(eq(DEVICE), eq("v2"), any());
    }

    @Test
    void autoRebuildBlocksOnConflictOnly() {
        when(lockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        when(deviceMapper.selectById(DEVICE)).thenReturn(device("v2"));
        when(modelSyncService.parseClassesForReview("https://x/classes.txt", 0, 0))
                .thenReturn(parsed("v2", row(22, "cola")));
        when(mappingMapper.listByDevice(DEVICE)).thenReturn(List.of(
                mapping(75, 22, "cola", "100016", "ACTIVE"),
                mapping(76, 23, "cola", "100017", "ACTIVE")));

        JiangyiMappingReviewService.AutoResult result =
                service.autoRebuild(DEVICE, "m", "https://x/classes.txt", "v2");

        assertFalse(result.applied(), "同识别名对应多个 SKU ⇒ 机器无法判断该挂谁");
        assertEquals(2, result.counts().conflicts());
        verify(mappingMapper, never()).parkForRelocation(anyLong(), anyInt(), any());
        verify(mappingMapper, never()).parkAsOrphan(anyLong(), anyInt(), any());
        verify(mappingMapper, never()).relocateClassId(anyLong(), anyInt(), any());
        verify(mappingMapper, never()).insert(any());
        verify(deviceMapper, never()).stampMappingVerified(anyString(), anyString(), any());
        verify(lockService).unlock(anyString());
    }

    @Test
    void autoRebuildAutoHangsNewCategoryWhenBarcodeUniquelyMatches() {
        when(lockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        when(deviceMapper.selectById(DEVICE)).thenReturn(device("v2"));
        when(modelSyncService.parseClassesForReview("https://x/classes.txt", 0, 0))
                .thenReturn(parsed("v2", row(26, "cola"), row(30, "newprod")));
        when(mappingMapper.listByDevice(DEVICE)).thenReturn(List.of(
                mapping(75, 26, "cola", "100016", "ACTIVE")));
        when(modelSyncService.uniqueBarcodeSkuMatches(eq("https://x/classes.txt"), any()))
                .thenReturn(Map.of("newprod", "100099"));
        ArgumentCaptor<JiangyiClassMapping> inserted = ArgumentCaptor.forClass(JiangyiClassMapping.class);

        JiangyiMappingReviewService.AutoResult result =
                service.autoRebuild(DEVICE, "m", "https://x/classes.txt", "v2");

        assertTrue(result.applied());
        assertEquals(1, result.autoHung(), "条码唯一命中的新类目直接挂上");
        assertEquals(0, result.pendingManual(), "自动挂上的不再欠人工");
        verify(mappingMapper).insert(inserted.capture());
        assertEquals("100099", inserted.getValue().getSkuId());
        assertEquals("ACTIVE", inserted.getValue().getStatus());
        assertEquals("MODEL_AUTO_MATCH", inserted.getValue().getSource(), "来源要能和人工挂接区分");
        verify(deviceMapper).stampMappingVerified(eq(DEVICE), eq("v2"), any());
    }

    @Test
    void autoRebuildKeepsNewCategoryManualWhenSkuAlreadyHung() {
        when(lockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        when(deviceMapper.selectById(DEVICE)).thenReturn(device("v2"));
        when(modelSyncService.parseClassesForReview("https://x/classes.txt", 0, 0))
                .thenReturn(parsed("v2", row(26, "cola"), row(30, "newprod")));
        when(mappingMapper.listByDevice(DEVICE)).thenReturn(List.of(
                mapping(75, 26, "cola", "100016", "ACTIVE"),
                // 同一商品已挂在别的编号上（停用留档行也算）⇒ 不能再挂一次
                mapping(73, -73, "", "100099", "DISABLED")));
        when(modelSyncService.uniqueBarcodeSkuMatches(eq("https://x/classes.txt"), any()))
                .thenReturn(Map.of("newprod", "100099"));
        ArgumentCaptor<JiangyiClassMapping> inserted = ArgumentCaptor.forClass(JiangyiClassMapping.class);

        JiangyiMappingReviewService.AutoResult result =
                service.autoRebuild(DEVICE, "m", "https://x/classes.txt", "v2");

        assertTrue(result.applied());
        assertEquals(0, result.autoHung());
        assertEquals(1, result.pendingManual());
        verify(mappingMapper).insert(inserted.capture());
        assertNull(inserted.getValue().getSkuId());
        assertEquals("DISABLED", inserted.getValue().getStatus());
        assertEquals("MODEL_SYNC", inserted.getValue().getSource());
    }

    @Test
    void autoRebuildStaysAppliedWhenBarcodeMatchFails() {
        when(lockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        when(deviceMapper.selectById(DEVICE)).thenReturn(device("v2"));
        when(modelSyncService.parseClassesForReview("https://x/classes.txt", 0, 0))
                .thenReturn(parsed("v2", row(26, "cola"), row(30, "newprod")));
        when(mappingMapper.listByDevice(DEVICE)).thenReturn(List.of(
                mapping(75, 26, "cola", "100016", "ACTIVE")));
        when(modelSyncService.uniqueBarcodeSkuMatches(eq("https://x/classes.txt"), any()))
                .thenThrow(new IllegalStateException("厂商 503 设备不在线"));
        ArgumentCaptor<JiangyiClassMapping> inserted = ArgumentCaptor.forClass(JiangyiClassMapping.class);

        JiangyiMappingReviewService.AutoResult result =
                service.autoRebuild(DEVICE, "m", "https://x/classes.txt", "v2");

        assertTrue(result.applied(), "挂接判定失败只等于省不掉人工，不能把复核打回");
        assertEquals(0, result.autoHung());
        verify(mappingMapper).insert(inserted.capture());
        assertNull(inserted.getValue().getSkuId());
        assertEquals("DISABLED", inserted.getValue().getStatus());
    }

    @Test
    void autoRebuildIgnoresAlreadyParkedOrphans() {
        when(lockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        when(deviceMapper.selectById(DEVICE)).thenReturn(device("v2"));
        when(modelSyncService.parseClassesForReview("https://x/classes.txt", 0, 0))
                .thenReturn(parsed("v2", row(22, "cola")));
        when(mappingMapper.listByDevice(DEVICE)).thenReturn(List.of(
                mapping(75, 22, "cola", "100016", "ACTIVE"),
                // 10-23 停用 classId 30/31 留下的孤儿行：编号已是负哨兵、text_name 已清空
                mapping(73, -73, "", "100014", "DISABLED")));

        JiangyiMappingReviewService.AutoResult result =
                service.autoRebuild(DEVICE, "m", "https://x/classes.txt", "v2");

        assertTrue(result.applied(), "历史孤儿行不能让之后每次自动复核都被永久拦住");
        assertEquals(0, result.freshOrphans());
        verify(deviceMapper).stampMappingVerified(eq(DEVICE), eq("v2"), any());
    }

    @Test
    void autoRebuildRequiresContentFingerprintMatch() {
        when(lockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        when(deviceMapper.selectById(DEVICE)).thenReturn(device("v2"));
        when(modelSyncService.parseClassesForReview("https://x/classes.txt", 0, 0))
                .thenReturn(parsed("v-replaced", row(22, "cola")));

        JiangyiMappingReviewService.AutoResult result =
                service.autoRebuild(DEVICE, "m", "https://x/classes.txt", "v2");

        assertFalse(result.applied(), "OSS 上的文件被换过就不能自动落位");
        assertTrue(result.reason().contains("不一致"));
        verify(mappingMapper, never()).relocateClassId(anyLong(), anyInt(), any());
        verify(deviceMapper, never()).stampMappingVerified(anyString(), anyString(), any());
    }

    @Test
    void autoRebuildSkipsWithoutClassesUrlWithoutTakingLock() {
        JiangyiMappingReviewService.AutoResult result =
                service.autoRebuild(DEVICE, "m", "  ", "v2");

        assertFalse(result.applied(), "厂商直推模型我方没有 textUrl，只能走人工");
        verify(lockService, never()).tryLock(anyString(), anyLong(), anyLong());
    }

    // ---------- 装配 ----------

    private JiangyiDevice device(String classesVersion) {
        JiangyiDevice d = new JiangyiDevice();
        d.setDeviceId(DEVICE);
        d.setStatus("BOUND");
        d.setClassesVersion(classesVersion);
        return d;
    }

    private JiangyiClassMapping mapping(long id, int classId, String textName, String skuId, String status) {
        JiangyiClassMapping m = new JiangyiClassMapping();
        m.setId(id);
        m.setDeviceId(DEVICE);
        m.setClassId(classId);
        m.setTextName(textName);
        m.setSkuId(skuId);
        m.setStatus(status);
        m.setSource("MANUAL");
        return m;
    }

    private static JiangyiClassesParser.ParsedClassRow row(int classId, String textName) {
        return new JiangyiClassesParser.ParsedClassRow(classId, textName, classId + 1);
    }

    private static JiangyiClassesParser.ParsedClasses parsed(
            String version, JiangyiClassesParser.ParsedClassRow... rows) {
        return new JiangyiClassesParser.ParsedClasses(List.of(rows), version);
    }

    private static Map<String, JiangyiMappingReviewService.RebuildRow> byText(
            JiangyiMappingReviewService.RebuildPlan plan) {
        return plan.rows().stream().collect(Collectors.toMap(
                JiangyiMappingReviewService.RebuildRow::textName, r -> r, (a, b) -> a));
    }
}
