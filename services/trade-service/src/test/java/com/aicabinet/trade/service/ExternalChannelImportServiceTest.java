package com.aicabinet.trade.service;

import com.aicabinet.common.dto.ExternalImportBatchDto;
import com.aicabinet.common.dto.ExternalImportCommitRequest;
import com.aicabinet.common.dto.ExternalProductDto;
import com.aicabinet.trade.domain.ExternalChannelFlow;
import com.aicabinet.trade.domain.ExternalImportBatch;
import com.aicabinet.trade.domain.ExternalImportTemplate;
import com.aicabinet.trade.domain.ExternalProduct;
import com.aicabinet.trade.domain.ExternalProductMapping;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.domain.Warehouse;
import com.aicabinet.trade.mapper.ExternalChannelFlowMapper;
import com.aicabinet.trade.mapper.ExternalImportBatchMapper;
import com.aicabinet.trade.mapper.ExternalImportTemplateMapper;
import com.aicabinet.trade.mapper.ExternalProductMapper;
import com.aicabinet.trade.mapper.ExternalProductMappingMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.mapper.WarehouseMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 外部渠道对账导入（CB-036）：预览建档 / 提交聚合闸门 / 映射治理（V347 家族）。 */
class ExternalChannelImportServiceTest {

    private ExternalImportTemplateMapper templateMapper;
    private ExternalProductMapper productMapper;
    private ExternalProductMappingMapper mappingMapper;
    private ExternalImportBatchMapper batchMapper;
    private ExternalChannelFlowMapper flowMapper;
    private SkuCatalogMapper skuCatalogMapper;
    private UserInfoMapper userInfoMapper;
    private WarehouseMapper warehouseMapper;
    private AdminAuditService auditService;
    private ExternalChannelImportService service;

    private static final String COLUMN_MAP =
            "{\"extId\":\"商品ID\",\"extName\":\"商品名称\",\"qty\":\"补货数量\",\"happenedAt\":\"补货时间\"}";

    @BeforeEach
    void setUp() {
        templateMapper = Mockito.mock(ExternalImportTemplateMapper.class);
        productMapper = Mockito.mock(ExternalProductMapper.class);
        mappingMapper = Mockito.mock(ExternalProductMappingMapper.class);
        batchMapper = Mockito.mock(ExternalImportBatchMapper.class);
        flowMapper = Mockito.mock(ExternalChannelFlowMapper.class);
        skuCatalogMapper = Mockito.mock(SkuCatalogMapper.class);
        userInfoMapper = Mockito.mock(UserInfoMapper.class);
        warehouseMapper = Mockito.mock(WarehouseMapper.class);
        auditService = Mockito.mock(AdminAuditService.class);
        service = new ExternalChannelImportService(templateMapper, productMapper, mappingMapper,
                batchMapper, flowMapper, skuCatalogMapper, userInfoMapper, warehouseMapper,
                auditService, new ExternalChannelFileParser(new ObjectMapper()), new ObjectMapper());
    }

    private ExternalImportTemplate template() {
        ExternalImportTemplate t = new ExternalImportTemplate();
        t.setTemplateId(1L);
        t.setChannelCode("test");
        t.setChannelName("测试渠道");
        t.setFileType("CSV_GBK");
        t.setSheetStrategy("FIRST");
        t.setQtySemantics("DIRECT");
        t.setKeyField("PRODUCT_ID");
        t.setPersonSource("MANUAL");
        t.setOpType(ExternalImportTemplate.OP_WAREHOUSE_OUT);
        t.setColumnMap(COLUMN_MAP);
        t.setEnabled(true);
        return t;
    }

    private byte[] csv() {
        String csv = """
                商品ID,商品名称,补货数量,补货时间
                P100,冰红茶,8,2026/9/2
                P200,矿泉水,5,2026/9/3
                P100,冰红茶,2,2026/9/20
                """;
        return csv.getBytes(Charset.forName("GB18030"));
    }

    private UserInfo user(long id, String name) {
        UserInfo u = new UserInfo();
        u.setUserId(id);
        u.setName(name);
        return u;
    }

    private Warehouse warehouse(String id, long managerId) {
        Warehouse w = new Warehouse();
        w.setWarehouseId(id);
        w.setManagerUserId(managerId);
        return w;
    }

    // ── 预览：首见建档 + 未映射统计 + 人员归仓 ─────────────────────────────────────────────

