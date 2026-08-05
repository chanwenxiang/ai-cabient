package com.aicabinet.trade.api;

import com.aicabinet.trade.config.QrProperties;
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

    private final DeviceQrService deviceQrService;
    private final QrProperties qrProperties;
    private final WeChatMiniAppClient weChatMiniAppClient;

    public CabinetOpenLandingController(DeviceQrService deviceQrService,
                                        QrProperties qrProperties,
                                        WeChatMiniAppClient weChatMiniAppClient) {
        this.deviceQrService = deviceQrService;
        this.qrProperties = qrProperties;
        this.weChatMiniAppClient = weChatMiniAppClient;
    }

    @GetMapping(value = "/o/{deviceId}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> openLanding(
            @PathVariable("deviceId") String deviceId,
            HttpServletRequest request) {
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
            return "WECHAT";
        }
        return "ALIPAY";
    }

    String resolveTargetUrl(String deviceId, String channel) {
        if ("WECHAT".equals(channel)) {
            String query = "deviceId=" + deviceId + "&channel=WECHAT&autoOpen=1";
            return weChatMiniAppClient
                    .generateUrlLink(qrProperties.wechatPath(), query, qrProperties.wechatEnvVersion())
                    .orElseGet(() -> buildH5OpenUrl(deviceId, "WECHAT"));
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

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
