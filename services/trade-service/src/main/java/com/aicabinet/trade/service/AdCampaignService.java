package com.aicabinet.trade.service;

import com.aicabinet.common.dto.AdCampaignDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.ScreenContentDto;
import com.aicabinet.common.dto.ScreenContentItemDto;
import com.aicabinet.common.dto.UpsertAdCampaignRequest;
import com.aicabinet.trade.domain.AdCampaign;
import com.aicabinet.trade.domain.AdCampaignDevice;
import com.aicabinet.trade.domain.AdCampaignItem;
import com.aicabinet.trade.domain.AdPlayEvent;
import com.aicabinet.trade.domain.MediaAsset;
import com.aicabinet.trade.mapper.AdCampaignDeviceMapper;
import com.aicabinet.trade.mapper.AdCampaignItemMapper;
import com.aicabinet.trade.mapper.AdCampaignMapper;
import com.aicabinet.trade.mapper.AdPlayEventMapper;
import com.aicabinet.trade.mapper.MediaAssetMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 广告/多媒体投放计划：DRAFT/RUNNING/STOPPED 生命周期，设备屏按当前生效计划拉取轮播内容。
 */
@Service
public class AdCampaignService {
    private static final String AD_CAMPAIGN = "AD_CAMPAIGN";
    private static final String SPECIFIC = "SPECIFIC";
    private static final String LITERAL = "投放计划不存在";
    private static final String NAME = "name=";


    private final AdCampaignMapper campaignRepository;
    private final AdCampaignItemMapper itemRepository;
    private final AdCampaignDeviceMapper deviceRepository;
    private final MediaAssetMapper assetRepository;
    private final AdminAuditService auditService;
    private final AdPlayEventMapper playEventRepository;
    private final DistributedLockService distributedLockService;

    public AdCampaignService(AdCampaignMapper campaignRepository,
                             AdCampaignItemMapper itemRepository,
                             AdCampaignDeviceMapper deviceRepository,
                             MediaAssetMapper assetRepository,
                             AdminAuditService auditService,
                             AdPlayEventMapper playEventRepository,
                             DistributedLockService distributedLockService) {
        this.campaignRepository = campaignRepository;
        this.itemRepository = itemRepository;
        this.deviceRepository = deviceRepository;
        this.assetRepository = assetRepository;
        this.auditService = auditService;
        this.playEventRepository = playEventRepository;
        this.distributedLockService = distributedLockService;
    }

    @Transactional(readOnly = true)
    public PageResult<AdCampaignDto> list(int page, int size) {
        int p = Math.max(0, page);
        int s = Math.min(Math.max(1, size), 500);
        Page<AdCampaign> result = campaignRepository.searchPage(p, s);
        List<AdCampaignDto> items = result.getRecords().stream().map(this::toDto).toList();
        return new PageResult<>(items, p, s, result.getTotal());
    }

    /** 兼容旧调用：全量列表。 */
    @Transactional(readOnly = true)
    public List<AdCampaignDto> list() {
        return list(0, 500).items();
    }

    @Transactional(readOnly = true)
    public AdCampaignDto get(Long campaignId) {
        return toDto(requireCampaign(campaignId));
    }

    @Transactional
    public AdCampaignDto upsert(Long operatorId, Long campaignId, UpsertAdCampaignRequest request) {
        if (campaignId == null) {
            return doUpsert(operatorId, null, request);
        }
        return runWithCampaignLock(campaignId, () -> doUpsert(operatorId, campaignId, request));
    }

