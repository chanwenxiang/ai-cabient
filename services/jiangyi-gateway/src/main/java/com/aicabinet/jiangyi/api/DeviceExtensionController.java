package com.aicabinet.jiangyi.api;

import com.aicabinet.jiangyi.auth.DeviceAuthInterceptor;
import com.aicabinet.jiangyi.client.TradeInternalClient;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 真机实测补齐的设备面扩展端点（2026-10-19，CB-053 真机接入联调）。
 *
 * <p><b>成因注释</b>：真机 CQYB10720 鉴权打通后，开机序列继续调用以下端点，
 * 404/401 会中断设备启动流程（拿不到分类 → 不连 WS → 永远 OFFLINE）：</p>
 * <ul>
 *   <li>{@code /floor/getDeviceSellProductClassIds}：<b>两份文档（V16.0.0、采集
 *       v1.13.0）均无此端点</b>（已用 OCR 容错关键词逐一复核）。按名字语义实现：
 *       返回该设备全部在售商品分类 classId（jiangyi_class_mapping ACTIVE 行），
 *       供设备侧识别结果过滤/界面展示。无映射 = 空数组（设备卖不了东西，安全）。</li>
 *   <li>{@code /advertisingDevice/getAdvertisingByDeviceId}：V16 §4.2.19 界面广告
 *       展示（OCR 全文误作 "Deviceld" 曾致漏检）。暂无广告内容 → 返回空数组；
 *       后续商户侧配广告时在此接入。</li>
 * </ul>
 *
 * <p>鉴权：走 DeviceAuthInterceptor（/jiangyi/api/** 全拦，token 端点豁免外）。
 * 响应一律将邑 envelope {@code {status:200,msg:"success",data:...}}。</p>
 */
@RestController
public class DeviceExtensionController {

    private static final Logger log = LoggerFactory.getLogger(DeviceExtensionController.class);

    private final TradeInternalClient tradeInternalClient;

    public DeviceExtensionController(TradeInternalClient tradeInternalClient) {
        this.tradeInternalClient = tradeInternalClient;
    }

    /**
     * 设备在售商品分类列表。data = ACTIVE classId 数组（int）。
     *
     * <p><b>成因注释（2026-10-19 修订，第三轮审计 P1-5）</b>：旧实现把 trade 异常吞成
     * {@code 200+[]}——trade 挂了设备会读成「营业中但没商品」而非重试。云侧语义
     * （log3 实证）：业务空态用 {@code {"status":666,"msg":"…未上架商品","data":null}}。
     * 现区分三态：有映射→200+数组；无 ACTIVE 映射→666；trade 不可达→503（设备按
     * 失败处理可重试，也不至于把故障当「无商品」）。</p>
     */
    @PostMapping("/jiangyi/api/floor/getDeviceSellProductClassIds")
    public Map<String, Object> getDeviceSellProductClassIds(HttpServletRequest request) {
        String deviceId = attr(request, DeviceAuthInterceptor.ATTR_DEVICE_ID);
        String identifier = attr(request, DeviceAuthInterceptor.ATTR_IDENTIFIER);
        List<Integer> classIds;
        try {
            classIds = tradeInternalClient.listMappings(deviceId).stream()
                    .filter(m -> "ACTIVE".equals(m.status()))
                    .map(TradeInternalClient.MappingView::classId)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            log.warn("jiangyi getDeviceSellProductClassIds listMappings failed deviceId={}: {}",
                    deviceId, e.getMessage());
            return Map.of("status", 503, "msg", "service unavailable", "data", "");
        }
        if (classIds.isEmpty()) {
            log.info("jiangyi floor getDeviceSellProductClassIds identifier={} → 666（未上架商品）", identifier);
            return Map.of("status", 666, "msg", "当前设备未上架商品", "data", "");
        }
        log.info("jiangyi floor getDeviceSellProductClassIds identifier={} classIds={}", identifier, classIds);
        return Map.of("status", 200, "msg", "success", "data", classIds);
    }

