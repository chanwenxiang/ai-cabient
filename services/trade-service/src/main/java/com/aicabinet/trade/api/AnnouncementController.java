package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
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

    @RequiresPermissions("ops:announcement:list")
    @GetMapping
    public ApiResponse<List<Announcement>> list() {
        return ApiResponse.ok(announcementService.listAll());
    }

    @GetMapping("/published")
    public ApiResponse<List<Announcement>> published() {
        return ApiResponse.ok(announcementService.listPublished());
    }

    @RequiresPermissions(value = {"ops:announcement:create", "ops:announcement:import"}, logical = RequiresPermissions.Logical.OR)
    @PostMapping
    public ApiResponse<Announcement> create(
            HttpServletRequest request,
            @RequestBody Map<String, String> body) {
        Long opId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(announcementService.create(
                body.get("title"), body.get("content"),
                body.get("targetScope"), body.get("priority"), opId));
    }

    @RequiresPermissions("ops:announcement:edit")
    @PutMapping("/{id}")
    public ApiResponse<Announcement> update(
            @PathVariable("id") Long id,
            @RequestBody Map<String, String> body) {
        return ApiResponse.ok(announcementService.update(
                id,
                body.get("title"), body.get("content"),
                body.get("targetScope"), body.get("priority")));
    }

    @RequiresPermissions("ops:announcement:publish")
    @PostMapping("/{id}/publish")
    public ApiResponse<Announcement> publish(@PathVariable("id") Long id) {
        return ApiResponse.ok(announcementService.publish(id));
    }

    @RequiresPermissions("ops:announcement:edit")
    @PostMapping("/{id}/archive")
    public ApiResponse<Announcement> archive(@PathVariable("id") Long id) {
        return ApiResponse.ok(announcementService.archive(id));
    }
}
