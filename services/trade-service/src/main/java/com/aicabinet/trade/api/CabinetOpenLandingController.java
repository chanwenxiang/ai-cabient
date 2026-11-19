package com.aicabinet.trade.api;
import com.aicabinet.common.constants.CabinetConstants;

import com.aicabinet.trade.config.AlipayMiniProperties;
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
import com.aicabinet.trade.wechat.MiniAppTarget;

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
    private final AlipayMiniProperties alipayMiniProperties;

    public CabinetOpenLandingController(DeviceQrService deviceQrService,
                                        QrProperties qrProperties,
                                        WeChatMiniAppClient weChatMiniAppClient,
                                        ApiRateLimitService rateLimitService,
                                        AlipayMiniProperties alipayMiniProperties) {
        this.deviceQrService = deviceQrService;
        this.qrProperties = qrProperties;
        this.weChatMiniAppClient = weChatMiniAppClient;
        this.rateLimitService = rateLimitService;
        this.alipayMiniProperties = alipayMiniProperties;
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
        // 支付宝支：小程序上线后优先拉起小程序（一码两用）；未配小程序应用时才看 H5 落点。
        // 🔴 顺序不能反：H5 只是过渡形态，先跳 H5 会把支付宝用户挡在小程序门外。
        if (CabinetConstants.PAY_CHANNEL_ALIPAY.equals(channel)) {
            if (alipayMiniProperties.isConfigured()) {
                return ResponseEntity.ok()
                        .contentType(MediaType.TEXT_HTML)
                        .body(alipayMiniOpenPage(id, alipayMiniProperties.appId()));
            }
            if (!qrProperties.hasConsumerH5()) {
                return ResponseEntity.ok()
                        .contentType(MediaType.TEXT_HTML)
                        .body(alipayUnavailablePage(id));
            }
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

    /**
     * L04：取来源 IP。信任序（R14-G1 信任序倒置）：X-Real-IP 优先——本仓 nginx
     * 恒定覆写 {@code proxy_set_header X-Real-IP $remote_addr}，客户端无法伪造；
     * X-Forwarded-For 是追加型（$proxy_add_x_forwarded_for），首跳可被客户端
     * 自选，仅作「未过反代的直连」回退。威胁边界：X-Real-IP 可信的前提是部署
     * 拓扑中该头只由反代写入——生产必须经 nginx（直连暴露 18080 时 X-Real-IP
     * 同样可伪造，此时限流退化为 best-effort，不应作为唯一防线）。
     */
    // 包级可见：CabinetOpenLandingClientIpTest 钉取值顺序（R14-G1）
    static String resolveClientIp(HttpServletRequest request) {
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
            if (!first.isBlank()) {
                return first;
            }
        }
        return request.getRemoteAddr();
    }

    String resolveTargetUrl(String deviceId, String channel) {
        if (CabinetConstants.PAY_CHANNEL_WECHAT.equals(channel)) {
            String query = "deviceId=" + deviceId + "&channel=WECHAT&autoOpen=1";
            return weChatMiniAppClient
                    .generateUrlLink(qrProperties.wechatPath(), query, qrProperties.wechatEnvVersion(),
                            MiniAppTarget.CONSUMER)
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

    /**
     * 支付宝内打开柜门码的承接页：拉起支付宝小程序并带 deviceId。
     *
     * <p>为什么不是 302：{@code alipays://} 是支付宝客户端的私有 scheme，HTTP 302 到它
     * 在部分机型/WebView 上会被直接丢弃（用户看到「无法打开网页」），而且失败后没有任何
     * 补救说明。渲染一页再跳，失败时还能给出「在支付宝首页搜索小程序」的兜底。
     *
     * <p>🔴 更优路径是开放平台的「扫普通链接二维码打开小程序」：同一张柜门码由支付宝
     * 识别后**直接**进小程序，不经过本页。本页是未配该规则/规则未命中时的兜底。
     */
    private static String alipayMiniOpenPage(String deviceId, String appId) {
        String safeId = HtmlUtils.htmlEscape(deviceId);
        // query 里带 channel=ALIPAY：ShoppingSession.entryChannel 据此记账（与微信侧同源）
        String query = enc("deviceId=" + deviceId + "&channel=ALIPAY&autoOpen=1");
        String scheme = "alipays://platformapi/startapp?appId=" + enc(appId)
                + "&page=" + enc("pages/index/index")
                + "&query=" + query;
        String safeScheme = HtmlUtils.htmlEscape(scheme);
        return """
                <!DOCTYPE html><html lang="zh-CN"><head>
                <meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1"/>
                <title>正在打开易购智能柜 · %s</title>
                <style>
                body{font-family:system-ui,sans-serif;margin:0;padding:48px 24px;background:#0b1220;color:#e8eef7;text-align:center}
                .card{max-width:420px;margin:0 auto;padding:28px 22px;border-radius:16px;background:#121a2b;border:1px solid #243049}
                h1{font-size:22px;margin:0 0 12px}p{color:#9db0c9;line-height:1.6;margin:8px 0}
                .tip{display:none;margin-top:16px;padding:12px;border-radius:8px;background:#0b1220;color:#7dd3c7;font-size:13px}
                a{color:#7dd3c7}
                </style></head>
                <body><div class="card">
                <h1>正在打开易购智能柜</h1>
                <p>当前柜机 <strong>%s</strong></p>
                <p id="wait">正在唤起支付宝小程序…</p>
                <div class="tip" id="tip">
                  <p>若未自动打开，请在支付宝首页搜索「易购智能柜」小程序后重新扫码。</p>
                  <p><a id="retry" href="%s">点此重试</a></p>
                </div>
                </div>
                <script>
                (function(){
                  // 从 href 读取而不是把 scheme 拼进 JS 字符串：deviceId 来自路径参数，
                  // 直接内插会因引号/反斜杠破坏脚本（或变成注入点）。
                  var target = document.getElementById('retry').href;
                  try { location.replace(target); } catch (e) {}
                  setTimeout(function(){
                    document.getElementById('wait').style.display = 'none';
                    document.getElementById('tip').style.display = 'block';
                  }, 1800);
                })();
                </script>
                </body></html>
                """.formatted(safeId, safeId, safeScheme);
    }

    /** 支付宝支无落点时的引导页：比 302 到死端口诚实（用户至少知道该怎么做）。 */
    private static String alipayUnavailablePage(String deviceId) {
        String safeId = HtmlUtils.htmlEscape(deviceId);
        return """
                <!DOCTYPE html><html lang="zh-CN"><head>
                <meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1"/>
                <title>请用微信扫码 · %s</title>
                <style>
                body{font-family:system-ui,sans-serif;margin:0;padding:48px 24px;background:#0b1220;color:#e8eef7;text-align:center}
                .card{max-width:420px;margin:0 auto;padding:28px 22px;border-radius:16px;background:#121a2b;border:1px solid #243049}
                h1{font-size:22px;margin:0 0 12px}p{color:#9db0c9;line-height:1.6;margin:8px 0}
                code{display:block;margin-top:16px;padding:10px;border-radius:8px;background:#0b1220;color:#7dd3c7;font-size:12px;word-break:break-all}
                </style></head>
                <body><div class="card">
                <h1>请用微信扫码</h1>
                <p>本柜暂未开通支付宝入口，微信扫一扫可直接开门取货。</p>
                <p>当前柜机 <strong>%s</strong></p>
                </div></body></html>
                """.formatted(safeId, safeId);
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
