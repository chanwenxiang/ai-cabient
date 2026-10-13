package com.aicabinet.trade.service;

import com.aicabinet.common.dto.ExternalChannelFlowDto;
import com.aicabinet.common.dto.ExternalImportBatchDto;
import com.aicabinet.common.dto.ExternalImportCommitRequest;
import com.aicabinet.common.dto.ExternalImportTemplateDto;
import com.aicabinet.common.dto.ExternalParsedRowDto;
import com.aicabinet.common.dto.ExternalProductDto;
import com.aicabinet.common.dto.ExternalUnmappedProductDto;
import com.aicabinet.common.dto.PageResult;
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
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 外部渠道对账导入服务（CB-036，V347；设计定稿见 {@code docs/SATELLITE_WAREHOUSE.md} 与任务台账）。
 *
 * <p><b>两步法</b>：① preview 上传解析（模板驱动）→ 落批次 PARSED + 外部商品首见建档
 * → ② ops 在映射治理页把外部商品绑到本系统 SKU、人员覆盖归仓后 commit →
 * 聚合为 {@link ExternalChannelFlow}（仓×SKU×渠道×操作类型），月结（账期制）按
 * {@code period_label + warehouse_id} 取数。COMMITTED 批次锁死不可删。
 *
 * <p>🔴 外部商品不进我方商品目录（2026-10-13 拍板）；🔴 本表不回写
 * {@code warehouse_movement}（业务时间在往期，混入实时流水会污染实时库存口径）。
 */
@Service
public class ExternalChannelImportService {

    private final ExternalImportTemplateMapper templateMapper;
    private final ExternalProductMapper productMapper;
    private final ExternalProductMappingMapper mappingMapper;
    private final ExternalImportBatchMapper batchMapper;
    private final ExternalChannelFlowMapper flowMapper;
    private final SkuCatalogMapper skuCatalogMapper;
    private final UserInfoMapper userInfoMapper;
    private final WarehouseMapper warehouseMapper;
    private final AdminAuditService auditService;
    private final ExternalChannelFileParser fileParser;
    private final ObjectMapper objectMapper;

    public ExternalChannelImportService(ExternalImportTemplateMapper templateMapper,
                                        ExternalProductMapper productMapper,
                                        ExternalProductMappingMapper mappingMapper,
                                        ExternalImportBatchMapper batchMapper,
                                        ExternalChannelFlowMapper flowMapper,
                                        SkuCatalogMapper skuCatalogMapper,
                                        UserInfoMapper userInfoMapper,
                                        WarehouseMapper warehouseMapper,
                                        AdminAuditService auditService,
                                        ExternalChannelFileParser fileParser,
                                        ObjectMapper objectMapper) {
        this.templateMapper = templateMapper;
        this.productMapper = productMapper;
        this.mappingMapper = mappingMapper;
        this.batchMapper = batchMapper;
        this.flowMapper = flowMapper;
        this.skuCatalogMapper = skuCatalogMapper;
        this.userInfoMapper = userInfoMapper;
        this.warehouseMapper = warehouseMapper;
        this.auditService = auditService;
        this.fileParser = fileParser;
        this.objectMapper = objectMapper;
    }

