package com.aicabinet.jiangyi.normalize;

import com.aicabinet.common.dto.VisionRecognitionResultDto;
import com.aicabinet.jiangyi.client.TradeInternalClient;
import com.aicabinet.jiangyi.client.TradeInternalClient.MappingView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 将邑识别上报归一（CB-022，方案 §4.3）：forms[{classId,quantity,specialId?}]
 * → {@link VisionRecognitionResultDto.Item}[{skuId,quantity,confidence}]。
 *
 * <p>铁律：将邑识别上报<b>无金额字段</b>（V16 §4.2.10 表27 已核对，specialId 亦不采信——
 * 全文档仅此一处出口、来源未定义）⇒ 金额由 trade 侧按映射 SKU 现价合计（分）。
 * classId 未命中映射 = fail-closed：整单不转发结算，交 recognize-timeout 转 DISPUTED
 * （宁可人工介入，不算错账——部分转发会算错账，故未命中即整单拒绝）。</p>
 *
 * <p>modelVersion = {@code jiangyi:<modelName>}，超长截断至 64
 * （{@link VisionRecognitionResultDto#MODEL_VERSION_MAX_LENGTH}，平台落库硬约束）。</p>
 */
@Component
public class RecognitionNormalizer {

    private static final Logger log = LoggerFactory.getLogger(RecognitionNormalizer.class);

    private final TradeInternalClient tradeInternalClient;

    public RecognitionNormalizer(TradeInternalClient tradeInternalClient) {
        this.tradeInternalClient = tradeInternalClient;
    }

    /**
     * @return 命中全部 classId → 归一化 items；任一未命中 → empty（调用方 fail-closed）
     */
    public List<VisionRecognitionResultDto.Item> normalize(String deviceId, List<Form> forms) {
        if (deviceId == null || deviceId.isBlank() || forms == null || forms.isEmpty()) {
            return List.of();
        }
        List<VisionRecognitionResultDto.Item> items = new ArrayList<>(forms.size());
        for (Form form : forms) {
            if (form.classId() == null || form.quantity() == null || form.quantity() <= 0) {
                log.warn("jiangyi recognition form invalid deviceId={} form={}", deviceId, form);
                return List.of();
            }
            MappingView mapping = tradeInternalClient.resolveMapping(deviceId, form.classId()).orElse(null);
            if (mapping == null || mapping.skuId() == null || mapping.skuId().isBlank()) {
                log.warn("jiangyi class mapping MISS deviceId={} classId={} — fail-closed",
                        deviceId, form.classId());
                return List.of();
            }
            items.add(new VisionRecognitionResultDto.Item(mapping.skuId(), form.quantity(), 1.0d));
        }
        return items;
    }

    /** modelVersion 组装（截断防护，不因超长让结算链路 400）。 */
    public static String modelVersion(String modelName) {
        String raw = "jiangyi:" + (modelName == null ? "unknown" : modelName);
        return raw.length() <= VisionRecognitionResultDto.MODEL_VERSION_MAX_LENGTH
                ? raw : raw.substring(0, VisionRecognitionResultDto.MODEL_VERSION_MAX_LENGTH);
    }

    /** V16 §4.2.10 表27 forms 行（classId 文档排版为 classld，驼峰原名为准）。 */
    public record Form(Integer classId, Integer quantity, String specialId) {}
}
