package com.aicabinet.trade.service;

import com.aicabinet.trade.client.JiangyiGatherClient;
import com.aicabinet.trade.client.JiangyiGatewayClient;
import com.aicabinet.trade.domain.JiangyiClassMapping;
import com.aicabinet.trade.domain.JiangyiDevice;
import com.aicabinet.trade.domain.JiangyiModelDeployment;
import com.aicabinet.trade.dto.JiangyiGatherDtos.JiangyiModelFile;
import com.aicabinet.trade.dto.JiangyiGatherDtos.JiangyiTextNameSku;
import com.aicabinet.trade.dto.JiangyiGatherDtos.TrainedProduct;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.mapper.JiangyiClassMappingMapper;
import com.aicabinet.trade.mapper.JiangyiDeviceMapper;
import com.aicabinet.trade.mapper.JiangyiModelDeploymentMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.Locale;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 将邑模型同步编排（CB-023 二期，范围 A）：
 * 预览（模型+classes 解析对照）→ 映射预生成（MODEL_SYNC/DISABLED，人工确认激活）→
 * WS updateModel 下发（机型校验+幂等）→ 设备 downloadModelNotify 回执回填。
 *
 * <p>语义铁律：预生成行落库即 DISABLED 不自动生效（防误映射直接扣款，与 CB-022
 * 「金额平台自持」同源）；MODEL_SYNC 覆盖既有行时保留 sku_id（upsert 的 MP NOT_NULL
 * 策略保证 null 不进 SET 子句）；industrial_control_model 存将邑原值（"76"/"88"）
 * 不解释语义，下发校验用字符串相等（CB-023 台账结论 4）。</p>
 */
@Service
public class JiangyiModelSyncService {

    private static final Logger log = LoggerFactory.getLogger(JiangyiModelSyncService.class);

    private final JiangyiGatherClient jiangyiGatherClient;
    private final JiangyiDeviceMapper jiangyiDeviceMapper;
    private final JiangyiClassMappingMapper jiangyiClassMappingMapper;
    private final JiangyiModelDeploymentMapper deploymentMapper;
    private final JiangyiGatewayClient jiangyiGatewayClient;
    private final SkuCatalogMapper skuCatalogMapper;
    /** 拉取 classes.txt（将邑 OSS 外链，不带内部凭据）。 */
    private final RestClient publicHttp = RestClient.create();

    public JiangyiModelSyncService(JiangyiGatherClient jiangyiGatherClient,
                                   JiangyiDeviceMapper jiangyiDeviceMapper,
                                   JiangyiClassMappingMapper jiangyiClassMappingMapper,
                                   JiangyiModelDeploymentMapper deploymentMapper,
                                   JiangyiGatewayClient jiangyiGatewayClient,
                                   SkuCatalogMapper skuCatalogMapper) {
        this.jiangyiGatherClient = jiangyiGatherClient;
        this.jiangyiDeviceMapper = jiangyiDeviceMapper;
        this.jiangyiClassMappingMapper = jiangyiClassMappingMapper;
        this.deploymentMapper = deploymentMapper;
        this.jiangyiGatewayClient = jiangyiGatewayClient;
        this.skuCatalogMapper = skuCatalogMapper;
    }

    // ---------- 预览 ----------

    /** 模型列表 + classes 解析对照（classId↔textName）+ 与已学习商品 textName 的交集警示。 */
    public List<ModelPreview> previewModels(int classIdBase) {
        List<JiangyiModelFile> models = jiangyiGatherClient.modelFiles();
        Set<String> trainedTextNames = new HashSet<>();
        for (TrainedProduct p : jiangyiGatherClient.trainedProducts()) {
            if (p.textName() != null) {
                trainedTextNames.add(p.textName());
            }
        }
        List<ModelPreview> out = new ArrayList<>();
        for (JiangyiModelFile model : models) {
            List<JiangyiClassesParser.ParsedClassRow> rows;
            String classesVersion;
            try {
                JiangyiClassesParser.ParsedClasses parsed = parseClasses(model, classIdBase);
                rows = parsed.rows();
                classesVersion = parsed.classesVersion();
            } catch (JiangyiClassesParser.ClassesParseRejectException
                    | ResponseStatusException
                    | RestClientException e) {
                // 预览不因单个模型取不到 classes.txt 而整体失败：白名单拒/地址为空/OSS 不可达
                // 一律降级成该行原因（后台按行显示「解析被拒」），其余模型照常可预览
                out.add(new ModelPreview(model.modelName(), model.industrialControlModel(),
                        model.quantity(), model.modelTextUrl(), null, classesVersionOf(model),
                        List.of(), previewRejectReason(e), 0));
                continue;
            }
            long matched = rows.stream().filter(r -> trainedTextNames.contains(r.textName())).count();
            out.add(new ModelPreview(model.modelName(), model.industrialControlModel(),
                    model.quantity(), model.modelTextUrl(), classesVersion, classesVersion,
                    rows, null, trainedTextNames.isEmpty() ? 0 : (int) (matched * 100 / rows.size())));
        }
        return out;
    }

