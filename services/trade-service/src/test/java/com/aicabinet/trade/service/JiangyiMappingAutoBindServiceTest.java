package com.aicabinet.trade.service;

import com.aicabinet.trade.client.JiangyiGatherClient;
import com.aicabinet.trade.domain.JiangyiClassMapping;
import com.aicabinet.trade.domain.JiangyiDevice;
import com.aicabinet.trade.domain.JiangyiSkuJiangyiLink;
import com.aicabinet.trade.dto.JiangyiGatherDtos.JiangyiStdSku;
import com.aicabinet.trade.dto.JiangyiGatherDtos.LearnableProduct;
import com.aicabinet.trade.mapper.JiangyiClassMappingMapper;
import com.aicabinet.trade.mapper.JiangyiDeviceMapper;
import com.aicabinet.trade.mapper.JiangyiSkuJiangyiLinkMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 挂接即生效的服务侧契约：按识别名唯一对位才动手，冲突/被别的 SKU 占用一律跳过不猜。
 */
@ExtendWith(MockitoExtension.class)
class JiangyiMappingAutoBindServiceTest {

    private static final String SKU_ID = "SKU-100009";
    private static final String OTHER_SKU = "SKU-100017";
    private static final String PID = "4083";
    private static final String TEXT_NAME = "kkkl_xb_qsnmw_pttz_330ml";
    private static final String DEV_A = "EG858564255060";
    private static final String DEV_B = "EG858564255061";

    @Mock private JiangyiDeviceMapper deviceMapper;
    @Mock private JiangyiClassMappingMapper mappingMapper;
    @Mock private JiangyiSkuJiangyiLinkMapper linkMapper;
    @Mock private JiangyiGatherClient gatherClient;
    @Mock private AdminAuditService auditService;

    private JiangyiMappingAutoBindService service() {
        return new JiangyiMappingAutoBindService(deviceMapper, mappingMapper, linkMapper,
                gatherClient, auditService);
    }

    private static JiangyiDevice device(String deviceId) {
        JiangyiDevice d = new JiangyiDevice();
        d.setDeviceId(deviceId);
        d.setStatus("BOUND");
        return d;
    }

    private static JiangyiClassMapping row(String deviceId, int classId, String textName,
                                           String skuId, String status) {
        JiangyiClassMapping m = new JiangyiClassMapping();
        m.setId((long) classId + 1);
        m.setDeviceId(deviceId);
        m.setClassId(classId);
        m.setTextName(textName);
        m.setSkuId(skuId);
        m.setStatus(status);
        m.setSource("MODEL_SYNC");
        return m;
    }

    private void learnable(String textName) {
        when(gatherClient.learnableProducts())
                .thenReturn(List.of(new LearnableProduct(Long.parseLong(PID), "雪碧", textName, "6928804011203")));
    }

    /** 复核预生成的空行（DISABLED + sku 空）→ 挂上并启用。 */
    @Test
    void bindsToEmptyPregeneratedRow() {
        learnable(TEXT_NAME);
        when(deviceMapper.listBound()).thenReturn(List.of(device(DEV_A)));
        when(mappingMapper.listByDevice(DEV_A))
                .thenReturn(List.of(row(DEV_A, 29, TEXT_NAME, null, "DISABLED")));

        JiangyiMappingAutoBindService.Result result = service().autoBind(7L, SKU_ID, PID, null);

        assertEquals(1, result.bound());
        assertEquals(0, result.already());
        assertEquals(0, result.pendingManual());
        assertEquals(TEXT_NAME, result.textName());
        ArgumentCaptor<JiangyiClassMapping> captor = ArgumentCaptor.forClass(JiangyiClassMapping.class);
        verify(mappingMapper).upsert(captor.capture());
        assertEquals(SKU_ID, captor.getValue().getSkuId());
        assertEquals("ACTIVE", captor.getValue().getStatus());
        // classId/textName 原样不动：动了这两个字段复核戳就失效
        assertEquals(29, captor.getValue().getClassId());
        assertEquals(TEXT_NAME, captor.getValue().getTextName());
        verify(auditService).appendLog(anyLong(), any(), any(), any(), any());
    }

    /** 同 SKU 且已生效 → 幂等，不重复写库。 */
    @Test
    void keepsAlreadyActiveRowUntouched() {
        when(deviceMapper.listBound()).thenReturn(List.of(device(DEV_A)));
        when(mappingMapper.listByDevice(DEV_A))
                .thenReturn(List.of(row(DEV_A, 29, TEXT_NAME, SKU_ID, "ACTIVE")));

        JiangyiMappingAutoBindService.Result result = service().autoBind(7L, SKU_ID, PID, TEXT_NAME);

        assertEquals(0, result.bound());
        assertEquals(1, result.already());
        verify(mappingMapper, never()).upsert(any());
        verifyNoInteractions(gatherClient);
    }

