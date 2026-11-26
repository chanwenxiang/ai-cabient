package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.NotificationDto;
import com.aicabinet.common.dto.NotifyPrefDto;
import com.aicabinet.common.dto.WeChatOfficialBindTicketDto;
import com.aicabinet.common.dto.WeChatOfficialBindingStatusDto;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.service.AdminAuditService;
import com.aicabinet.trade.service.ConsumerNotifyPrefService;
import com.aicabinet.trade.service.NotificationService;
import com.aicabinet.trade.service.WeChatOfficialAccountBindingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/member/notifications")
public class ConsumerNotificationController {

    private final NotificationService notificationService;
    private final ConsumerNotifyPrefService notifyPrefService;
    private final WeChatOfficialAccountBindingService officialAccountBindingService;
    private final AdminAuditService auditService;

    public ConsumerNotificationController(NotificationService notificationService,
                                          ConsumerNotifyPrefService notifyPrefService,
                                          WeChatOfficialAccountBindingService officialAccountBindingService,
                                          AdminAuditService auditService) {
        this.notificationService = notificationService;
        this.notifyPrefService = notifyPrefService;
        this.officialAccountBindingService = officialAccountBindingService;
        this.auditService = auditService;
    }

    @GetMapping
    public ApiResponse<List<NotificationDto>> list(
            HttpServletRequest request,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(notificationService.consumerNotifications(userId(request), limit));
    }

    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Long>> unreadCount(HttpServletRequest request) {
        return ApiResponse.ok(Map.of("count", notificationService.consumerUnreadCount(userId(request))));
    }

    @PostMapping("/{id}/read")
    public ApiResponse<Void> markRead(HttpServletRequest request, @PathVariable Long id) {
        notificationService.markConsumerRead(userId(request), id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/read-all")
    public ApiResponse<Void> markAllRead(HttpServletRequest request) {
        notificationService.markConsumerAllRead(userId(request));
        return ApiResponse.ok(null);
    }

    @GetMapping("/prefs")
    public ApiResponse<List<NotifyPrefDto>> prefs(HttpServletRequest request) {
        return ApiResponse.ok(notifyPrefService.getPrefs(userId(request)));
    }

    @PutMapping("/prefs")
    public ApiResponse<NotifyPrefDto> updatePref(
            HttpServletRequest request,
            @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {
        String category = body.get("category") == null ? "" : String.valueOf(body.get("category"));
        boolean enabled = !"false".equalsIgnoreCase(String.valueOf(body.getOrDefault("enabled", "true")));
        return ApiResponse.ok(notifyPrefService.update(userId(request), category, enabled));
    }

    // authz: self-service —— 票据只绑定登录态 userId，5 分钟一次性消费，不写业务数据。
    @PostMapping("/official-account/bind-ticket")
    public ApiResponse<WeChatOfficialBindTicketDto> officialAccountBindTicket(HttpServletRequest request) {
        Long userId = userId(request);
        WeChatOfficialBindTicketDto ticket = officialAccountBindingService.createTicket(userId);
        auditService.appendLog(userId, "WECHAT_OFFICIAL_BIND_TICKET", "USER", String.valueOf(userId),
                "申请服务号通知授权票据");
        return ApiResponse.ok(ticket);
    }

    @GetMapping("/official-account/status")
    public ApiResponse<WeChatOfficialBindingStatusDto> officialAccountStatus(HttpServletRequest request) {
        return ApiResponse.ok(officialAccountBindingService.status(userId(request)));
    }

    private static Long userId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