    // ---------- 厂商对照解码（§5.5.3，只读） ----------

    /**
     * 把 classes.txt 的每一行翻成「厂商标准商品 + 本平台候选 SKU」，供运营挂接对照时看人话。
     *
     * <p>🔴 <b>只读，一行都不写</b>（2026-10-22 拍板）：§5.5.3 回的是厂商标准商品，不是我方 SKU——
     * 按它自动写 sku_id 等于把「厂商认为这是什么商品」当成「这个柜按哪个价结算的商品」，
     * 挂错即错扣款。条码命中的候选只用于显示，挂接仍由人工在识别映射页做。</p>
     *
     * <p>合并键用 textName 而不是 classId：厂商 classId 与 classes.txt 行下标同基（台账 H-15
     * 实测 0-based），但本页留着 classIdBase 旋钮——按 classId 合并时旋钮拨到 1 会整体错行且
     * 看不出来。厂商 classId 原样回显（vendorClassId），与 classId 不一致即肉眼可见。</p>
     *
     * @param quantity 模型声明的商品行数；0 = 跳过行数校验（只想翻译看看时）
     */
    public ClassDecodeView decodeVendorSkuByClasses(String deviceId, String textUrl,
                                                   int quantity, int classIdBase) {
        JiangyiClassesParser.ParsedClasses parsed = parseClasses(textUrl, quantity, classIdBase);
        List<JiangyiTextNameSku> vendor =
                jiangyiGatherClient.stdSkuByTextNames(requireSafeModelTextUrl(textUrl));
        Map<String, JiangyiTextNameSku> byTextName = new LinkedHashMap<>();
        Set<String> barcodes = new HashSet<>();
        for (JiangyiTextNameSku s : vendor) {
            if (s.textName() == null) {
                continue;
            }
            byTextName.putIfAbsent(s.textName(), s);
            if (s.barCode() != null && !s.barCode().isBlank()) {
                barcodes.add(s.barCode().trim());
            }
        }

        Map<String, List<SkuCandidate>> candidatesByBarcode = new HashMap<>();
        Map<String, String> skuNames = new HashMap<>();
        for (SkuCatalog sku : skuCatalogMapper.findByBarcodes(barcodes)) {
            if (sku.getBarcode() == null || sku.getBarcode().isBlank()) {
                continue;
            }
            String code = sku.getBarcode().trim();
            candidatesByBarcode.computeIfAbsent(code, k -> new ArrayList<>())
                    .add(new SkuCandidate(sku.getSkuId(), sku.getSkuName(), code));
            skuNames.put(sku.getSkuId(), sku.getSkuName());
        }

        Map<Integer, JiangyiClassMapping> mappedByClassId = new HashMap<>();
        for (JiangyiClassMapping m : jiangyiClassMappingMapper.listByDevice(deviceId)) {
            mappedByClassId.putIfAbsent(m.getClassId(), m);
        }
        // 已挂但不在条码候选里的 SKU：一次批量补品名，否则「当前挂接」列只有 id
        List<String> unmappedNames = mappedByClassId.values().stream()
                .map(JiangyiClassMapping::getSkuId)
                .filter(id -> id != null && !id.isBlank() && !skuNames.containsKey(id))
                .distinct()
                .toList();
        for (SkuCatalog sku : skuCatalogMapper.findAllById(unmappedNames)) {
            skuNames.put(sku.getSkuId(), sku.getSkuName());
        }

        List<DecodeRow> rows = new ArrayList<>();
        int matched = 0;
        for (JiangyiClassesParser.ParsedClassRow row : parsed.rows()) {
            JiangyiTextNameSku s = byTextName.get(row.textName());
            if (s != null) {
                matched++;
            }
            JiangyiClassMapping m = mappedByClassId.get(row.classId());
            List<SkuCandidate> candidates = (s == null || s.barCode() == null)
                    ? List.of()
                    : candidatesByBarcode.getOrDefault(s.barCode().trim(), List.of());
            rows.add(new DecodeRow(row.classId(), row.textName(),
                    s == null ? null : s.classId(),
                    s == null ? null : s.stdSkuId(),
                    s == null ? null : s.stdSkuName(),
                    s == null ? null : s.barCode(),
                    candidates,
                    m == null ? null : m.getSkuId(),
                    m == null || m.getSkuId() == null ? null : skuNames.get(m.getSkuId()),
                    m == null ? null : m.getStatus()));
        }
        log.info("jiangyi class-decode deviceId={} lines={} vendorRows={} matched={} classesVersion={}",
                deviceId, parsed.rows().size(), vendor.size(), matched, parsed.classesVersion());
        return new ClassDecodeView(parsed.classesVersion(), parsed.rows().size(),
                vendor.size(), matched, List.copyOf(rows));
    }

