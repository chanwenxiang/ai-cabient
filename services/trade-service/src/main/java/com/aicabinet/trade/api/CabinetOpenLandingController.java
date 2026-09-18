package com.aicabinet.trade.api;
import com.aicabinet.common.constants.CabinetConstants;

import com.aicabinet.trade.config.QrProperties;
import com.aicabinet.trade.service.ApiRateLimitService;
import com.aicabinet.trade.service.DeviceQrService;
import com.aicabinet.trade.wechat.WeChatMiniAppClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 柜门一码两用：微信优先 URL Link 进小程序，否则与支付宝一并进消费者 H5。
 */
@RestController
public class CabinetOpenLandingController {

    /** L04：公开落地页按来源 IP 限流，防止用 NOT_FOUND 差异无限探测柜机编号。 */
    private static final int OPEN_LANDING_MAX_PER_MINUTE_PER_IP = 20;
    private static final String OPEN_LANDING_RATE_MESSAGE = "请求过于频繁，请稍后再试";

    private final DeviceQrService deviceQrService;
    private final QrProperties qrProperties;
    private final WeChatMiniAppClient weChatMiniAppClient;
    private final ApiRateLimitService rateLimitService;

    public CabinetOpenLandingController(DeviceQrService deviceQrService,
                                        QrProperties qrProperties,
                                        WeChatMiniAppClient weChatMiniAppClient,
                                        ApiRateLimitService rateLimitService) {
        this.deviceQrService = deviceQrService;
        this.qrProperties = qrProperties;
        this.weChatMiniAppClient = weChatMiniAppClient;
        this.rateLimitService = rateLimitService;
    }

    @GetMapping(value = "/o/{deviceId}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> openLanding(
            @PathVariable("deviceId") String deviceId,
            HttpServletRequest request) {
        rateLimitService.assertIpAllowed(ApiRateLimitService.ACTION_OPEN_LANDING,
                resolveClientIp(request), OPEN_LANDING_MAX_PER_MINUTE_PER_IP, OPEN_LANDING_RATE_MESSAGE);
        String id;
        try {
            id = deviceQrService.requireDevice(deviceId).getDeviceId();
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND || e.getStatusCode() == HttpStatus.BAD_REQUEST) {
                return ResponseEntity.status(e.getStatusCode())
                        .contentType(MediaType.TEXT_HTML)
                        .body(simplePage("柜机不可用", e.getReason() != null ? e.getReason() : "柜机不存在或编号无效"));
            }
            throw e;
        }

        String channel = resolveChannel(request.getHeader("User-Agent"));
        if ("OTHER".equals(channel)) {
            String openUrl = deviceQrService.buildOpenUrl(id);
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .body(guidePage(id, openUrl));
        }
        String target = resolveTargetUrl(id, channel);
        return ResponseEntity.status(HttpStatus.FOUND)
                .header("Location", target)
                .build();
    }

    public static String resolveChannel(String userAgent) {
        String ua = userAgent == null ? "" : userAgent;
        if (ua.contains("AlipayClient") || ua.contains("Alipay")) {
            return "ALIPAY";
        }
        if (ua.contains("MicroMessenger")) {
            return CabinetConstants.PAY_CHANNEL_WECHAT;
        }
        // 桌面/其它浏览器：展示引导页，避免误跳到本机 H5 开发端口
        return "OTHER";
    }

    /** L04：取来源 IP，优先代理头第一跳（与限流口径一致，不可伪造多段绕过）。 */
    private static String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
            if (!first.isBlank()) {
                return first;
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    String resolveTargetUrl(String deviceId, String channel) {
        if (CabinetConstants.PAY_CHANNEL_WECHAT.equals(channel)) {
            String query = "deviceId=" + deviceId + "&channel=WECHAT&autoOpen=1";
            return weChatMiniAppClient
                    .generateUrlLink(qrProperties.wechatPath(), query, qrProperties.wechatEnvVersion())
                    .orElseGet(() -> buildH5OpenUrl(deviceId, CabinetConstants.PAY_CHANNEL_WECHAT));
        }
        return buildH5OpenUrl(deviceId, channel);
    }

    private String buildH5OpenUrl(String deviceId, String channel) {
        String base = qrProperties.normalizedConsumerH5Base();
        String q = "deviceId=" + enc(deviceId) + "&channel=" + enc(channel) + "&autoOpen=1";
        if (base.contains("#")) {
            return base.endsWith("/") || base.endsWith("#")
                    ? base + "pages/index/index?" + q
                    : base + "/pages/index/index?" + q;
        }
        return base + "#/pages/index/index?" + q;
    }

    private static String simplePage(String title, String message) {
        return """
                <!DOCTYPE html><html lang="zh-CN"><head>
                <meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1"/>
                <title>%s</title>
                <style>body{font-family:system-ui,sans-serif;padding:48px 24px;text-align:center;color:#333}
                h1{font-size:20px}p{color:#666}</style></head>
                <body><h1>%s</h1><p>%s</p></body></html>
                """.formatted(
                HtmlUtils.htmlEscape(title),
                HtmlUtils.htmlEscape(title),
                HtmlUtils.htmlEscape(message));
    }

    private static String guidePage(String deviceId, String openUrl) {
        String safeId = HtmlUtils.htmlEscape(deviceId);
        String safeUrl = HtmlUtils.htmlEscape(openUrl == null ? "" : openUrl);
        return """
                <!DOCTYPE html><html lang="zh-CN"><head>
                <meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1"/>
                <title>扫码开门 · %s</title>
                <style>
                body{font-family:system-ui,sans-serif;margin:0;padding:48px 24px;background:#0b1220;color:#e8eef7;text-align:center}
                .card{max-width:420px;margin:0 auto;padding:28px 22px;border-radius:16px;background:#121a2b;border:1px solid #243049}
                h1{font-size:22px;margin:0 0 12px}p{color:#9db0c9;line-height:1.6;margin:8px 0}
                code{display:block;margin-top:16px;padding:10px;border-radius:8px;background:#0b1220;color:#7dd3c7;font-size:12px;word-break:break-all}
                </style></head>
                <body><div class="card">
                <h1>请用手机扫柜机码</h1>
                <p>支付宝扫码进入消费页；微信扫码进入小程序（未配置时回落 H5）。</p>
                <p>当前柜机 <strong>%s</strong></p>
                <code>%s</code>
                </div></body></html>
                """.formatted(safeId, safeId, safeUrl);
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
