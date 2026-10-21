package com.aicabinet.jiangyi.normalize;

import com.aicabinet.common.dto.VisionRecognitionResultDto;
import com.aicabinet.jiangyi.client.TradeInternalClient;
import com.aicabinet.jiangyi.client.TradeInternalClient.MappingView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 将邑识别上报归一（CB-022，方案 §4.3）：forms → {@link VisionRecognitionResultDto.Item}
 * [{skuId,quantity,confidence}]。
 *
 * <p><b>报文形状（2026-10-21 依真机固件重定）</b>：同一个端点在 V16 里有两套字段表——
 * §4.2.10（第四章 普通对接）只列 classId/quantity/specialId，§5.2.9（第五章 使用将邑后台）
 * 则把 thirdPid/price/productName 列为 <b>M 必填</b>。旧实现只照抄了前者，并写下
 * 「将邑识别上报无金额字段」的铁律——APK 反编译证明该结论不成立：
 * {@code data/service/Forms.smali} 的字段集是 barCode/classId/floorNum/price/productCode/
 * productName/quantity/specialId/thirdPid/txtName，上传前
 * {@code rknn/tool/UploadInfo2Server.smali} 会用设备侧补货表（{@code ReplenishGoodsInfos}）
 * 按 classId 逐行回填 thirdPid/productName/price。⇒ 全字段建模，未知字段不再静默丢弃。</p>
 *
 * <p><b>SKU 解析优先级</b>：{@code thirdPid > 0} 先按挂接关系（stdSku id 唯一键）解析，
 * 命中即免逐设备映射；未命中或 thirdPid=0（设备侧补货表为空/未挂接）回落
 * {@code classId → jiangyi_class_mapping}。两条都落空 = fail-closed。</p>
 *
 * <p>🔴 <b>金额铁律不变（A 档，2026-10-21 用户拍板）</b>：上报里的 {@code price}
 * <b>只存档不参与结算</b>——金额一律由 trade 侧按我方 SKU 现价合计（分）。采信厂商标价的
 * B 档要把资金金额来源交给对方，须等真机跑通、拿到真实报文比对两侧价差后再议。
 * 存档落 {@code jiangyi_recognition_report}（原样 forms，含未命中/空上报）。</p>
 *
 * <p>fail-closed 语义：任一 form 解析不到 SKU ⇒ <b>整单</b>不转发结算，交
 * recognize-timeout 转 DISPUTED（部分转发会算错账，宁可人工介入）。</p>
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
     * @return 全部 form 都能解析到 SKU → 归一化 items；任一解析不到 → empty（调用方 fail-closed）
     */
    public List<VisionRecognitionResultDto.Item> normalize(String deviceId, List<Form> forms) {
        if (deviceId == null || deviceId.isBlank() || forms == null || forms.isEmpty()) {
            return List.of();
        }
        List<VisionRecognitionResultDto.Item> items = new ArrayList<>(forms.size());
        for (Form form : forms) {
            if (form.quantity() == null || form.quantity() <= 0) {
                log.warn("jiangyi recognition form invalid deviceId={} form={}", deviceId, form);
                return List.of();
            }
            String skuId = resolveSkuId(deviceId, form);
            if (skuId == null) {
                return List.of();
            }
            items.add(new VisionRecognitionResultDto.Item(skuId, form.quantity(), 1.0d));
        }
        return items;
    }

    /** thirdPid（将邑标准商品 id）优先，回落 classId 映射；两条都落空返回 null。 */
    private String resolveSkuId(String deviceId, Form form) {
        Integer thirdPid = form.thirdPid();
        if (thirdPid != null && thirdPid > 0) {
            Optional<String> linked = tradeInternalClient.resolveSkuByProduct(String.valueOf(thirdPid));
            if (linked.isPresent()) {
                log.info("jiangyi recognition resolved by thirdPid deviceId={} thirdPid={} classId={} skuId={}",
                        deviceId, thirdPid, form.classId(), linked.get());
                return linked.get();
            }
            log.warn("jiangyi thirdPid MISS deviceId={} thirdPid={} classId={} — 回落 classId 映射",
                    deviceId, thirdPid, form.classId());
        }
        if (form.classId() == null) {
            log.warn("jiangyi recognition form without classId deviceId={} form={} — fail-closed", deviceId, form);
            return null;
        }
        MappingView mapping = tradeInternalClient.resolveMapping(deviceId, form.classId()).orElse(null);
        if (mapping == null || mapping.skuId() == null || mapping.skuId().isBlank()) {
            log.warn("jiangyi class mapping MISS deviceId={} classId={} — fail-closed", deviceId, form.classId());
            return null;
        }
        return mapping.skuId();
    }

    /**
     * 厂商上报价合计（分）——CB-062 留档列，🔴 <b>绝不参与结算</b>（A 档：金额一律按我方 SKU 现价）。
     * 全无 price 字段（§4.2.10 形态报文）返回 null，用于区分「0 元单」与「报文不带价」。
     */
    public static Long vendorAmountCents(List<Form> forms) {
        if (forms == null || forms.isEmpty()) {
            return null;
        }
        long total = 0;
        boolean anyPriced = false;
        for (Form form : forms) {
            if (form.price() == null || form.quantity() == null) {
                continue;
            }
            anyPriced = true;
            total += (long) form.price() * form.quantity();
        }
        return anyPriced ? total : null;
    }

    /** modelVersion 组装（截断防护，不因超长让结算链路 400）。 */
    public static String modelVersion(String modelName) {
        String raw = "jiangyi:" + (modelName == null ? "unknown" : modelName);
        return raw.length() <= VisionRecognitionResultDto.MODEL_VERSION_MAX_LENGTH
                ? raw : raw.substring(0, VisionRecognitionResultDto.MODEL_VERSION_MAX_LENGTH);
    }

    /**
     * 上报商品行——字段名逐字对齐固件 {@code Forms} DTO（V16 §4.2.10 + §5.2.9 并集）。
     * 除 classId/quantity 外的字段在 §4.2.10 形态下可能整体缺席，故一律装箱类型。
     *
     * @param thirdPid    将邑标准商品 id（§5.2.9 M）；0/缺席 = 设备侧补货表没有这一行
     * @param price       厂商标价，<b>单位分</b>（§5.2.9 M）；仅存档，不参与结算
     * @param specialId   商户商品特殊 ID（§4.2.10 C，来源仍未经厂商定义，不用于解析）
     * @param txtName     模型 classes.txt 行名（固件按 CLASSES_NAME[classId] 回填）
     * @param barCode     条码（补货表有值时固件才带）
     * @param floorNum    货道号（固件字段为 String，原样保留）
     */
    public record Form(Integer classId,
                       Integer quantity,
                       Integer thirdPid,
                       Integer price,
                       String productName,
                       String specialId,
                       String barCode,
                       String txtName,
                       String productCode,
                       String floorNum) {}
}
