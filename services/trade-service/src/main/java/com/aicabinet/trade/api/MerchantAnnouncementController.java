package com.aicabinet.trade.api;

import com.aicabinet.common.dto.AnnouncementDto;
import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.trade.service.AnnouncementService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Merchant portal announcements (published MERCHANT + ALL). Auth via /api/v2/merchant/**.
 */
@RestController
@RequestMapping("/api/v2/merchant/announcements")
public class MerchantAnnouncementController {

    private final AnnouncementService announcementService;

    public MerchantAnnouncementController(AnnouncementService announcementService) {
        this.announcementService = announcementService;
    }

    @GetMapping
    public ApiResponse<List<AnnouncementDto>> list() {
        return ApiResponse.ok(announcementService.listPublishedForAudience("MERCHANT"));
    }

    @GetMapping("/{id}")
    public ApiResponse<AnnouncementDto> detail(@PathVariable("id") Long id) {
        return ApiResponse.ok(announcementService.getPublishedForAudience(id, "MERCHANT"));
    }
}
