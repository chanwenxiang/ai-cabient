package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.NotificationDto;
import com.aicabinet.common.dto.NotifyPrefDto;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.service.ConsumerNotifyPrefService;
import com.aicabinet.trade.service.NotificationService;
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

    public ConsumerNotificationController(NotificationService notificationService,
                                          ConsumerNotifyPrefService notifyPrefService) {
        this.notificationService = notificationService;
        this.notifyPrefService = notifyPrefService;
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

    private static Long userId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