    private AdCampaignDto doUpsert(Long operatorId, Long campaignId, UpsertAdCampaignRequest request) {
        if (request.assetIds() == null || request.assetIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "投放计划至少需要一个素材");
        }
        String scope = request.deviceScope() == null || request.deviceScope().isBlank()
                ? "ALL" : request.deviceScope().trim().toUpperCase();
        if (scope.equals(SPECIFIC)
                && (request.deviceIds() == null || request.deviceIds().isEmpty())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "指定设备投放需要选择设备");
        }
        for (Long assetId : request.assetIds()) {
            assetRepository.findById(assetId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "素材不存在: " + assetId));
        }

        AdCampaign campaign;
        if (campaignId == null) {
            campaign = new AdCampaign();
            campaign.setName(request.name().trim());
            campaign.setStatus("DRAFT");
            campaign.setDeviceScope(scope);
            campaign.setStartAt(request.startAt());
            campaign.setEndAt(request.endAt());
            campaign.setCreatedBy(operatorId);
            campaign.setCreatedAt(Instant.now());
            campaign.setUpdatedAt(Instant.now());
            campaignRepository.insert(campaign);
        } else {
            campaign = campaignRepository.findByIdForUpdate(campaignId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, LITERAL));
            campaign.setName(request.name().trim());
            campaign.setDeviceScope(scope);
            campaign.setStartAt(request.startAt());
            campaign.setEndAt(request.endAt());
            campaign.setUpdatedAt(Instant.now());
            campaignRepository.updateById(campaign);
        }
        replaceItems(campaign.getCampaignId(), request.assetIds());
        replaceDevices(campaign.getCampaignId(), scope, request.deviceIds());
        auditService.appendLog(operatorId, "AD_CAMPAIGN_UPSERT", AD_CAMPAIGN,
                String.valueOf(campaign.getCampaignId()), NAME + campaign.getName());
        return toDto(campaign);
    }

    @Transactional
    public AdCampaignDto launch(Long operatorId, Long campaignId) {
        return runWithCampaignLock(campaignId, () -> {
            AdCampaign campaign = requireCampaignForUpdate(campaignId);
            if (itemRepository.findByCampaignId(campaignId).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "投放计划没有素材，无法上线");
            }
            campaign.setStatus("RUNNING");
            campaign.setUpdatedAt(Instant.now());
            campaignRepository.updateById(campaign);
            auditService.appendLog(operatorId, "AD_CAMPAIGN_LAUNCH", AD_CAMPAIGN,
                    String.valueOf(campaignId), NAME + campaign.getName());
            return toDto(campaign);
        });
    }

    @Transactional
    public AdCampaignDto stop(Long operatorId, Long campaignId) {
        return runWithCampaignLock(campaignId, () -> {
            AdCampaign campaign = requireCampaignForUpdate(campaignId);
            campaign.setStatus("STOPPED");
            campaign.setUpdatedAt(Instant.now());
            campaignRepository.updateById(campaign);
            auditService.appendLog(operatorId, "AD_CAMPAIGN_STOP", AD_CAMPAIGN,
                    String.valueOf(campaignId), NAME + campaign.getName());
            return toDto(campaign);
        });
    }

    @Transactional
    public void delete(Long operatorId, Long campaignId) {
        runWithCampaignLock(campaignId, () -> {
            AdCampaign campaign = requireCampaignForUpdate(campaignId);
            if ("RUNNING".equalsIgnoreCase(campaign.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "投放中的计划请先停止再删除");
            }
            itemRepository.deleteByCampaignId(campaignId);
            deviceRepository.deleteByCampaignId(campaignId);
            campaignRepository.deleteById(campaignId);
            auditService.appendLog(operatorId, "AD_CAMPAIGN_DELETE", AD_CAMPAIGN,
                    String.valueOf(campaignId), NAME + campaign.getName());
            return null;
        });
    }

    /** 设备屏内容（内部接口）：取当前时间窗口内 RUNNING 的投放计划（全部设备或包含该设备），返回轮播素材。 */
    @Transactional(readOnly = true)
    public ScreenContentDto screenContent(String deviceId) {
        Instant now = Instant.now();
        for (AdCampaign campaign : campaignRepository.findRunningInWindow(now)) {
            if (SPECIFIC.equals(campaign.getDeviceScope())
                    && deviceRepository.findByCampaignId(campaign.getCampaignId()).stream()
                    .noneMatch(d -> d.getDeviceId().equalsIgnoreCase(deviceId))) {
                continue;
            }
            List<ScreenContentItemDto> items = new ArrayList<>();
            for (AdCampaignItem item : itemRepository.findByCampaignId(campaign.getCampaignId())) {
                MediaAsset asset = assetRepository.findById(item.getAssetId()).orElse(null);
                if (asset == null || !"ACTIVE".equals(asset.getStatus())) {
                    continue;
                }
                items.add(new ScreenContentItemDto(asset.getAssetId(), asset.getTitle(),
                        asset.getAssetType(), asset.getStorageUri(), asset.getDurationSeconds()));
            }
            if (!items.isEmpty()) {
                return new ScreenContentDto(campaign.getCampaignId(), campaign.getName(), items);
            }
        }
        return new ScreenContentDto(null, null, List.of());
    }

    /** 设备屏回写曝光/完播，供投放 ROI 留痕。 */
    @Transactional
    public void recordPlayEvent(String deviceId, Long campaignId, Long assetId, String eventType) {
        if (deviceId == null || deviceId.isBlank() || campaignId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "campaignId/deviceId 必填");
        }
        String type = eventType == null ? "" : eventType.trim().toUpperCase();
        if (!type.equals("IMPRESSION") && !type.equals("COMPLETE") && !type.equals("CLICK")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "eventType 仅支持 IMPRESSION/COMPLETE/CLICK");
        }
        requireCampaign(campaignId);
        AdPlayEvent ev = new AdPlayEvent();
        ev.setCampaignId(campaignId);
        ev.setDeviceId(deviceId.trim().toUpperCase());
        ev.setAssetId(assetId);
        ev.setEventType(type);
        ev.setCreatedAt(Instant.now());
        playEventRepository.insert(ev);
    }

    private void replaceItems(Long campaignId, List<Long> assetIds) {
        itemRepository.deleteByCampaignId(campaignId);
        int order = 0;
        for (Long assetId : assetIds) {
            AdCampaignItem item = new AdCampaignItem();
            item.setCampaignId(campaignId);
            item.setAssetId(assetId);
            item.setSortOrder(order++);
            itemRepository.insert(item);
        }
    }

    private void replaceDevices(Long campaignId, String scope, List<String> deviceIds) {
        deviceRepository.deleteByCampaignId(campaignId);
        if (!scope.equals(SPECIFIC) || deviceIds == null) {
            return;
        }
        for (String deviceId : deviceIds) {
            if (deviceId == null || deviceId.isBlank()) {
                continue;
            }
            AdCampaignDevice row = new AdCampaignDevice();
            row.setCampaignId(campaignId);
            row.setDeviceId(deviceId.trim().toUpperCase());
            deviceRepository.insert(row);
        }
    }

    private AdCampaignDto toDto(AdCampaign campaign) {
        List<Long> assetIds = itemRepository.findByCampaignId(campaign.getCampaignId()).stream()
                .map(AdCampaignItem::getAssetId).toList();
        List<String> deviceIds = deviceRepository.findByCampaignId(campaign.getCampaignId()).stream()
                .map(AdCampaignDevice::getDeviceId).toList();
        return new AdCampaignDto(
                campaign.getCampaignId(), campaign.getName(), campaign.getStatus(),
                campaign.getDeviceScope(), campaign.getStartAt(), campaign.getEndAt(),
                assetIds, deviceIds, campaign.getCreatedAt(), campaign.getUpdatedAt(),
                playEventRepository.countByCampaignAndType(campaign.getCampaignId(), "IMPRESSION"),
                playEventRepository.countByCampaignAndType(campaign.getCampaignId(), "COMPLETE"));
    }

    private AdCampaign requireCampaign(Long campaignId) {
        return campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, LITERAL));
    }

    private AdCampaign requireCampaignForUpdate(Long campaignId) {
        return campaignRepository.findByIdForUpdate(campaignId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, LITERAL));
    }

    static String campaignLockKey(Long campaignId) {
        return "ad:campaign:" + campaignId;
    }

    private <T> T runWithCampaignLock(Long campaignId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(campaignLockKey(campaignId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "广告投放计划处理中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            distributedLockService.unlock(campaignLockKey(campaignId));
        }
    }
}