    // ---------- 预生成 / 激活 ----------

    /**
     * 映射复核（V400）专用解析入口：与下发/预生成同一解析器、同一 SSRF 白名单，
     * 返回行与内容版本（sha256 前 12 位），供「审阅即认领版本」的差异计算与令牌比对。
     */
    public JiangyiClassesParser.ParsedClasses parseClassesForReview(String textUrl, int quantity,
                                                                    int classIdBase) {
        return parseClasses(textUrl, quantity, classIdBase);
    }

    /** 模型 classes → 该设备映射预生成（source=MODEL_SYNC，status=DISABLED）。返回写入行数。 */
    public int pregenerateForDevice(String deviceId, String modelName, int classIdBase) {
        JiangyiDevice device = requireBoundDevice(deviceId);
        JiangyiModelFile model = requireModel(modelName);
        // 预生成同样按机型过滤：非本机型模型不生成映射（发了也下发不了，避免无效 DISABLED 行污染人工确认面）
        requireModelCompatible(device, model);
        return writePregenerated(deviceId, modelName, parseClasses(model, classIdBase).rows());
    }

    /**
     * 显式 classes 地址预生成（台账 G-14）：§4.4.4 回 {@code []} 时，这是把 classId↔textName
     * 落库的唯一路径——厂商后台点的那次下发不会写进 §4.4.4（2026-10-21 对照实验已证）。
     * <p>机型匹配这一步<b>无法校验</b>：显式载荷没有厂商的 industrialControlModel 字段，
     * 型号对不对由操作人对 modelUrl 文件名负责（G-15 记的 rk3576 嫌疑）。其余校验与
     * {@link #pregenerateForDevice} 同：BOUND + classes 过 SSRF 白名单 + 行数==quantity。</p>
     */
    public int pregenerateForDeviceByUrl(String deviceId, ExplicitModel model, int classIdBase) {
        requireBoundDevice(deviceId);
        model.requireComplete();
        return writePregenerated(deviceId, model.modelName().trim(),
                parseClasses(model.textUrl().trim(), model.quantity(), classIdBase).rows());
    }

    private int writePregenerated(String deviceId, String modelName,
                                  List<JiangyiClassesParser.ParsedClassRow> rows) {
        Set<String> trainedTextNames = new HashSet<>();
        for (TrainedProduct p : jiangyiGatherClient.trainedProducts()) {
            if (p.textName() != null) {
                trainedTextNames.add(p.textName());
            }
        }
        for (JiangyiClassesParser.ParsedClassRow row : rows) {
            JiangyiClassMapping mapping = new JiangyiClassMapping();
            mapping.setDeviceId(deviceId);
            mapping.setClassId(row.classId());
            mapping.setModelName(modelName);
            mapping.setTextName(row.textName());
            // skuId 不设（null 不进 SET 子句）：MODEL_SYNC 覆盖 MANUAL/既有行时保留已挂 SKU
            mapping.setSkuId(null);
            mapping.setStatus("DISABLED");
            mapping.setSource("MODEL_SYNC");
            jiangyiClassMappingMapper.upsert(mapping);
        }
        log.info("jiangyi model-sync pregenerated deviceId={} modelName={} rows={} trainedMatched={}/{}",
                deviceId, modelName, rows.size(),
                rows.stream().filter(r -> trainedTextNames.contains(r.textName())).count(),
                rows.size());
        return rows.size();
    }