    @Test
    void previewRegistersProductsAndCountsUnmapped() {
        when(templateMapper.selectOne(any())).thenReturn(template());
        when(productMapper.selectOne(any())).thenReturn(null); // 全部首见
        when(userInfoMapper.selectList(any())).thenReturn(List.of(user(9L, "李进学")));
        when(warehouseMapper.findFirstActiveByManagerUserId(9L))
                .thenReturn(Optional.of(warehouse("WH-1", 9L)));

        ExternalImportBatchDto dto = service.preview(1L, "test", "202609", "李进学", "x.csv", csv());

        assertEquals("PARSED", dto.status());
        assertEquals(3, dto.rowsTotal());
        assertEquals(0, dto.rowsMapped()); // 商品全部未映射
        assertEquals(2, dto.unmappedProducts().size());
        assertTrue(dto.unresolvedPersons().isEmpty()); // 李进学可归仓
        verify(productMapper, Mockito.times(2)).insert(any(ExternalProduct.class));
    }

    @Test
    void previewManualChannelRequiresPerson() {
        when(templateMapper.selectOne(any())).thenReturn(template());
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.preview(1L, "test", "202609", null, "x.csv", csv()));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
    }

    @Test
    void previewRejectsBadPeriod() {
        when(templateMapper.selectOne(any())).thenReturn(template());
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.preview(1L, "test", "2026-13", null, "x.csv", csv()));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
    }

    // ── 提交：全映射 + 全归仓才放行；聚合为仓×SKU 流水 ─────────────────────────────────────

    @Test
    void commitBlockedWhenProductUnmapped() {
        when(templateMapper.selectOne(any())).thenReturn(template());
        ExternalImportBatch batch = parsedBatch();
        when(batchMapper.selectById(77L)).thenReturn(batch);
        when(productMapper.selectOne(any())).thenReturn(null); // 未建档未映射

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.commit(1L, 77L, new ExternalImportCommitRequest(Map.of())));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(flowMapper, never()).insert(any(ExternalChannelFlow.class));
    }

    @Test
    void commitBlockedWhenPersonUnresolvable() {
        when(templateMapper.selectOne(any())).thenReturn(template());
        ExternalImportBatch batch = parsedBatch();
        when(batchMapper.selectById(77L)).thenReturn(batch);
        when(productMapper.selectOne(any())).thenReturn(existingProduct(11L, "P100"), existingProduct(12L, "P200"));
        when(mappingMapper.selectOne(any())).thenReturn(mapping(11L, "SKU-A"), mapping(12L, "SKU-B"));
        when(userInfoMapper.selectList(any())).thenReturn(List.of()); // 人找不到

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.commit(1L, 77L, new ExternalImportCommitRequest(Map.of())));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
    }

    @Test
    void commitAggregatesByWarehouseAndSku() {
        when(templateMapper.selectOne(any())).thenReturn(template());
        ExternalImportBatch batch = parsedBatch();
        when(batchMapper.selectById(77L)).thenReturn(batch);
        when(productMapper.selectOne(any()))
                .thenReturn(existingProduct(11L, "P100"), existingProduct(12L, "P200"),
                        existingProduct(11L, "P100"));
        when(mappingMapper.selectOne(any()))
                .thenReturn(mapping(11L, "SKU-A"), mapping(12L, "SKU-B"), mapping(11L, "SKU-A"));
        when(userInfoMapper.selectList(any())).thenReturn(List.of(user(9L, "李进学")));
        when(warehouseMapper.findFirstActiveByManagerUserId(9L))
                .thenReturn(Optional.of(warehouse("WH-1", 9L)));
        when(skuCatalogMapper.findById(anyString())).thenReturn(Optional.empty());

        ExternalImportBatchDto dto = service.commit(1L, 77L, new ExternalImportCommitRequest(Map.of()));

        assertEquals("COMMITTED", dto.status());
        assertEquals(2, dto.commitSummary().get("flows")); // P100×2 行聚合成 1 条
        assertEquals(15, dto.commitSummary().get("qtyTotal")); // 8+2+5
        ArgumentCaptor<ExternalChannelFlow> flows = ArgumentCaptor.forClass(ExternalChannelFlow.class);
        verify(flowMapper, Mockito.times(2)).insert(flows.capture());
        ExternalChannelFlow skuA = flows.getAllValues().stream()
                .filter(f -> "SKU-A".equals(f.getSkuId())).findFirst().orElseThrow();
        assertEquals(10, skuA.getQty());
        assertEquals("WH-1", skuA.getWarehouseId());
        assertEquals("WAREHOUSE_OUT", skuA.getOpType());
        assertEquals(java.time.LocalDate.of(2026, 9, 2), skuA.getHappenedMin());
        assertEquals(java.time.LocalDate.of(2026, 9, 20), skuA.getHappenedMax());
        verify(auditService).appendLog(eq(1L), eq("EXTERNAL_IMPORT_COMMIT"),
                eq("EXTERNAL_IMPORT_BATCH"), eq("77"), anyString());
    }

    @Test
    void commitRejectsAlreadyCommitted() {
        when(templateMapper.selectOne(any())).thenReturn(template());
        ExternalImportBatch batch = parsedBatch();
        batch.setStatus(ExternalImportBatch.STATUS_COMMITTED);
        when(batchMapper.selectById(77L)).thenReturn(batch);
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.commit(1L, 77L, new ExternalImportCommitRequest(Map.of())));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
    }

    @Test
    void commitPersonOverrideResolvesWarehouse() {
        when(templateMapper.selectOne(any())).thenReturn(template());
        ExternalImportBatch batch = parsedBatch();
        when(batchMapper.selectById(77L)).thenReturn(batch);
        when(productMapper.selectOne(any()))
                .thenReturn(existingProduct(11L, "P100"), existingProduct(12L, "P200"),
                        existingProduct(11L, "P100"));
        when(mappingMapper.selectOne(any()))
                .thenReturn(mapping(11L, "SKU-A"), mapping(12L, "SKU-B"), mapping(11L, "SKU-A"));
        when(userInfoMapper.selectList(any())).thenReturn(List.of()); // 系统查不到人
        when(warehouseMapper.findById("WH-9")).thenReturn(Optional.of(warehouse("WH-9", 1L)));

        ExternalImportBatchDto dto = service.commit(1L, 77L,
                new ExternalImportCommitRequest(Map.of("李进学", "WH-9")));
        assertEquals("COMMITTED", dto.status());
        verify(flowMapper, Mockito.times(2)).insert(any(ExternalChannelFlow.class));
    }

    // ── 映射治理 ────────────────────────────────────────────────────────────────────────────

    @Test
    void bindMappingValidatesSkuExists() {
        when(productMapper.selectById(11L)).thenReturn(existingProduct(11L, "P100"));
        when(skuCatalogMapper.findById("NOPE")).thenReturn(Optional.empty());
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.bindMapping(1L, 11L, "NOPE", null));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
    }

    @Test
    void bindAndUnbindMappingRoundTrip() {
        ExternalProduct product = existingProduct(11L, "P100");
        when(productMapper.selectById(11L)).thenReturn(product);
        SkuCatalog sku = new SkuCatalog();
        sku.setSkuId("SKU-A");
        sku.setSkuName("冰红茶 500ml");
        when(skuCatalogMapper.findById("SKU-A")).thenReturn(Optional.of(sku));
        ArgumentCaptor<ExternalProductMapping> inserted = ArgumentCaptor.forClass(ExternalProductMapping.class);
        when(mappingMapper.selectOne(any())).thenReturn(null);

        ExternalProductDto bound = service.bindMapping(1L, 11L, "SKU-A", "名称精确匹配");
        verify(mappingMapper).insert(inserted.capture());
        assertEquals("SKU-A", inserted.getValue().getSkuId());

        Mockito.reset(mappingMapper);
        when(mappingMapper.selectOne(any())).thenReturn(inserted.getValue());
        when(productMapper.selectById(11L)).thenReturn(product);
        ExternalProductDto relisted = service.unbindMapping(1L, 11L);
        assertEquals("SKU-A", relisted.mappedSkuId());
        verify(mappingMapper).deleteById(inserted.getValue().getMappingId());
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private ExternalImportBatch parsedBatch() {
        try {
            List<Map<String, Object>> rows = new java.util.ArrayList<>();
            rows.add(personRow("李进学", "P100", "冰红茶", 8, "2026-09-02", 2));
            rows.add(personRow("李进学", "P200", "矿泉水", 5, "2026-09-03", 3));
            rows.add(personRow("李进学", "P100", "冰红茶", 2, "2026-09-20", 4));
            String rowsJson = new ObjectMapper().writeValueAsString(rows);
            ExternalImportBatch b = new ExternalImportBatch();
            b.setBatchId(77L);
            b.setChannelCode("test");
            b.setPeriodLabel("202609");
            b.setFileName("x.csv");
            b.setStatus(ExternalImportBatch.STATUS_PARSED);
            b.setRowsTotal(3);
            b.setParsedRows(rowsJson);
            return b;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> personRow(String person, String key, String name, int qty,
                                          String happenedAt, int srcLine) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("person", person);
        row.put("extKey", key);
        row.put("extName", name);
        row.put("qty", qty);
        row.put("unitPriceCents", null);
        row.put("happenedAt", happenedAt);
        row.put("srcLine", srcLine);
        return row;
    }

    private ExternalProduct existingProduct(long id, String key) {
        ExternalProduct p = new ExternalProduct();
        p.setExternalProductId(id);
        p.setChannelCode("test");
        p.setExternalKey(key);
        p.setExternalName(key);
        p.setTimesSeen(1);
        return p;
    }

    private ExternalProductMapping mapping(long externalProductId, String skuId) {
        ExternalProductMapping m = new ExternalProductMapping();
        m.setMappingId(externalProductId * 10);
        m.setExternalProductId(externalProductId);
        m.setSkuId(skuId);
        return m;
    }
}