    /** 同 SKU 但被停用 → 启用（与 shelveOne 一致）；不算「新挂」。 */
    @Test
    void reEnablesDisabledRowOfSameSku() {
        when(deviceMapper.listBound()).thenReturn(List.of(device(DEV_A)));
        when(mappingMapper.listByDevice(DEV_A))
                .thenReturn(List.of(row(DEV_A, 29, TEXT_NAME, SKU_ID, "DISABLED")));

        JiangyiMappingAutoBindService.Result result = service().autoBind(7L, SKU_ID, PID, TEXT_NAME);

        assertEquals(1, result.bound());
        ArgumentCaptor<JiangyiClassMapping> captor = ArgumentCaptor.forClass(JiangyiClassMapping.class);
        verify(mappingMapper).upsert(captor.capture());
        assertEquals("ACTIVE", captor.getValue().getStatus());
        assertEquals(SKU_ID, captor.getValue().getSkuId());
    }

    /** 该识别名已挂另一个 SKU → 不抢收款对象，回传原因。 */
    @Test
    void skipsRowOwnedByAnotherSku() {
        when(deviceMapper.listBound()).thenReturn(List.of(device(DEV_A)));
        when(mappingMapper.listByDevice(DEV_A))
                .thenReturn(List.of(row(DEV_A, 29, TEXT_NAME, OTHER_SKU, "ACTIVE")));

        JiangyiMappingAutoBindService.Result result = service().autoBind(7L, SKU_ID, PID, TEXT_NAME);

        assertEquals(0, result.bound());
        assertEquals(1, result.pendingManual());
        assertTrue(result.notes().get(0).reason().contains(OTHER_SKU));
        verify(mappingMapper, never()).upsert(any());
    }

    /** 同一识别名多行（冲突）→ 不猜。 */
    @Test
    void skipsConflictingTextName() {
        when(deviceMapper.listBound()).thenReturn(List.of(device(DEV_A)));
        when(mappingMapper.listByDevice(DEV_A)).thenReturn(List.of(
                row(DEV_A, 28, TEXT_NAME, null, "DISABLED"),
                row(DEV_A, 29, TEXT_NAME, null, "DISABLED")));

        JiangyiMappingAutoBindService.Result result = service().autoBind(7L, SKU_ID, PID, TEXT_NAME);

        assertEquals(0, result.bound());
        assertEquals(1, result.pendingManual());
        assertTrue(result.notes().get(0).reason().contains("冲突"));
        verify(mappingMapper, never()).upsert(any());
    }

    /** 负编号是复核腾位哨兵：即便识别名匹配也不能被点亮成旧编号的错映射。 */
    @Test
    void ignoresParkedSentinelRows() {
        when(deviceMapper.listBound()).thenReturn(List.of(device(DEV_A)));
        when(mappingMapper.listByDevice(DEV_A))
                .thenReturn(List.of(row(DEV_A, -31, TEXT_NAME, null, "DISABLED")));

        JiangyiMappingAutoBindService.Result result = service().autoBind(7L, SKU_ID, PID, TEXT_NAME);

        assertEquals(0, result.bound());
        assertEquals(1, result.pendingManual());
        verify(mappingMapper, never()).upsert(any());
    }

    /** 多台柜：一台能挂、一台已挂别人的 → 分别计数，互不影响。 */
    @Test
    void handlesEachDeviceIndependently() {
        when(deviceMapper.listBound()).thenReturn(List.of(device(DEV_A), device(DEV_B)));
        when(mappingMapper.listByDevice(DEV_A))
                .thenReturn(List.of(row(DEV_A, 29, TEXT_NAME, null, "DISABLED")));
        when(mappingMapper.listByDevice(DEV_B))
                .thenReturn(List.of(row(DEV_B, 29, TEXT_NAME, OTHER_SKU, "ACTIVE")));

        JiangyiMappingAutoBindService.Result result = service().autoBind(7L, SKU_ID, PID, TEXT_NAME);

        assertEquals(1, result.bound());
        assertEquals(1, result.pendingManual());
        assertTrue(result.message().contains("已挂到 1 台柜机"));
        assertEquals(2, result.notes().size());
        // 明细按「已生效优先」排序，前端照序展示
        assertEquals(DEV_A, result.notes().get(0).deviceId());
    }

    /** 标准库里这条商品没有识别名 → 一行都不动，并说清下一步。 */
    @Test
    void reportsMissingTextNameWithoutTouchingAnything() {
        when(gatherClient.learnableProducts()).thenReturn(List.of());

        JiangyiMappingAutoBindService.Result result = service().autoBind(7L, SKU_ID, PID, null);

        assertFalse(result.touched());
        assertEquals(null, result.textName());
        assertTrue(result.message().contains("没有识别类目名"));
        verifyNoInteractions(deviceMapper, mappingMapper);
    }

    /** 厂商读面不可达只降级成「本次不挂」，不能让挂接本身失败。 */
    @Test
    void degradesWhenGatherClientUnavailable() {
        when(gatherClient.learnableProducts()).thenThrow(new IllegalStateException("502"));

        JiangyiMappingAutoBindService.Result result = service().autoBind(7L, SKU_ID, PID, null);

        assertFalse(result.touched());
        assertTrue(result.message().contains("没有识别类目名"));
        verify(mappingMapper, never()).upsert(any());
    }