    /** 批量激活某设备某模型的全部预生成行（人工确认后一次性生效）。 */
    public int activatePregenerated(String deviceId, String modelName) {
        requireBoundDevice(deviceId);
        int activated = jiangyiClassMappingMapper.activatePregenerated(deviceId, modelName);
        log.info("jiangyi model-sync activated deviceId={} modelName={} rows={}", deviceId, modelName, activated);
        return activated;
    }

    // ---------- 下发 / 回执 ----------

    /** 下发模型到设备（BOUND + 机型字符串相等 + 无未决 SENT 幂等）→ 落 SENT 审计 → 调 gateway。 */
    public long pushModel(String deviceId, String modelName) {
        JiangyiDevice device = requireBoundDevice(deviceId);
        requireRegisteredControlModel(device);
        JiangyiModelFile model = requireModel(modelName);
        requireModelCompatible(device, model);
        JiangyiClassesParser.ParsedClasses parsed = parseClasses(model, 0);

        return recordAndPush(device, String.valueOf(model.id()), model.modelName(),
                model.modelUrl(), model.modelTextUrl(), model.quantity(), parsed.classesVersion());
    }

    /**
     * 显式 URL 下发（台账 G-14）：走同一条 §4.3.2.9 `updateModel` 通道，但<b>不查 §4.4.4</b>。
     * <p>留着不松的校验：BOUND、机型已登记、无未决 SENT、classes 地址过 SSRF 白名单、
     * 行数==quantity（错位映射一旦激活就是错钱）。去掉的只有 `requireModel` 那道我方自加的
     * 门 —— 真机日志证明 `updateModel` 载荷本来就是裸 URL（G-15/G-16）。</p>
     */
    public long pushModelByUrl(String deviceId, ExplicitModel model) {
        JiangyiDevice device = requireBoundDevice(deviceId);
        requireRegisteredControlModel(device);
        model.requireComplete();
        JiangyiClassesParser.ParsedClasses parsed =
                parseClasses(model.textUrl().trim(), model.quantity(), 0);

        return recordAndPush(device, null, model.modelName().trim(), model.modelUrl().trim(),
                model.textUrl().trim(), model.quantity(), parsed.classesVersion());
    }

    /** 落 SENT 审计 → 网关下发；网关侧失败即标 FAILED（两条下发路径共用）。 */
    private long recordAndPush(JiangyiDevice device, String modelId, String modelName,
                               String modelUrl, String textUrl, int quantity, String classesVersion) {
        String deviceId = device.getDeviceId();
        if (deploymentMapper.hasPendingSent(deviceId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该设备存在未完成的模型下发（SENT 待回执），请等待回执或超时后再试");
        }
        JiangyiModelDeployment dep = new JiangyiModelDeployment();
        dep.setDeviceId(deviceId);
        dep.setModelId(modelId);
        dep.setModelName(modelName);
        dep.setModelUrl(modelUrl);
        dep.setClassesTextUrl(textUrl);
        dep.setIndustrialControlModel(device.getIndustrialControlModel());
        dep.setClassesVersion(classesVersion);
        dep.setStatus("SENT");
        dep.setSentAt(Instant.now());
        deploymentMapper.insert(dep);

        // 组装 V16 §4.3.2.9 updateModel；gateway 负责 identifier→WS + tracker 超时兜底
        try {
            jiangyiGatewayClient.modelPush(deviceId, dep.getId(), modelName, modelUrl, textUrl, quantity);
        } catch (Exception e) {
            deploymentMapper.fail(dep.getId(), "gateway 下发失败：" + e.getMessage(), Instant.now());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "模型下发到网关失败：" + e.getMessage());
        }
        log.info("jiangyi model pushed deviceId={} modelName={} deploymentId={} classesVersion={}",
                deviceId, modelName, dep.getId(), classesVersion);
        return dep.getId();
    }

