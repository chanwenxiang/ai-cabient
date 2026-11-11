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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 广告/多媒体投放计划：DRAFT/RUNNING/STOPPED 生命周期，设备屏按当前生效计划拉取轮播内容。
 */
@Service
public class AdCampaignService {
    private static final Logger log = LoggerFactory.getLogger(AdCampaignService.class);
    private static final String AD_CAMPAIGN = "AD_CAMPAIGN";
    private static final String SPECIFIC = "SPECIFIC";
    /** V296/P3-6：投放端=消费者小程序轮播位（与柜机屏投放互不可见） */
    public static final String CHANNEL_MINI_PROGRAM = "MINI_PROGRAM";
    public static final String CHANNEL_CABINET_SCREEN = "CABINET_SCREEN";
    private static final String LITERAL = "投放计划不存在";
    private static final String NAME = "name=";


    private final AdCampaignMapper campaignRepository;
    private final AdCampaignItemMapper itemRepository;
    private final AdCampaignDeviceMapper deviceRepository;
    private final MediaAssetMapper assetRepository;
    private final AdminAuditService auditService;
    private final AdPlayEventMapper playEventRepository;
    private final DistributedLockService distributedLockService;
    private final AdPlayEventDeduplicator playEventDeduplicator;

    public AdCampaignService(AdCampaignMapper campaignRepository,
                             AdCampaignItemMapper itemRepository,
                             AdCampaignDeviceMapper deviceRepository,
                             MediaAssetMapper assetRepository,
                             AdminAuditService auditService,
                             AdPlayEventMapper playEventRepository,
                             DistributedLockService distributedLockService,
                             AdPlayEventDeduplicator playEventDeduplicator) {
        this.campaignRepository = campaignRepository;
        this.itemRepository = itemRepository;
        this.deviceRepository = deviceRepository;
        this.assetRepository = assetRepository;
        this.auditService = auditService;
        this.playEventRepository = playEventRepository;
        this.distributedLockService = distributedLockService;
        this.playEventDeduplicator = playEventDeduplicator;
    }

    @Transactional(readOnly = true)
    public PageResult<AdCampaignDto> list(int page, int size) {
        return list(page, size, null, null);
    }