    /** 没有已接入的柜机 → 明确回「无需挂接」。 */
    @Test
    void reportsNoDevices() {
        when(deviceMapper.listBound()).thenReturn(List.of());

        JiangyiMappingAutoBindService.Result result = service().autoBind(7L, SKU_ID, PID, TEXT_NAME);

        assertFalse(result.touched());
        assertEquals(0, result.pendingManual());
        assertTrue(result.message().contains("没有已接入的柜机"));
    }

    // ---------- 识别名数据源（2026-11-20 生产实锤后的回归钉） ----------

    private static JiangyiStdSku stdSku(String id, String textName, String barCode) {
        return new JiangyiStdSku(Long.parseLong(id), "雪碧清爽柠檬味汽水普通听装330ml", barCode, "330ml",
                "雪碧", "饮料", null, null, "scan", textName);
    }

    /** 挂接行（bind 已落库的形态）：标准商品库按这两个字段反查识别名。 */
    private void linked(String barCode, String name) {
        JiangyiSkuJiangyiLink link = new JiangyiSkuJiangyiLink();
        link.setId(1L);
        link.setSkuId(SKU_ID);
        link.setJiangyiProductId(PID);
        link.setJiangyiName(name);
        link.setBarCode(barCode);
        link.setSyncStatus("BOUND");
        when(linkMapper.byProduct(PID)).thenReturn(Optional.of(link));
    }

    /**
     * 🔴 生产首版缺陷的回归钉：识别名取自标准商品库 {@code stdSku/getList} 的 textName
     * （按挂接行条码精确命中），而不是「待采集训练」名单——那份名单对人工建档商品恒为空，
     * 导致生产上每次挂接都报「还没有识别名」。
     */
    @Test
    void resolvesTextNameFromStdSkuByBarcode() {
        linked("6954767430386", "雪碧清爽柠檬味汽水普通听装330ml");
        when(gatherClient.stdSkuList(null, "6954767430386"))
                .thenReturn(List.of(stdSku(PID, TEXT_NAME, "6954767430386")));
        when(deviceMapper.listBound()).thenReturn(List.of(device(DEV_A)));
        when(mappingMapper.listByDevice(DEV_A))
                .thenReturn(List.of(row(DEV_A, 29, TEXT_NAME, null, "DISABLED")));

        JiangyiMappingAutoBindService.Result result = service().autoBind(7L, SKU_ID, PID, null);

        assertEquals(1, result.bound());
        assertEquals(TEXT_NAME, result.textName());
        // 可学习名单只作兜底：标准库命中就不该再打它（省一次外部调用，也避免被空名单误导）
        verify(gatherClient, never()).learnableProducts();
    }

    /** 条码与厂商不一致时（生产实测 100009 即如此），按名称查再用 id 精确命中，不能取第一条。 */
    @Test
    void fallsBackToNameLookupWhenBarcodeMisses() {
        linked("6928804010145", "雪碧清爽柠檬味汽水普通听装330ml");
        when(gatherClient.stdSkuList(null, "6928804010145")).thenReturn(List.of());
        when(gatherClient.stdSkuList("雪碧清爽柠檬味汽水普通听装330ml", null))
                .thenReturn(List.of(
                        stdSku("9999", "xblk_330mlxblknmwqs_330ml", "6928804014686"),
                        stdSku(PID, TEXT_NAME, "6954767430386")));
        when(deviceMapper.listBound()).thenReturn(List.of(device(DEV_A)));
        when(mappingMapper.listByDevice(DEV_A))
                .thenReturn(List.of(row(DEV_A, 29, TEXT_NAME, null, "DISABLED")));

        JiangyiMappingAutoBindService.Result result = service().autoBind(7L, SKU_ID, PID, null);

        assertEquals(1, result.bound());
        assertEquals(TEXT_NAME, result.textName());
        verify(mappingMapper).upsert(any());
    }

    /**
     * 识别名拿到了、但柜机模型里没有那一行 → 必须把识别名原样回给运营。
     * 生产实证：同款雪碧有两条（1018→第29行 / 2211→模型里没有），挂错那条只会看到「没挂上」，
     * 不说清识别名就等于让人反复重点。
     */
    @Test
    void explainsWhenTextNameNotInDeviceModel() {
        when(deviceMapper.listBound()).thenReturn(List.of(device(DEV_A)));
        when(mappingMapper.listByDevice(DEV_A))
                .thenReturn(List.of(row(DEV_A, 29, TEXT_NAME, null, "DISABLED")));

        JiangyiMappingAutoBindService.Result result =
                service().autoBind(7L, SKU_ID, PID, "xb_xbqsnmwqs_330ml");

        assertEquals(0, result.bound());
        assertTrue(result.message().contains("xb_xbqsnmwqs_330ml"));
        assertTrue(result.message().contains("改挂"));
        assertTrue(result.notes().get(0).reason().contains("不在这台柜的模型里"));
        verify(mappingMapper, never()).upsert(any());
    }
}
