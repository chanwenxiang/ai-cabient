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
     * trade 不可达/查询失败时返回空数组而非 5xx——设备启动流程不应被基础设施抖动卡死。
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
            classIds = List.of();
        }
        log.info("jiangyi floor getDeviceSellProductClassIds identifier={} classIds={}", identifier, classIds);
        return Map.of("status", 200, "msg", "success", "data", classIds);
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
