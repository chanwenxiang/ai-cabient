package com.aicabinet.trade.api;

import com.aicabinet.trade.service.WeChatOfficialAccountBindingService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v2/public/wechat-official-account")
public class WeChatOfficialAccountOAuthController {

    private static final MediaType HTML = new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);

    private final WeChatOfficialAccountBindingService bindingService;

    public WeChatOfficialAccountOAuthController(WeChatOfficialAccountBindingService bindingService) {
        this.bindingService = bindingService;
    }

    @GetMapping("/callback")
    public ResponseEntity<String> callback(@RequestParam(required = false) String code,
                                           @RequestParam(required = false) String state) {
        try {
            bindingService.complete(code, state);
            return html(true, "服务号通知已绑定，请返回小程序查看。");
        } catch (ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .contentType(HTML)
                    .cacheControl(CacheControl.noStore())
                    .header("X-Content-Type-Options", "nosniff")
                    .body(page(false, e.getReason() == null ? "绑定失败，请重试" : e.getReason()));
        } catch (RuntimeException e) {
            return ResponseEntity.internalServerError()
                    .contentType(HTML)
                    .cacheControl(CacheControl.noStore())
                    .header("X-Content-Type-Options", "nosniff")
                    .body(page(false, "绑定失败，请稍后重试。"));
        }
    }

    private static ResponseEntity<String> html(boolean success, String message) {
        return ResponseEntity.ok()
                .contentType(HTML)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(page(success, message));
    }

    private static String page(boolean success, String message) {
        String color = success ? "#0f766e" : "#b91c1c";
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>服务号通知绑定</title></head>"
                + "<body style=\"margin:0;background:#f5f5f5;font-family:sans-serif;color:#111827\">"
                + "<main style=\"max-width:420px;margin:80px auto;padding:28px;background:#fff;border-radius:12px\">"
                + "<h1 style=\"font-size:22px;margin:0 0 16px;color:" + color + "\">"
                + (success ? "绑定成功" : "绑定未完成") + "</h1>"
                + "<p style=\"font-size:16px;line-height:1.7;margin:0\">" + HtmlUtils.htmlEscape(message) + "</p>"
                + "</main></body></html>";
    }
}
