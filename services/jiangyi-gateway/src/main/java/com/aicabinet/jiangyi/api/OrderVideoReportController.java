package com.aicabinet.jiangyi.api;

import com.aicabinet.jiangyi.auth.DeviceAuthInterceptor;
import com.aicabinet.jiangyi.client.TradeInternalClient;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 将邑设备面视频上报端点（CB-024，V16 §4.2.14 uploadVideoUrl，PDF 原件第 29-30 页）。
 *
 * <p><b>分片语义</b>：视频超 1 分半自动分文件，设备按 {@code serialNum}（第几片，
 * 1 起）/ {@code videoQuantity}（总片数）分多次上报；{@code videoUrls} 为数组——
 * 上/下摄像头地址同片上报（逗号分隔语义），<b>生成/上传失败时可为空数组</b>
 * （文档原文明示「如：[] [http://...]」），空数组也落库（留痕「该片失败」）。</p>
 *
 * <p>落库走 trade 内部端点（gateway 无 DB 依赖，与模型回执同模式转发）；
 * 幂等由 trade 侧 uk(order_no, serial_num) upsert 承担——设备重试重报不产生重复行。
 * 响应固定 {@code {status:200,msg:"success",data:""}}（转发失败也回 200 防设备
 * 重试风暴，与 downloadModelNotify 同策略；落库失败由日志暴露补偿）。</p>
 */
@RestController
public class OrderVideoReportController {

    private static final Logger log = LoggerFactory.getLogger(OrderVideoReportController.class);

    private final TradeInternalClient tradeInternalClient;

    public OrderVideoReportController(TradeInternalClient tradeInternalClient) {
        this.tradeInternalClient = tradeInternalClient;
    }

    /** §4.2.14 请求体（表格 36）：orderNo/serialNum/videoQuantity 必填，videoUrls 数组可空。 */
    public record UploadVideoUrlRequest(String orderNo, Integer serialNum,
                                        Integer videoQuantity, List<String> videoUrls) {}

    @PostMapping("/jiangyi/api/device/pokerOrderVideo/uploadVideoUrl")
    public Map<String, Object> uploadVideoUrl(HttpServletRequest request,
                                              @RequestBody UploadVideoUrlRequest body) {
        String deviceId = attr(request, DeviceAuthInterceptor.ATTR_DEVICE_ID);
        if (body == null || body.orderNo() == null || body.orderNo().isBlank()
                || body.serialNum() == null || body.videoQuantity() == null) {
            log.warn("jiangyi uploadVideoUrl missing required fields deviceId={} body={}", deviceId, body);
            return Map.of("status", 400, "msg", "missing required fields", "data", "");
        }
        List<String> urls = body.videoUrls() == null ? List.of() : body.videoUrls();
        try {
            tradeInternalClient.orderVideoReport(deviceId, body.orderNo().trim(),
                    body.serialNum(), body.videoQuantity(), urls);
            log.info("jiangyi video report deviceId={} orderNo={} serial={}/{} urls={}",
                    deviceId, body.orderNo(), body.serialNum(), body.videoQuantity(), urls.size());
        } catch (Exception e) {
            log.error("jiangyi video report forward failed deviceId={} orderNo={} serial={}: {}",
                    deviceId, body.orderNo(), body.serialNum(), e.getMessage());
        }
        return Map.of("status", 200, "msg", "success", "data", "");
    }

    private static String attr(HttpServletRequest request, String name) {
        Object v = request.getAttribute(name);
        return v == null ? "unknown" : v.toString();
    }
}
