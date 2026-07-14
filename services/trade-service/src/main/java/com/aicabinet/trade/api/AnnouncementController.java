package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.domain.Announcement;
import com.aicabinet.trade.service.AnnouncementService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/ops/announcements")
public class AnnouncementController {

    private final AnnouncementService announcementService;

    public AnnouncementController(AnnouncementService announcementService) {
        this.announcementService = announcementService;
    }

    @GetMapping
    public ApiResponse<List<Announcement>> list() {
        return ApiResponse.ok(announcementService.listAll());
    }

    @GetMapping("/published")
    public ApiResponse<List<Announcement>> published() {
        return ApiResponse.ok(announcementService.listPublished());
    }

    @PostMapping
    public ApiResponse<Announcement> create(
            HttpServletRequest request,
            @RequestBody Map<String, String> body) {
        Long opId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(announcementService.create(
                body.get("title"), body.get("content"),
                body.get("targetScope"), body.get("priority"), opId));
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<Announcement> publish(@PathVariable("id") Long id) {
        return ApiResponse.ok(announcementService.publish(id));
    }

    @PostMapping("/{id}/archive")
    public ApiResponse<Announcement> archive(@PathVariable("id") Long id) {
        return ApiResponse.ok(announcementService.archive(id));
    }
}
