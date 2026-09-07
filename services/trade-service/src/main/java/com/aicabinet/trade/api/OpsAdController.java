package com.aicabinet.trade.api;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.AdCampaignService;
import com.aicabinet.trade.service.MediaAssetService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 广告素材与投放计划（从 {@link OpsCommercialController} 按域抽出；URL 不变）。
 */
@RestController
@RequestMapping("/api/v2/ops/admin")
public class OpsAdController {

    private final MediaAssetService mediaAssetService;
    private final AdCampaignService adCampaignService;

    public OpsAdController(MediaAssetService mediaAssetService, AdCampaignService adCampaignService) {
        this.mediaAssetService = mediaAssetService;
        this.adCampaignService = adCampaignService;
    }

    // --- 广告/多媒体运营：素材库 + 投放计划（读写沿用设备权限码，避免新建角色权限） ---
    @RequiresPermissions("ops:ad:list")
    @GetMapping("/ad/assets")
    public ApiResponse<PageResult<MediaAssetDto>> adAssets(
            HttpServletRequest request,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(mediaAssetService.list(page, size));
    }

    @RequiresPermissions("ops:ad:edit")
    @PostMapping(value = "/ad/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<MediaAssetDto> uploadAdAsset(
            HttpServletRequest request,
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "title", required = false) String title,
            @RequestParam(name = "durationSeconds", defaultValue = "0") int durationSeconds,
            @RequestParam(name = "assetType", required = false) String assetType) {
        return ApiResponse.ok(mediaAssetService.upload(
                operatorId(request), file, title, durationSeconds, assetType));
    }

    @RequiresPermissions("ops:ad:edit")
    @PutMapping("/ad/assets/{assetId}")
    public ApiResponse<MediaAssetDto> updateAdAsset(
            HttpServletRequest request,
            @PathVariable Long assetId,
            @Valid @RequestBody UpsertMediaAssetRequest body) {
        return ApiResponse.ok(mediaAssetService.update(assetId, body));
    }

    @RequiresPermissions("ops:ad:edit")
    @DeleteMapping("/ad/assets/{assetId}")
    public ApiResponse<Void> deleteAdAsset(
            HttpServletRequest request, @PathVariable Long assetId) {
        mediaAssetService.delete(assetId);
        return ApiResponse.ok(null);
    }

    @RequiresPermissions("ops:ad:list")
    @GetMapping("/ad/campaigns")
    public ApiResponse<PageResult<AdCampaignDto>> adCampaigns(
            HttpServletRequest request,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(adCampaignService.list(page, size));
    }

    @RequiresPermissions("ops:ad:list")
    @GetMapping("/ad/campaigns/{campaignId}")
    public ApiResponse<AdCampaignDto> adCampaign(
            HttpServletRequest request, @PathVariable Long campaignId) {
        return ApiResponse.ok(adCampaignService.get(campaignId));
    }

    @RequiresPermissions("ops:ad:edit")
    @PostMapping("/ad/campaigns")
    public ApiResponse<AdCampaignDto> createAdCampaign(
            HttpServletRequest request,
            @Valid @RequestBody UpsertAdCampaignRequest body) {
        return ApiResponse.ok(adCampaignService.upsert(operatorId(request), null, body));
    }

    @RequiresPermissions("ops:ad:edit")
    @PutMapping("/ad/campaigns/{campaignId}")
    public ApiResponse<AdCampaignDto> updateAdCampaign(
            HttpServletRequest request,
            @PathVariable Long campaignId,
            @Valid @RequestBody UpsertAdCampaignRequest body) {
        return ApiResponse.ok(adCampaignService.upsert(operatorId(request), campaignId, body));
    }

    @RequiresPermissions("ops:ad:edit")
    @PostMapping("/ad/campaigns/{campaignId}/launch")
    public ApiResponse<AdCampaignDto> launchAdCampaign(
            HttpServletRequest request, @PathVariable Long campaignId) {
        return ApiResponse.ok(adCampaignService.launch(operatorId(request), campaignId));
    }

    @RequiresPermissions("ops:ad:edit")
    @PostMapping("/ad/campaigns/{campaignId}/stop")
    public ApiResponse<AdCampaignDto> stopAdCampaign(
            HttpServletRequest request, @PathVariable Long campaignId) {
        return ApiResponse.ok(adCampaignService.stop(operatorId(request), campaignId));
    }

    @RequiresPermissions("ops:ad:edit")
    @DeleteMapping("/ad/campaigns/{campaignId}")
    public ApiResponse<Void> deleteAdCampaign(
            HttpServletRequest request, @PathVariable Long campaignId) {
        adCampaignService.delete(operatorId(request), campaignId);
        return ApiResponse.ok(null);
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
