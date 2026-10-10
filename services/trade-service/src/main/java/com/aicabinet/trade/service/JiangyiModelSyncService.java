package com.aicabinet.trade.service;

import com.aicabinet.trade.client.JiangyiGatherClient;
import com.aicabinet.trade.client.JiangyiGatewayClient;
import com.aicabinet.trade.domain.JiangyiClassMapping;
import com.aicabinet.trade.domain.JiangyiDevice;
import com.aicabinet.trade.domain.JiangyiModelDeployment;
import com.aicabinet.trade.dto.JiangyiGatherDtos.JiangyiModelFile;
import com.aicabinet.trade.dto.JiangyiGatherDtos.TrainedProduct;
import com.aicabinet.trade.mapper.JiangyiClassMappingMapper;
import com.aicabinet.trade.mapper.JiangyiDeviceMapper;
import com.aicabinet.trade.mapper.JiangyiModelDeploymentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
    /** 拉取 classes.txt（将邑 OSS 外链，不带内部凭据）。 */
    private final RestClient publicHttp = RestClient.create();

    public JiangyiModelSyncService(JiangyiGatherClient jiangyiGatherClient,
                                   JiangyiDeviceMapper jiangyiDeviceMapper,
                                   JiangyiClassMappingMapper jiangyiClassMappingMapper,
                                   JiangyiModelDeploymentMapper deploymentMapper,
                                   JiangyiGatewayClient jiangyiGatewayClient) {
        this.jiangyiGatherClient = jiangyiGatherClient;
        this.jiangyiDeviceMapper = jiangyiDeviceMapper;
        this.jiangyiClassMappingMapper = jiangyiClassMappingMapper;
        this.deploymentMapper = deploymentMapper;
        this.jiangyiGatewayClient = jiangyiGatewayClient;
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
            } catch (JiangyiClassesParser.ClassesParseRejectException e) {
                // 预览不因单个模型解析失败而整体失败：标注拒绝原因，人工可见
                out.add(new ModelPreview(model.modelName(), model.industrialControlModel(),
                        model.quantity(), model.modelTextUrl(), null, classesVersionOf(model),
                        List.of(), e.getMessage(), 0));
                continue;
            }
            long matched = rows.stream().filter(r -> trainedTextNames.contains(r.textName())).count();
            out.add(new ModelPreview(model.modelName(), model.industrialControlModel(),
                    model.quantity(), model.modelTextUrl(), classesVersion, classesVersion,
                    rows, null, trainedTextNames.isEmpty() ? 0 : (int) (matched * 100 / rows.size())));
        }
        return out;
    }

    // ---------- 预生成 / 激活 ----------

    /** 模型 classes → 该设备映射预生成（source=MODEL_SYNC，status=DISABLED）。返回写入行数。 */
    public int pregenerateForDevice(String deviceId, String modelName, int classIdBase) {
        JiangyiDevice device = requireBoundDevice(deviceId);
        JiangyiModelFile model = requireModel(modelName);
        // 预生成同样按机型过滤：非本机型模型不生成映射（发了也下发不了，避免无效 DISABLED 行污染人工确认面）
        requireModelCompatible(device, model);
        JiangyiClassesParser.ParsedClasses parsed = parseClasses(model, classIdBase);

        Set<String> trainedTextNames = new HashSet<>();
        for (TrainedProduct p : jiangyiGatherClient.trainedProducts()) {
            if (p.textName() != null) {
                trainedTextNames.add(p.textName());
            }
        }
        int written = 0;
        for (JiangyiClassesParser.ParsedClassRow row : parsed.rows()) {
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
            written++;
        }
        log.info("jiangyi model-sync pregenerated deviceId={} modelName={} rows={} trainedMatched={}/{}",
                deviceId, modelName, written,
                parsed.rows().stream().filter(r -> trainedTextNames.contains(r.textName())).count(),
                parsed.rows().size());
        return written;
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
        if (device.getIndustrialControlModel() == null || device.getIndustrialControlModel().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "设备未登记工控机机型（将邑原值 76/88），拒绝下发");
        }
        JiangyiModelFile model = requireModel(modelName);
        requireModelCompatible(device, model);
        if (deploymentMapper.hasPendingSent(deviceId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该设备存在未完成的模型下发（SENT 待回执），请等待回执或超时后再试");
        }
        JiangyiClassesParser.ParsedClasses parsed = parseClasses(model, 0);

        JiangyiModelDeployment dep = new JiangyiModelDeployment();
        dep.setDeviceId(deviceId);
        dep.setModelId(String.valueOf(model.id()));
        dep.setModelName(model.modelName());
        dep.setModelUrl(model.modelUrl());
        dep.setClassesTextUrl(model.modelTextUrl());
        dep.setIndustrialControlModel(device.getIndustrialControlModel());
        dep.setClassesVersion(parsed.classesVersion());
        dep.setStatus("SENT");
        dep.setSentAt(Instant.now());
        deploymentMapper.insert(dep);

        // 组装 V16 §4.3.2.9 updateModel；gateway 负责 identifier→WS + tracker 超时兜底
        try {
            jiangyiGatewayClient.modelPush(deviceId, dep.getId(), model.modelName(),
                    model.modelUrl(), model.modelTextUrl(), model.quantity());
        } catch (Exception e) {
            deploymentMapper.fail(dep.getId(), "gateway 下发失败：" + e.getMessage(), Instant.now());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "模型下发到网关失败：" + e.getMessage());
        }
        log.info("jiangyi model pushed deviceId={} modelName={} deploymentId={} classesVersion={}",
                deviceId, modelName, dep.getId(), parsed.classesVersion());
        return dep.getId();
    }

    /** 设备 downloadModelNotify 回执（gateway 转发）：回填 model_name/classes_version + CONFIRMED。 */
    public void confirmModel(String deviceId, String modelName) {
        JiangyiModelDeployment dep = deploymentMapper.findLatestSent(deviceId, modelName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "无进行中的模型下发记录（modelName=" + modelName + "）——疑似串包或重放，忽略"));
        Instant now = Instant.now();
        deploymentMapper.confirm(dep.getId(), dep.getClassesVersion(), now);
        jiangyiDeviceMapper.updateModelInfo(deviceId, modelName, dep.getClassesVersion(), now);
        log.info("jiangyi model confirmed deviceId={} modelName={} deploymentId={} classesVersion={}",
                deviceId, modelName, dep.getId(), dep.getClassesVersion());
    }

    /** 模型下发超时（watchdog）：SENT → FAILED（幂等）。 */
    public void markPushTimeout(long deploymentId, String reason) {
        int rows = deploymentMapper.fail(deploymentId, reason, Instant.now());
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

    private JiangyiClassesParser.ParsedClasses parseClasses(JiangyiModelFile model, int classIdBase) {
        String content = publicHttp.get()
                .uri(URI.create(model.modelTextUrl()))
                .retrieve()
                .body(String.class);
        return JiangyiClassesParser.parse(content, model.quantity(), classIdBase);
    }

    private static String classesVersionOf(JiangyiModelFile model) {
        return JiangyiClassesParser.sha256Prefix(model.modelTextUrl() == null ? "" : model.modelTextUrl());
    }

    /** admin 预览行（openapi inline schema）。 */
    public record ModelPreview(String modelName, String industrialControlModel, int quantity,
                               String modelTextUrl, String classesVersion, String fallbackVersion,
                               List<JiangyiClassesParser.ParsedClassRow> rows,
                               String rejectReason, int trainedMatchPercent) {}
}
