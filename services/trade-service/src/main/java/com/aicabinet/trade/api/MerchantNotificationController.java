package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.MerchantMeDto;
import com.aicabinet.common.dto.NotificationDto;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.MerchantPortalService;
import com.aicabinet.trade.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/merchant/notifications")
public class MerchantNotificationController {

    private final NotificationService notificationService;
    private final MerchantPortalService merchantPortalService;

    public MerchantNotificationController(NotificationService notificationService,
                                          MerchantPortalService merchantPortalService) {
        this.notificationService = notificationService;
        this.merchantPortalService = merchantPortalService;
    }

    @RequiresPermissions("merchant:portal:access")
    @GetMapping
    public ApiResponse<List<NotificationDto>> list(
            HttpServletRequest request,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(notificationService.merchantNotifications(merchantId(request), limit));
    }

    @RequiresPermissions("merchant:portal:access")
    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Long>> unreadCount(HttpServletRequest request) {
        return ApiResponse.ok(Map.of("count", notificationService.merchantUnreadCount(merchantId(request))));
    }

    @RequiresPermissions("merchant:portal:access")
    @PostMapping("/{id}/read")
    public ApiResponse<Void> markRead(HttpServletRequest request, @PathVariable Long id) {
        notificationService.markMerchantRead(merchantId(request), id);
        return ApiResponse.ok(null);
    }

    private String merchantId(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        MerchantMeDto me = merchantPortalService.getMe(userId);
        if (me == null || me.merchants() == null || me.merchants().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "未绑定商户");
        }
        return me.merchants().get(0).merchantId();
    }
}