    /**
     * 设备 downloadModelNotify 回执（gateway 转发）：回填 model_name/classes_version + CONFIRMED。
     *
     * <p>复核失效（V400）两条路：</p>
     * <ul>
     *   <li>回执命中 SENT → classes_version 被改成新版本，mapping_verified_version 落后即自动失效；</li>
     *   <li>回执对不上任何 SENT，且该模型也从未 CONFIRMED 过 = 柜机在跑一个我方没下发的模型
     *       （厂商直推/恢复出厂）。此时<b>主动撤销复核戳</b>再抛 409：否则旧映射会继续按未知
     *       classes 结算，这正是 H-27 的错钱路径。重放已知模型则不动状态。</li>
     * </ul>
     */
    public void confirmModel(String deviceId, String modelName) {
        JiangyiModelDeployment dep = deploymentMapper.findLatestSent(deviceId, modelName)
                .orElseThrow(() -> {
                    if (!deploymentMapper.hasConfirmed(deviceId, modelName)) {
                        Instant now = Instant.now();
                        int cleared = jiangyiDeviceMapper.clearMappingVerification(deviceId, now);
                        log.warn("jiangyi model notify for unknown model deviceId={} modelName={} "
                                + "→ mapping verification revoked rows={}", deviceId, modelName, cleared);
                        return new ResponseStatusException(HttpStatus.CONFLICT,
                                "无进行中的模型下发记录（modelName=" + modelName + "），且该模型从未确认过"
                                        + "——柜机可能被厂商直推了未知模型；已撤销映射复核，请按当前模型重算并确认");
                    }
                    return new ResponseStatusException(HttpStatus.CONFLICT,
                            "无进行中的模型下发记录（modelName=" + modelName + "）——疑似串包或重放，忽略");
                });
        Instant now = Instant.now();
        deploymentMapper.confirm(dep.getId(), dep.getClassesVersion(), now);
        jiangyiDeviceMapper.updateModelInfo(deviceId, modelName, dep.getClassesVersion(), now);
        log.info("jiangyi model confirmed deviceId={} modelName={} deploymentId={} classesVersion={}",
                deviceId, modelName, dep.getId(), dep.getClassesVersion());
    }

    /**
     * 模型下发超时（watchdog）：SENT → FAILED（幂等）。
     *
     * <p>超时只说明没收到回执，柜机可能已经切换过去了（回执丢失要比没收到更常见）。
     * 为防「映射按旧模型审过、柜机跑新模型」的错位扣款，只要有一行真的置败就撤销复核戳
     * （V400）；已 FAILED 的重入 no-op 不再重复撤销。</p>
     */
    public void markPushTimeout(long deploymentId, String reason) {
        Instant now = Instant.now();
        int rows = deploymentMapper.fail(deploymentId, reason, now);
        if (rows > 0) {
            deploymentMapper.byId(deploymentId).ifPresent(dep -> {
                int cleared = jiangyiDeviceMapper.clearMappingVerification(dep.getDeviceId(), now);
                log.warn("jiangyi model push timeout revoked mapping verification deviceId={} "
                        + "deploymentId={} rows={}", dep.getDeviceId(), deploymentId, cleared);
            });
        }
        log.warn("jiangyi model push timeout deploymentId={} rows={} reason={}", deploymentId, rows, reason);
    }

    public List<JiangyiModelDeployment> listDeployments(String deviceId) {
        return deploymentMapper.listByDevice(deviceId);
    }

    // ---------- 内部 ----------

    private JiangyiDevice requireBoundDevice(String deviceId) {
        JiangyiDevice device = jiangyiDeviceMapper.selectById(deviceId);
        if (device == null || !"BOUND".equals(device.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "将邑设备未绑定：" + deviceId);
        }
        return device;
    }

    private JiangyiModelFile requireModel(String modelName) {
        return jiangyiGatherClient.modelFiles().stream()
                .filter(m -> modelName.equals(m.modelName()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "将邑侧未找到模型：" + modelName));
    }