    /** MA-012：关键词/状态由后端过滤，total 与命中集合一致。 */
    @Transactional(readOnly = true)
    public PageResult<AdCampaignDto> list(int page, int size, String q, String status) {
        int p = Math.max(0, page);
        int s = Math.min(Math.max(1, size), 500);
        Page<AdCampaign> result = campaignRepository.searchPage(p, s, q, status);
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
        // MA-086：时间窗倒挂静默接受=计划永不生效、轮播静默空转（对齐同域 Promotion 校验）
        if (request.startAt() != null && request.endAt() != null
                && !request.endAt().isAfter(request.startAt())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "结束时间需晚于开始时间");
        }
        if (request.assetIds() == null || request.assetIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "投放计划至少需要一个素材");
        }
        String scope = request.deviceScope() == null || request.deviceScope().isBlank()
                ? "ALL" : request.deviceScope().trim().toUpperCase();
        if (scope.equals(SPECIFIC)
                && (request.deviceIds() == null || request.deviceIds().isEmpty())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "指定设备投放需要选择设备");
        }
        String channel = request.channel() == null || request.channel().isBlank()
                ? CHANNEL_CABINET_SCREEN : request.channel().trim().toUpperCase();
        if (!CHANNEL_CABINET_SCREEN.equals(channel) && !CHANNEL_MINI_PROGRAM.equals(channel)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "channel 仅支持 CABINET_SCREEN/MINI_PROGRAM");
        }
        String linkUrl = request.linkUrl() == null || request.linkUrl().isBlank()
                ? null : request.linkUrl().trim();
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
            campaign.setChannel(channel);
            campaign.setLinkUrl(linkUrl);
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
            campaign.setChannel(channel);
            campaign.setLinkUrl(linkUrl);
            campaign.setStartAt(request.startAt());
            campaign.setEndAt(request.endAt());
            campaign.setUpdatedAt(Instant.now());
            campaignRepository.updateById(campaign);
            // R13-MA-174：显式清列——updateById 跳 null，linkUrl 清空后无法落库（M01 家族）。
            if (campaign.getLinkUrl() == null) {
                campaignRepository.update(null, Wrappers.<AdCampaign>lambdaUpdate()
                        .eq(AdCampaign::getCampaignId, campaignId)
                        .set(AdCampaign::getLinkUrl, null));
            }
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
            if (CHANNEL_MINI_PROGRAM.equals(campaign.getChannel())) {
                continue; // 小程序渠道不上柜机屏（V296/P3-6）
            }
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
                // playUrl：同源媒体代理，供 H5/小程序 <image>/<video> 直链（与素材库 preview 一致）
                String playUrl = "/api/v2/media/ad-assets/" + asset.getAssetId();
                items.add(new ScreenContentItemDto(asset.getAssetId(), asset.getTitle(),
                        asset.getAssetType(), asset.getStorageUri(), asset.getDurationSeconds(),
                        playUrl));
            }
            if (!items.isEmpty()) {
                return new ScreenContentDto(campaign.getCampaignId(), campaign.getName(), items);
            }
        }
        return new ScreenContentDto(null, null, List.of());
    }

    /** P3-6：小程序轮播位条目（每计划取首个 ACTIVE 的 IMAGE 素材）。 */
    public record MiniProgramBanner(long campaignId, long assetId, String title, String linkUrl) {}

    /**
     * P3-6：消费者小程序轮播位内容——RUNNING 且档期内且 channel=MINI_PROGRAM，
     * 按计划创建时间倒序、计划内素材顺序取首个可用图片。柜机屏渠道互不可见。
     *
     * <p>投放范围复用 device_scope/ad_campaign_device（V296 语义扩展）：
     * ALL=全场景；SPECIFIC=仅限指定柜机上下文——deviceId 为空（落地页等无柜码场景）
     * 或不在投放设备列表时不出。</p>
     */
    public List<MiniProgramBanner> listMiniProgramBanners(int limit, String deviceId) {
        if (limit <= 0) {
            return List.of();
        }
        List<MiniProgramBanner> banners = new ArrayList<>();
        for (AdCampaign campaign : campaignRepository.findRunningInWindow(Instant.now())) {
            if (!CHANNEL_MINI_PROGRAM.equals(campaign.getChannel())) {
                continue;
            }
            if (SPECIFIC.equals(campaign.getDeviceScope())) {
                if (deviceId == null || deviceId.isBlank()) {
                    continue;
                }
                boolean inScope = deviceRepository.findByCampaignId(campaign.getCampaignId()).stream()
                        .anyMatch(d -> d.getDeviceId().equalsIgnoreCase(deviceId.trim()));
                if (!inScope) {
                    continue;
                }
            }
            for (AdCampaignItem item : itemRepository.findByCampaignId(campaign.getCampaignId())) {
                MediaAsset asset = assetRepository.findById(item.getAssetId()).orElse(null);
                if (asset == null || !"ACTIVE".equals(asset.getStatus())
                        || !"IMAGE".equals(asset.getAssetType())) {
                    continue;
                }
                banners.add(new MiniProgramBanner(campaign.getCampaignId(), asset.getAssetId(),
                        campaign.getName(), campaign.getLinkUrl()));
                break;
            }
            if (banners.size() >= limit) {
                break;
            }
        }
        return banners;
    }

    /**
     * 设备/柜机屏侧推广位回写（一机一密口 {@code /internal/v1/devices/{id}/ad-play}）。
     *
     * <p>这条没有登录主体 ⇒ 逐字保留旧语义与**旧去重键格式**（批次 S 的同款取舍：键格式一变，
     * 60s 窗口内的在途键失效，等于放开一次重复上报）。
     */
    @Transactional
    public void recordPlayEvent(String deviceId, Long campaignId, Long assetId, String eventType) {
        recordPlayEvent(deviceId, campaignId, assetId, eventType, null);
    }

    /**
     * 推广位回写曝光/完播/点击，供投放 ROI 留痕。
     *
     * <p>🔴 服务端**必须自己去重**：客户端（{@code device-ad-banner.vue}）的 {@code impressed} /
     * {@code completeTimers} 只是内存 Set，改包或脚本可直接绕过；而本表是 ROI 的唯一数据源。
     *
     * <p>MA-150（A 档）：{@code userId} 非空（小程序登录用户上报）时，去重键按**人**
     * （{@code U{userId}} × 位置 × 计划 × 素材 × 事件类型），并把 {@code user_id} 与
     * {@code position='BANNER'} 落列——此前只按设备去重 ⇒ 任何登录用户都能给任意**已存在**的柜机
     * 造曝光（{@code device_id} 有 FK 指向 {@code device_info}），且换 assetId/eventType 即绕开 60s 窗口。
     * {@code userId} 为空（设备凭据口）时走旧键格式，{@code user_id}/{@code position} 留 NULL。
     */
    @Transactional
    public void recordPlayEvent(String deviceId, Long campaignId, Long assetId, String eventType, Long userId) {
        boolean attributed = userId != null && userId > 0;
        String deviceKey = deviceId == null || deviceId.isBlank() ? null : deviceId.trim();
        if (campaignId == null || (!attributed && deviceKey == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "campaignId/deviceId 必填");
        }
        String type = requirePlayEventType(eventType);
        AdCampaign campaign = requireCampaign(campaignId);
        // H44：仅统计投放中计划，且设备必须在投放范围内；越界/停用直接丢弃（不抛 4xx，防接口探测刷量）
        if (!"RUNNING".equalsIgnoreCase(campaign.getStatus())) {
            log.debug("ad play event dropped: campaign {} not RUNNING (status={})",
                    campaignId, campaign.getStatus());
            return;
        }
        // 设备范围只在「调用方报了柜机号」时校验：小程序侧 device_id 留空，没有柜可校
        if (deviceKey != null && !CHANNEL_MINI_PROGRAM.equals(campaign.getChannel())
                && SPECIFIC.equals(campaign.getDeviceScope())
                && deviceRepository.findByCampaignId(campaignId).stream()
                .noneMatch(d -> d.getDeviceId().equalsIgnoreCase(deviceKey))) {
            log.debug("ad play event dropped: device {} not in campaign {} device scope",
                    deviceKey, campaignId);
            return;
        }
        AdPlayEventDeduplicator.PlayEventKey key = attributed
                ? new AdPlayEventDeduplicator.PlayEventKey(
                        "U" + userId, PromotionPlacementService.BANNER, null, campaignId, assetId, type)
                : new AdPlayEventDeduplicator.PlayEventKey(deviceKey, null, null, campaignId, assetId, type);
        // 窗口内重复上报：静默丢弃（同样不抛 4xx，理由与上面的越界丢弃一致）
        if (!playEventDeduplicator.tryAcquire(key)) {
            log.debug("ad play event dropped: duplicate {} in dedup window (user={}, device={}, campaign={}, asset={})",
                    type, userId, deviceKey, campaignId, assetId);
            return;
        }
        AdPlayEvent ev = new AdPlayEvent();
        ev.setCampaignId(campaignId);
        ev.setDeviceId(deviceKey);
        ev.setAssetId(assetId);
        if (attributed) {
            ev.setUserId(userId);
            ev.setPosition(PromotionPlacementService.BANNER);
        }
        ev.setEventType(type);
        ev.setCreatedAt(Instant.now());
        try {
            playEventRepository.insert(ev);
        } catch (RuntimeException e) {
            // 落库失败 ⇒ 归还资格：同窗口内的重试仍能落库，避免一次 DB 抖动就让这次真实曝光彻底丢失
            playEventDeduplicator.release(key);
            throw e;
        }
    }

    /**
     * 展示位打点（批次 S）：主体是登录消费者，事件按「位置 × 活动 × 计划 × 素材」记，
     * 因此 device_id 留 NULL——小程序流量不再伪装成柜机号。
     *
     * <p>与设备侧 {@link #recordPlayEvent} 共用同一张表但**不共用去重键语义**：
     * 这里按人（{@code U{userId}}）去重，换 deviceId 绕不去 60s 窗口了。
     */
    @Transactional
    public void recordPlacementEvent(Long userId, String position, Long activityId, Long campaignId,
                                     Long assetId, String eventType) {
        String type = requirePlayEventType(eventType);
        AdPlayEventDeduplicator.PlayEventKey key = new AdPlayEventDeduplicator.PlayEventKey(
                "U" + userId, position, activityId, campaignId, assetId, type);
        if (!playEventDeduplicator.tryAcquire(key)) {
            log.debug("placement event dropped: duplicate {} in dedup window (user={}, position={}, activity={})",
                    type, userId, position, activityId);
            return;
        }
        AdPlayEvent ev = new AdPlayEvent();
        ev.setUserId(userId);
        ev.setPosition(position);
        ev.setActivityId(activityId);
        ev.setCampaignId(campaignId);
        ev.setAssetId(assetId);
        ev.setEventType(type);
        ev.setCreatedAt(Instant.now());
        try {
            playEventRepository.insert(ev);
        } catch (RuntimeException e) {
            playEventDeduplicator.release(key);
            throw e;
        }
    }

    private static String requirePlayEventType(String eventType) {
        String type = eventType == null ? "" : eventType.trim().toUpperCase();
        if (!type.equals("IMPRESSION") && !type.equals("COMPLETE") && !type.equals("CLICK")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "eventType 仅支持 IMPRESSION/COMPLETE/CLICK");
        }
        return type;
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
            // deviceId 大小写敏感：投放行按原样存，避免与 device_info 对不上（去重大小写各自独立窗口）
            row.setDeviceId(deviceId.trim());
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
                campaign.getDeviceScope(), campaign.getChannel(), campaign.getLinkUrl(),
                campaign.getStartAt(), campaign.getEndAt(),
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