    // ── 模板 ────────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ExternalImportTemplateDto> listTemplates() {
        return templateMapper.selectList(Wrappers.<ExternalImportTemplate>lambdaQuery()
                        .orderByAsc(ExternalImportTemplate::getTemplateId))
                .stream().map(this::toTemplateDto).toList();
    }

    // ── ① 预览（上传解析）────────────────────────────────────────────────────────────────────

    /**
     * 上传解析并落 PARSED 批次。外部商品首见建档（timesSeen 累计）；
     * 行不立即落任何流水——映射与归仓在治理页完成后 commit。
     */
    @Transactional
    public ExternalImportBatchDto preview(Long operatorId, String channelCode, String periodLabel,
                                          String personOverride, String fileName, byte[] content) {
        ExternalImportTemplate template = requireTemplate(channelCode);
        String period = normalizePeriod(periodLabel);
        if (content == null || content.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件内容为空");
        }
        boolean manualPerson = ExternalImportTemplate.PERSON_MANUAL.equals(template.getPersonSource());
        if (manualPerson && (personOverride == null || personOverride.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "该渠道人员来源=人工指定，请填写线长姓名");
        }
        ExternalChannelFileParser.ParseResult parsed =
                fileParser.parse(content, fileName, template, manualPerson ? personOverride.trim() : null);
        if (parsed.rows().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "解析到 0 条有效数据行；问题明细：" + String.join("；", firstN(parsed.errors(), 5)));
        }

        ExternalImportBatch batch = new ExternalImportBatch();
        batch.setChannelCode(template.getChannelCode());
        batch.setPeriodLabel(period);
        batch.setFileName(fileName == null ? "upload" : fileName);
        batch.setPersonOverride(manualPerson ? personOverride.trim() : null);
        batch.setStatus(ExternalImportBatch.STATUS_PARSED);
        batch.setRowsTotal(parsed.rows().size());
        batch.setParsedRows(toJson(toRowDtos(parsed.rows())));
        batch.setUploadedBy(operatorId);
        batch.setUploadedAt(Instant.now());

        Map<String, ExternalUnmappedProductDto> unmapped = refreshExternalProducts(template, parsed.rows());
        resolveMappingState(batch, template, parsed.rows(), unmapped, Map.of());

        batchMapper.insert(batch);
        return toBatchDto(batch, template);
    }

    /** 外部商品首见建档 / 再见累计；返回本次出现的外部商品键 → 未映射概要（已映射的键不出现）。 */
    private Map<String, ExternalUnmappedProductDto> refreshExternalProducts(
            ExternalImportTemplate template, List<ExternalChannelFileParser.ParsedRow> rows) {
        Map<String, ExternalChannelFileParser.ParsedRow> firstByKey = new LinkedHashMap<>();
        for (ExternalChannelFileParser.ParsedRow row : rows) {
            firstByKey.putIfAbsent(row.extKey(), row);
        }
        Map<String, ExternalUnmappedProductDto> unmapped = new LinkedHashMap<>();
        for (Map.Entry<String, ExternalChannelFileParser.ParsedRow> e : firstByKey.entrySet()) {
            ExternalChannelFileParser.ParsedRow row = e.getValue();
            ExternalProduct product = productMapper.selectOne(Wrappers.<ExternalProduct>lambdaQuery()
                    .eq(ExternalProduct::getChannelCode, template.getChannelCode())
                    .eq(ExternalProduct::getExternalKey, row.extKey()));
            Long productId;
            if (product == null) {
                product = new ExternalProduct();
                product.setChannelCode(template.getChannelCode());
                product.setExternalKey(row.extKey());
                product.setExternalName(row.extName());
                product.setBrand(row.brand());
                product.setSpec(row.spec());
                product.setUnitPriceCents(row.unitPriceCents());
                product.setFirstSeenAt(Instant.now());
                product.setLastSeenAt(Instant.now());
                product.setTimesSeen(1);
                productMapper.insert(product);
                productId = product.getExternalProductId();
            } else {
                product.setLastSeenAt(Instant.now());
                product.setTimesSeen(product.getTimesSeen() + 1);
                if (row.extName() != null && !row.extName().isBlank()) {
                    product.setExternalName(row.extName());
                }
                if (row.brand() != null) {
                    product.setBrand(row.brand());
                }
                if (row.spec() != null) {
                    product.setSpec(row.spec());
                }
                if (row.unitPriceCents() != null) {
                    product.setUnitPriceCents(row.unitPriceCents());
                }
                productMapper.updateById(product);
                productId = product.getExternalProductId();
            }
            if (findMapping(productId).isEmpty()) {
                unmapped.put(row.extKey(), new ExternalUnmappedProductDto(
                        productId, row.extKey(), row.extName(), product.getTimesSeen(), suggestSku(row.extName())));
            }
        }
        return unmapped;
    }

    /** 名称精确匹配本系统 SKU 的建议值（治理页一键采纳；不自动绑定）。 */
    private String suggestSku(String extName) {
        if (extName == null || extName.isBlank()) {
            return null;
        }
        List<SkuCatalog> hits = skuCatalogMapper.selectList(Wrappers.<SkuCatalog>lambdaQuery()
                .eq(SkuCatalog::getSkuName, extName.trim()).last("LIMIT 2"));
        return hits.size() == 1 ? hits.get(0).getSkuId() : null;
    }

    private Optional<ExternalProductMapping> findMapping(Long externalProductId) {
        return Optional.ofNullable(mappingMapper.selectOne(Wrappers.<ExternalProductMapping>lambdaQuery()
                .eq(ExternalProductMapping::getExternalProductId, externalProductId)));
    }

    /** 行级映射/归仓状态：写入批次统计与未映射/未归仓清单（preview 时调用，unmapped 为当次权威）。 */
    private void resolveMappingState(ExternalImportBatch batch, ExternalImportTemplate template,
                                     List<ExternalChannelFileParser.ParsedRow> rows,
                                     Map<String, ExternalUnmappedProductDto> unmapped,
                                     Map<String, String> personOverrides) {
        int mapped = 0;
        List<ExternalUnmappedProductDto> unmappedList = new ArrayList<>(unmapped.values());
        List<String> unresolvedPersons = new ArrayList<>();
        for (var row : rows) {
            boolean productOk = !unmapped.containsKey(row.extKey());
            String person = row.person();
            boolean personOk = resolveWarehouse(person, personOverrides).isPresent();
            if (person != null && !personOk && !unresolvedPersons.contains(person)) {
                unresolvedPersons.add(person);
            }
            if (productOk && personOk) {
                mapped++;
            }
        }
        batch.setRowsMapped(mapped);
        batch.setRowsUnmapped(batch.getRowsTotal() - mapped);
        batch.setUnmappedProducts(toJson(unmappedList));
        batch.setUnresolvedPersons(toJson(unresolvedPersons));
    }

    /** commit 前置校验用：按键查当前映射状态（预览后可能又绑了新映射）。 */
    private boolean hasMappingNow(String channelCode, String extKey) {
        ExternalProduct product = productMapper.selectOne(Wrappers.<ExternalProduct>lambdaQuery()
                .eq(ExternalProduct::getChannelCode, channelCode)
                .eq(ExternalProduct::getExternalKey, extKey));
        return product != null && findMapping(product.getExternalProductId()).isPresent();
    }

    /** 人员→仓：精确姓名 → user_info → manager_user_id 仓；覆盖表优先。 */
    private Optional<String> resolveWarehouse(String person, Map<String, String> overrides) {
        if (person == null || person.isBlank()) {
            return Optional.empty();
        }
        String override = overrides.get(person);
        if (override != null && !override.isBlank()) {
            return warehouseMapper.findById(override.trim()).isPresent()
                    ? Optional.of(override.trim()) : Optional.empty();
        }
        Optional<UserInfo> user = userInfoMapper.selectList(Wrappers.<UserInfo>lambdaQuery()
                        .eq(UserInfo::getName, person).last("LIMIT 2"))
                .stream().findFirst();
        if (user.isEmpty()) {
            return Optional.empty();
        }
        return warehouseMapper.findFirstActiveByManagerUserId(user.get().getUserId())
                .map(Warehouse::getWarehouseId);
    }

    // ── ② 确认提交（聚合入流水）──────────────────────────────────────────────────────────────

    @Transactional
    public ExternalImportBatchDto commit(Long operatorId, Long batchId, ExternalImportCommitRequest request) {
        ExternalImportBatch batch = requireBatch(batchId);
        if (!ExternalImportBatch.STATUS_PARSED.equals(batch.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该批次已提交，不可重复提交");
        }
        ExternalImportTemplate template = requireTemplate(batch.getChannelCode());
        Map<String, String> overrides = request == null || request.personWarehouseOverrides() == null
                ? Map.of() : request.personWarehouseOverrides();

        List<ExternalChannelFileParser.ParsedRow> rows = fromRowJson(batch.getParsedRows());
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "批次解析行丢失，请重新上传");
        }

        // 提交闸门：全部行商品已映射 + 人员可归仓（含覆盖）；顺带解析行→SKU
        List<String> blockers = new ArrayList<>();
        Map<String, String> skuIdByExtKey = new HashMap<>();
        for (var row : rows) {
            ExternalProduct product = productMapper.selectOne(Wrappers.<ExternalProduct>lambdaQuery()
                    .eq(ExternalProduct::getChannelCode, batch.getChannelCode())
                    .eq(ExternalProduct::getExternalKey, row.extKey()));
            Optional<ExternalProductMapping> mapping = product == null
                    ? Optional.empty() : findMapping(product.getExternalProductId());
            if (mapping.isEmpty()) {
                blockers.add("商品「" + row.extName() + "」未映射");
                continue;
            }
            skuIdByExtKey.put(row.extKey(), mapping.get().getSkuId());
            if (resolveWarehouse(row.person(), overrides).isEmpty()) {
                blockers.add("人员「" + row.person() + "」无法归仓");
            }
        }
        if (!blockers.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "存在 " + blockers.size() + " 个阻断项：" + String.join("；", firstN(blockers, 8)));
        }

        // 聚合：仓×SKU（op_type 模板级）
        record Agg(String warehouseId, String skuId, int qty, Long price, LocalDate min, LocalDate max,
                   int lines, String person) {
        }
        Map<String, Agg> aggs = new LinkedHashMap<>();
        Map<String, String> skuNameByKey = new HashMap<>();
        for (var row : rows) {
            String warehouseId = resolveWarehouse(row.person(), overrides).orElseThrow();
            String skuId = skuIdByExtKey.get(row.extKey());
            String key = warehouseId + "\u0001" + skuId;
            Agg a = aggs.get(key);
            boolean priceConflict = a != null && a.price != null && row.unitPriceCents() != null
                    && !a.price.equals(row.unitPriceCents());
            if (a == null) {
                aggs.put(key, new Agg(warehouseId, skuId, row.qty(), row.unitPriceCents(),
                        row.happenedAt(), row.happenedAt(), 1, row.person()));
                skuNameByKey.put(row.extKey(), row.extName());
            } else {
                aggs.put(key, new Agg(warehouseId, skuId, a.qty() + row.qty(),
                        priceConflict ? null : (row.unitPriceCents() != null ? row.unitPriceCents() : a.price()),
                        minDate(a.min(), row.happenedAt()), maxDate(a.max(), row.happenedAt()),
                        a.lines() + 1, a.person()));
            }
        }

        String summary = "渠道导入：" + template.getChannelName() + " " + batch.getPeriodLabel()
                + "；来源文件 " + batch.getFileName();
        int qtyTotal = 0;
        List<String> warehouses = new ArrayList<>();
        for (Map.Entry<String, Agg> e : aggs.entrySet()) {
            Agg a = e.getValue();
            ExternalChannelFlow flow = new ExternalChannelFlow();
            flow.setBatchId(batch.getBatchId());
            flow.setChannelCode(batch.getChannelCode());
            flow.setPeriodLabel(batch.getPeriodLabel());
            flow.setWarehouseId(a.warehouseId());
            flow.setPersonName(a.person());
            flow.setSkuId(a.skuId());
            flow.setOpType(template.getOpType());
            flow.setQty(a.qty());
            flow.setUnitPriceCents(a.price());
            flow.setHappenedMin(a.min());
            flow.setHappenedMax(a.max());
            flow.setRowCount(a.lines());
            flow.setRemark(summary);
            flow.setCreatedAt(Instant.now());
            flowMapper.insert(flow);
            qtyTotal += a.qty();
            if (!warehouses.contains(a.warehouseId())) {
                warehouses.add(a.warehouseId());
            }
        }

        batch.setStatus(ExternalImportBatch.STATUS_COMMITTED);
        batch.setCommittedAt(Instant.now());
        Map<String, Object> commitSummary = new LinkedHashMap<>();
        commitSummary.put("flows", aggs.size());
        commitSummary.put("qtyTotal", qtyTotal);
        commitSummary.put("warehouses", warehouses);
        batch.setCommitSummary(toJson(commitSummary));
        batchMapper.updateById(batch);

        auditService.appendLog(operatorId, "EXTERNAL_IMPORT_COMMIT", "EXTERNAL_IMPORT_BATCH",
                String.valueOf(batch.getBatchId()),
                summary + "；聚合 " + aggs.size() + " 条流水，共 " + qtyTotal + " 件");
        return toBatchDto(batch, template);
    }

    // ── 批次查询/删除 ────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResult<ExternalImportBatchDto> listBatches(String channelCode, String periodLabel,
                                                          int page, int size) {
        Page<ExternalImportBatch> p = batchMapper.selectPage(
                new Page<>(Math.max(page, 0) + 1L, Math.min(Math.max(size, 1), 100)),
                Wrappers.<ExternalImportBatch>lambdaQuery()
                        .eq(channelCode != null && !channelCode.isBlank(),
                                ExternalImportBatch::getChannelCode, channelCode)
                        .eq(periodLabel != null && !periodLabel.isBlank(),
                                ExternalImportBatch::getPeriodLabel, periodLabel)
                        .orderByDesc(ExternalImportBatch::getBatchId));
        List<ExternalImportBatchDto> items = p.getRecords().stream()
                .map(b -> toBatchDto(b, null)).toList();
        return new PageResult<>(items, page, size, p.getTotal());
    }

    @Transactional(readOnly = true)
    public ExternalImportBatchDto getBatch(Long batchId) {
        ExternalImportBatch batch = requireBatch(batchId);
        return toBatchDto(batch, null);
    }

    @Transactional
    public void deleteBatch(Long operatorId, Long batchId) {
        ExternalImportBatch batch = requireBatch(batchId);
        if (!ExternalImportBatch.STATUS_PARSED.equals(batch.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已提交批次不可删除");
        }
        batchMapper.deleteById(batchId);
        auditService.appendLog(operatorId, "EXTERNAL_IMPORT_BATCH_DELETE", "EXTERNAL_IMPORT_BATCH",
                String.valueOf(batchId), batch.getChannelCode() + " " + batch.getPeriodLabel()
                        + " " + batch.getFileName());
    }

    // ── 外部商品与映射治理 ───────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResult<ExternalProductDto> listProducts(String channelCode, Boolean mapped,
                                                       String keyword, int page, int size) {
        Page<ExternalProduct> p = productMapper.selectPage(
                new Page<>(Math.max(page, 0) + 1L, Math.min(Math.max(size, 1), 200)),
                Wrappers.<ExternalProduct>lambdaQuery()
                        .eq(channelCode != null && !channelCode.isBlank(),
                                ExternalProduct::getChannelCode, channelCode)
                        .and(keyword != null && !keyword.isBlank(), w -> w
                                .like(ExternalProduct::getExternalName, keyword.trim())
                                .or().like(ExternalProduct::getExternalKey, keyword.trim()))
                        .orderByDesc(ExternalProduct::getLastSeenAt));
        List<ExternalProductDto> items = p.getRecords().stream()
                .map(this::toProductDto)
                .filter(d -> mapped == null || (mapped && d.mappedSkuId() != null)
                        || (!mapped && d.mappedSkuId() == null))
                .toList();
        return new PageResult<>(items, page, size, p.getTotal());
    }

    @Transactional
    public ExternalProductDto bindMapping(Long operatorId, Long externalProductId,
                                          String skuId, String note) {
        ExternalProduct product = requireProduct(externalProductId);
        if (skuId == null || skuId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择本系统 SKU");
        }
        SkuCatalog sku = skuCatalogMapper.findById(skuId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "SKU 不存在：" + skuId));
        ExternalProductMapping mapping = findMapping(externalProductId).orElseGet(ExternalProductMapping::new);
        boolean isNew = mapping.getMappingId() == null;
        mapping.setExternalProductId(externalProductId);
        mapping.setSkuId(sku.getSkuId());
        mapping.setBoundBy(operatorId);
        mapping.setBoundAt(Instant.now());
        mapping.setNote(note == null || note.isBlank() ? null : note.trim());
        if (isNew) {
            mappingMapper.insert(mapping);
        } else {
            mappingMapper.updateById(mapping);
        }
        auditService.appendLog(operatorId, isNew ? "EXTERNAL_MAPPING_BIND" : "EXTERNAL_MAPPING_REBIND",
                "EXTERNAL_PRODUCT", String.valueOf(externalProductId),
                product.getExternalName() + " → " + sku.getSkuId());
        return toProductDto(product);
    }

    @Transactional
    public ExternalProductDto unbindMapping(Long operatorId, Long externalProductId) {
        ExternalProduct product = requireProduct(externalProductId);
        ExternalProductMapping mapping = findMapping(externalProductId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "该商品未绑定映射"));
        mappingMapper.deleteById(mapping.getMappingId());
        auditService.appendLog(operatorId, "EXTERNAL_MAPPING_UNBIND", "EXTERNAL_PRODUCT",
                String.valueOf(externalProductId), product.getExternalName());
        return toProductDto(product);
    }

    // ── 渠道流水（月结取数源预览）────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ExternalChannelFlowDto> listFlows(String periodLabel, String warehouseId, String opType) {
        return flowMapper.selectList(Wrappers.<ExternalChannelFlow>lambdaQuery()
                        .eq(periodLabel != null && !periodLabel.isBlank(),
                                ExternalChannelFlow::getPeriodLabel, periodLabel)
                        .eq(warehouseId != null && !warehouseId.isBlank(),
                                ExternalChannelFlow::getWarehouseId, warehouseId)
                        .eq(opType != null && !opType.isBlank(), ExternalChannelFlow::getOpType, opType)
                        .orderByDesc(ExternalChannelFlow::getFlowId))
                .stream().map(this::toFlowDto).toList();
    }

    // ── 组装 ────────────────────────────────────────────────────────────────────────────────

    private ExternalImportTemplateDto toTemplateDto(ExternalImportTemplate t) {
        return new ExternalImportTemplateDto(t.getTemplateId(), t.getChannelCode(), t.getChannelName(),
                t.getFileType(), t.getSheetStrategy(), t.getQtySemantics(), t.getKeyField(),
                t.getPersonSource(), t.getOpType(), fromJsonMap(t.getColumnMap()),
                t.getDefaultWarehouseId(), t.isEnabled(), t.getRemark(), t.getUpdatedAt());
    }

    private ExternalImportBatchDto toBatchDto(ExternalImportBatch b, ExternalImportTemplate template) {
        String channelName = template != null ? template.getChannelName() : b.getChannelCode();
        if (template == null) {
            ExternalImportTemplate t = templateMapper.selectOne(Wrappers.<ExternalImportTemplate>lambdaQuery()
                    .eq(ExternalImportTemplate::getChannelCode, b.getChannelCode()));
            channelName = t != null ? t.getChannelName() : b.getChannelCode();
        }
        List<ExternalUnmappedProductDto> unmapped = b.getUnmappedProducts() == null ? List.of()
                : fromJson(b.getUnmappedProducts(), new TypeReference<List<ExternalUnmappedProductDto>>() { });
        List<String> unresolved = b.getUnresolvedPersons() == null ? List.of()
                : fromJson(b.getUnresolvedPersons(), new TypeReference<List<String>>() { });
        List<ExternalParsedRowDto> rows = b.getParsedRows() == null ? List.of()
                : fromJson(b.getParsedRows(), new TypeReference<List<ExternalParsedRowDto>>() { });
        Map<String, Object> summary = b.getCommitSummary() == null ? null
                : fromJson(b.getCommitSummary(), new TypeReference<Map<String, Object>>() { });
        return new ExternalImportBatchDto(b.getBatchId(), b.getChannelCode(), channelName,
                b.getPeriodLabel(), b.getFileName(), b.getPersonOverride(), b.getStatus(),
                b.getRowsTotal(), b.getRowsMapped(), b.getRowsUnmapped(), unmapped, unresolved, rows,
                summary, operatorName(b.getUploadedBy()), b.getUploadedAt(), b.getCommittedAt());
    }

    private String operatorName(Long userId) {
        if (userId == null) {
            return null;
        }
        return userInfoMapper.findById(userId)
                .map(u -> u.getName() != null && !u.getName().isBlank()
                        ? u.getName() : u.getPhoneNumber()).orElse("账号 " + userId);
    }

    private ExternalProductDto toProductDto(ExternalProduct p) {
        Optional<ExternalProductMapping> mapping = findMapping(p.getExternalProductId());
        String skuId = mapping.map(ExternalProductMapping::getSkuId).orElse(null);
        String skuName = null;
        if (skuId != null) {
            skuName = skuCatalogMapper.findById(skuId).map(SkuCatalog::getSkuName).orElse(skuId);
        }
        return new ExternalProductDto(p.getExternalProductId(), p.getChannelCode(), p.getExternalKey(),
                p.getExternalName(), p.getBrand(), p.getSpec(), p.getUnitPriceCents(), p.getTimesSeen(),
                p.getLastSeenAt(), skuId, skuName);
    }

    private ExternalChannelFlowDto toFlowDto(ExternalChannelFlow f) {
        return new ExternalChannelFlowDto(f.getFlowId(), f.getBatchId(), f.getChannelCode(),
                f.getPeriodLabel(), f.getWarehouseId(), f.getPersonName(), f.getSkuId(), f.getOpType(),
                f.getQty(), f.getUnitPriceCents(), f.getHappenedMin(), f.getHappenedMax(),
                f.getRowCount(), f.getRemark(), f.getCreatedAt());
    }

    private List<ExternalParsedRowDto> toRowDtos(List<ExternalChannelFileParser.ParsedRow> rows) {
        return rows.stream()
                .map(r -> new ExternalParsedRowDto(r.person(), r.extKey(), r.extName(), r.qty(),
                        r.unitPriceCents(), r.happenedAt() == null ? null : r.happenedAt().toString(),
                        r.srcLine()))
                .toList();
    }

    private List<ExternalChannelFileParser.ParsedRow> fromRowJson(String json) {
        List<ExternalParsedRowDto> dtos = fromJson(json, new TypeReference<List<ExternalParsedRowDto>>() { });
        return dtos.stream()
                .map(d -> new ExternalChannelFileParser.ParsedRow(d.person(), d.extKey(), d.extName(),
                        null, null, d.unitPriceCents(), d.qty(),
                        d.happenedAt() == null ? null : LocalDate.parse(d.happenedAt()), d.srcLine()))
                .toList();
    }

    private ExternalImportTemplate requireTemplate(String channelCode) {
        ExternalImportTemplate t = templateMapper.selectOne(Wrappers.<ExternalImportTemplate>lambdaQuery()
                .eq(ExternalImportTemplate::getChannelCode, channelCode));
        if (t == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知渠道：" + channelCode);
        }
        if (!t.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "渠道「" + t.getChannelName() + "」已停用");
        }
        return t;
    }

    private ExternalImportBatch requireBatch(Long batchId) {
        ExternalImportBatch b = batchMapper.selectById(batchId);
        if (b == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "批次不存在");
        }
        return b;
    }

    private ExternalProduct requireProduct(Long externalProductId) {
        ExternalProduct p = productMapper.selectById(externalProductId);
        if (p == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "外部商品不存在");
        }
        return p;
    }

    /** 账期标签=结束月（yyyyMM）；容错 yyyy-MM。26~25 账期口径由月结侧解释。 */
    static String normalizePeriod(String raw) {
        if (raw == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写账期（所属月份，如 202609）");
        }
        String v = raw.trim().replace("-", "");
        if (!v.matches("20\\d{2}(0[1-9]|1[0-2])")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "账期格式应为 yyyyMM（如 202609），收到：" + raw);
        }
        return v;
    }

    private static LocalDate minDate(LocalDate a, LocalDate b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isBefore(b) ? a : b;
    }

    private static LocalDate maxDate(LocalDate a, LocalDate b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    private static List<String> firstN(List<String> list, int n) {
        return list.size() <= n ? list : list.subList(0, n);
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private <T> T fromJson(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 反序列化失败", e);
        }
    }

    private Map<String, String> fromJsonMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, String>>() { });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private List<String> fromJsonList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }
}