    /** 机型校验：字符串相等（原值透传，不解释 "76"/"88"）。 */
    private void requireModelCompatible(JiangyiDevice device, JiangyiModelFile model) {
        if (model.industrialControlModel() == null
                || !model.industrialControlModel().equals(device.getIndustrialControlModel())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "机型不匹配：设备 %s ≠ 模型 %s".formatted(
                            device.getIndustrialControlModel(), model.industrialControlModel()));
        }
    }

    /** 机型未登记时两条下发路径都拒绝（下发了也没法判型号对不对）。 */
    private void requireRegisteredControlModel(JiangyiDevice device) {
        if (device.getIndustrialControlModel() == null || device.getIndustrialControlModel().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "设备未登记工控机机型（将邑原值 76/88），拒绝下发");
        }
    }

    /** AU-183：允许的 classes.txt 主机后缀白名单（逗号分隔；默认将邑域，测试注入 localhost）。 */
    @org.springframework.beans.factory.annotation.Value(
            "${aicabinet.jiangyi.model-sync.allowed-hosts:hunanjysmart.com}")
    private java.util.List<String> allowedModelHosts = java.util.List.of("hunanjysmart.com");

    private JiangyiClassesParser.ParsedClasses parseClasses(JiangyiModelFile model, int classIdBase) {
        return parseClasses(model.modelTextUrl(), model.quantity(), classIdBase);
    }

    private JiangyiClassesParser.ParsedClasses parseClasses(String textUrl, int quantity, int classIdBase) {
        String url = requireSafeModelTextUrl(textUrl);
        String content = publicHttp.get()
                .uri(URI.create(url))
                .retrieve()
                .body(String.class);
        return JiangyiClassesParser.parse(content, quantity, classIdBase);
    }

    /**
     * AU-183（2026-10-18 批次 5）SSRF 守卫：classes.txt URL 来自将邑第三方接口返回值，
     * 侧被攻陷/接口被 MITM 即可让 trade 以自身网络位置打内网（169.254.169.254 /
     * actuator / Redis 管理面）。守卫：仅 https + 将邑域名后缀白名单 + 拒 IP 直连形态
     * （私网/回环/链路本地地址根本不是合法域名）。
     */
    private String requireSafeModelTextUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模型 classes.txt 地址为空");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模型 classes.txt 地址非法");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        // 条目形态：`domain`（=强制 https，含子域）/ `http://host`、`https://host`（scheme+host 精确，
        // 供测试本地 HttpServer 等 http 例外）。默认 hunanjysmart.com = 仅 https。
        boolean allowlisted = allowedModelHosts.stream()
                .map(String::trim).filter(e -> !e.isBlank())
                .map(String::toLowerCase)
                .anyMatch(entry -> {
                    if (entry.startsWith("http://")) {
                        return "http".equals(scheme) && host.equals(entry.substring(7));
                    }
                    if (entry.startsWith("https://")) {
                        return "https".equals(scheme) && host.equals(entry.substring(8));
                    }
                    return "https".equals(scheme)
                            && (host.equals(entry) || host.endsWith("." + entry));
                });
        if (!allowlisted) {
            log.warn("jyi model classes url blocked by SSRF guard url={}", url);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "模型 classes.txt 地址不在允许的将邑域内");
        }
        return url.trim();
    }

    private static String classesVersionOf(JiangyiModelFile model) {
        return JiangyiClassesParser.sha256Prefix(model.modelTextUrl() == null ? "" : model.modelTextUrl());
    }

    /** 预览降级原因：ResponseStatusException 只取 reason（去掉「400 BAD_REQUEST」前缀），其余取 message。 */
    private static String previewRejectReason(Exception e) {
        if (e instanceof ResponseStatusException rse && rse.getReason() != null) {
            return rse.getReason();
        }
        return e.getMessage();
    }

    /**
     * 显式下发入参 = V16 §4.3.2.9 `updateModel` 的 msgContent 四字段（协议层本来就是裸 URL，
     * 不含任何「模型须登记在租户名下」的前置；台账 G-14/G-15）。
     */
    public record ExplicitModel(String modelName, String modelUrl, String textUrl, Integer quantity) {
        void requireComplete() {
            if (modelName == null || modelName.isBlank()
                    || modelUrl == null || modelUrl.isBlank()
                    || textUrl == null || textUrl.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "显式下发需要 modelName / modelUrl / classes 地址三项非空");
            }
            if (quantity == null || quantity <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模型商品数量必须是正整数");
            }
        }
    }

    /** admin 预览行（openapi inline schema）。 */
    public record ModelPreview(String modelName, String industrialControlModel, int quantity,
                               String modelTextUrl, String classesVersion, String fallbackVersion,
                               List<JiangyiClassesParser.ParsedClassRow> rows,
                               String rejectReason, int trainedMatchPercent) {}

    /** 本平台候选 SKU（只按条码精确相等命中；多命中=名称规格要人工分辨，不自动选）。 */
    public record SkuCandidate(String skuId, String skuName, String barcode) {}

    /**
     * §5.5.3 对照解码行（openapi inline schema）：vendor* 为 null = 该 classes 行在厂商标准
     * 商品库里对不上号（实测 69 行文件回 67 行），仍须人工挂接。
     */
    public record DecodeRow(int classId, String textName, Integer vendorClassId,
                            Long stdSkuId, String stdSkuName, String vendorBarcode,
                            List<SkuCandidate> localCandidates,
                            String mappedSkuId, String mappedSkuName, String mappingStatus) {}

    /** 解码汇总：classesLines/vendorMatchedRows 直接说明「多少行还翻不出来」。 */
    public record ClassDecodeView(String classesVersion, int classesLines, int vendorRows,
                                  int vendorMatchedRows, List<DecodeRow> rows) {}
}
