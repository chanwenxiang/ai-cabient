package com.aicabinet.jiangyi.api;

import com.aicabinet.jiangyi.auth.DeviceAuthInterceptor;
import com.aicabinet.jiangyi.client.TradeInternalClient;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
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

    /**
     * 补货历史视频上报端点（2026-10-30 真机实测新增，非 V16 文档接口）。
     *
     * <p><b>为什么加这个端点</b>：设备固件在「拉取柜内视频」后，把 OSS URL 通过
     * {@code /jiangyi/api/replenishmentHistoryVideo/create} 上报——不是走
     * {@code uploadVideoUrl}。2026-10-30 真机实测确认：正常消费单拉取视频后，
     * 设备走此端点上报 2 路 OSS URL，网关此前 404 导致 URL 丢失、后台看不到录像。</p>
     *
     * <p><b>Payload 兼容</b>：设备固件可能用 {@code orderNo}、{@code transactionId}
     * 或 {@code historyNo} 标识单号（{@code setOrderNoVideo} 用 transactionId，
     * {@code uploadVideoUrl} 用 orderNo，<b>补货录像实流用 historyNo=补货会话 id</b>——
     * 2026-10-30 15:59 真机报文实键，与 orderNo 同为我方 sessionId 约定）；
     * {@code videoUrls} 可能是 JSON 数组或逗号分隔字符串。本端点用 Map 灵活解析，
     * 不绑定具体字段名；serialNum/videoQuantity 非数值一律回默认 1（铁律：固件不可信）。</p>
     *
     * <p>落库走 trade 内部端点（与 {@code uploadVideoUrl} 同一路径），
     * 幂等由 trade 侧 uk(order_no, serial_num) upsert 承担。</p>
     */
    @PostMapping("/jiangyi/api/replenishmentHistoryVideo/create")
    public Map<String, Object> replenishmentHistoryVideo(HttpServletRequest request,
                                                          @RequestBody Map<String, Object> body) {
        String deviceId = attr(request, DeviceAuthInterceptor.ATTR_DEVICE_ID);

        // 单号兼容链：orderNo → transactionId → historyNo（补货录像实流，2026-10-30 真机报文）
        Object orderNoObj = body.get("orderNo");
        if (orderNoObj == null || orderNoObj.toString().isBlank()) {
            orderNoObj = body.get("transactionId");
        }
        if (orderNoObj == null || orderNoObj.toString().isBlank()) {
            orderNoObj = body.get("historyNo");
        }
        String orderNo = orderNoObj != null ? orderNoObj.toString().trim() : null;

        // serialNum/videoQuantity 默认 1；固件不可信——非数值不炸 500，回默认
        int serialNum = safeInt(body.get("serialNum"));
        int videoQuantity = safeInt(body.get("videoQuantity"));

        // videoUrls 兼容：JSON 数组 或 逗号分隔字符串
        Object videoUrlsObj = body.get("videoUrls");
        List<String> videoUrls = new ArrayList<>();
        if (videoUrlsObj instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !item.toString().isBlank()) {
                    videoUrls.add(item.toString().trim());
                }
            }
        } else if (videoUrlsObj instanceof String str && !str.isBlank()) {
            videoUrls = Arrays.stream(str.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }

        if (orderNo == null || orderNo.isBlank()) {
            log.warn("jiangyi replenishmentHistoryVideo missing orderNo deviceId={} body={}", deviceId, body);
            return Map.of("status", 400, "msg", "missing required fields", "data", "");
        }

        try {
            tradeInternalClient.orderVideoReport(deviceId, orderNo, serialNum, videoQuantity, videoUrls);
            log.info("jiangyi replenishment video report deviceId={} orderNo={} serial={}/{} urls={}",
                    deviceId, orderNo, serialNum, videoQuantity, videoUrls.size());
        } catch (Exception e) {
            log.error("jiangyi replenishment video report forward failed deviceId={} orderNo={} serial={}: {}",
                    deviceId, orderNo, serialNum, e.getMessage());
        }
        return Map.of("status", 200, "msg", "success", "data", "");
    }

    /** 宽容解析（铁律：固件不可信）：缺失/非正整数一律回 1，不让垃圾报文把上报打成 500。 */
    private static int safeInt(Object raw) {
        if (raw == null) {
            return 1;
        }
        try {
            int v = Integer.parseInt(raw.toString().trim());
            return v >= 1 ? v : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static String attr(HttpServletRequest request, String name) {
        Object v = request.getAttribute(name);
        return v == null ? "unknown" : v.toString();
    }
}