    /**
     * V16 §5.5.2 语义的设备侧对应物：<b>设备主动拉取本机补货/在售商品表</b>。
     *
     * <p><b>为什么必须实现（2026-10-21，APK 反编译实证）</b>：固件里
     * {@code ReplenishGoodsInfos.addGoods} 的<b>唯一调用点</b>是
     * {@code control/main/tool/GetReplenishGoodsInfo$1}（解析本端点响应），调用时机为
     * 购物预览（CameraPreviewNew）与上报前（UploadInfo2Server）。识别上报的
     * {@code thirdPid/price/productName} 就是按 classId 从这张表回填的——
     * 该端点 404 ⇒ 表恒空 ⇒ 上报里这三个字段恒 0/null，识别歧义消解
     * （{@code UpDownCampare} 按 {@code stock>0} 偏向有货类别、
     * {@code RawPairedPrefixClassEvidencePolicy} 要求 {@code thirdPid>0} 且 category 非空）
     * 也随之降级。⇒ 这是「开门→取货→识别→结算」能否走通的必需下发面，不是可选项。</p>
     *
     * <p><b>响应契约</b>（固件 Gson 目标类 {@code GoodsInfoMsgEntiy} →
     * {@code GoodsInfoMsgProducts.products[]} → {@code ReplenishGoodsInfo}）：
     * {@code {status:200,msg:"success",data:{products:[{classId,thirdPid,productName,
     * salePrice,stock,category}]}}}}。设备只按 {@code "status":200} 判成功，
     * 故 <b>空表也回 200+[]</b>（回 666/503 会把「没有在售商品」和「拉取失败」混成同一个
     * onFailed 分支）；仅 trade 不可达回 503。{@code category} 暂不下发：
     * 取值是形态枚举（bag/bottle/bowl/box/egg），与我方 SKU 类目无映射依据，
     * 固件各消费点均先判 null（{@code if-eqz}），留空只是不启用形态判定，不会 NPE。</p>
     */
    @PostMapping("/jiangyi/api/floor/getFloorByDevice")
    public Map<String, Object> getFloorByDevice(HttpServletRequest request) {
        String deviceId = attr(request, DeviceAuthInterceptor.ATTR_DEVICE_ID);
        List<Map<String, Object>> products;
        try {
            products = tradeInternalClient.listFloorGoods(deviceId).stream()
                    .filter(g -> g.classId() != null)
                    .map(g -> {
                        Map<String, Object> row = new java.util.LinkedHashMap<String, Object>();
                        row.put("classId", g.classId());
                        row.put("thirdPid", g.thirdPid() == null ? 0 : g.thirdPid());
                        row.put("productName", g.productName());
                        row.put("salePrice", g.salePrice() == null ? 0 : g.salePrice());
                        row.put("stock", g.stock() == null ? 0 : g.stock());
                        return (Map<String, Object>) row;
                    })
                    .toList();
        } catch (Exception e) {
            log.warn("jiangyi floor getFloorByDevice floor-goods failed deviceId={}: {}",
                    deviceId, e.getMessage());
            return Map.of("status", 503, "msg", "service unavailable", "data", Map.of());
        }
        log.info("jiangyi floor getFloorByDevice deviceId={} products={}", deviceId, products.size());
        return Map.of("status", 200, "msg", "success", "data", Map.of("products", products));
    }

    /**
     * V16 §4.2.19 界面广告展示。data = [{advertisingAddress, advertisingType}]，
     * advertisingType 1=图片(jpg/png) 2=视频(mp4)。暂无广告运营面 → 恒空数组
     * （文档注明该接口面向「机器端有屏幕的商户」，空数组=不展示，符合最小实现）。
     */
    @PostMapping("/jiangyi/api/advertisingDevice/getAdvertisingByDeviceId")
    public Map<String, Object> getAdvertisingByDeviceId(HttpServletRequest request) {
        log.info("jiangyi advertising getAdvertisingByDeviceId deviceId={} → []（暂无广告内容）",
                attr(request, DeviceAuthInterceptor.ATTR_DEVICE_ID));
        return Map.of("status", 200, "msg", "success", "data", List.of());
    }

    private static String attr(HttpServletRequest request, String name) {
        Object v = request.getAttribute(name);
        return v == null ? "unknown" : v.toString();
    }
}
