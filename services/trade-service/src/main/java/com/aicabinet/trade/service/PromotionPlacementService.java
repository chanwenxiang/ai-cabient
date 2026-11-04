package com.aicabinet.trade.service;

import com.aicabinet.common.dto.PromotionPlacementDto;
import com.aicabinet.common.dto.UpsertPromotionPlacementRequest;
import com.aicabinet.trade.config.PromotionPlacementProperties;
import com.aicabinet.trade.domain.MediaAsset;
import com.aicabinet.trade.domain.PromotionActivity;
import com.aicabinet.trade.domain.PromotionPlacement;
import com.aicabinet.trade.mapper.MediaAssetMapper;
import com.aicabinet.trade.mapper.PromotionActivityMapper;
import com.aicabinet.trade.mapper.PromotionPlacementMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 活动展示位（CB-082 v1）。
 *
 * <p>规则（用户 2026-11-01 拍板）：
 * <ul>
 *   <li>{@code BANNER} 必选；</li>
 *   <li>{@code POPUP_BEFORE_OPEN} / {@code POPUP_AFTER_CLOSE} 互斥，最多一个；</li>
 *   <li>每个位置一张图，图片须为 ACTIVE 的 IMAGE 素材，且像素尺寸落在该位置限制内；</li>
 *   <li>进行中的活动不可改展示位（与活动编辑同口径：先停止）。</li>
 * </ul>
 */
@Service
public class PromotionPlacementService {

    public static final String BANNER = "BANNER";
    public static final String POPUP_BEFORE_OPEN = "POPUP_BEFORE_OPEN";
    public static final String POPUP_AFTER_CLOSE = "POPUP_AFTER_CLOSE";
    private static final Set<String> POSITIONS = Set.of(BANNER, POPUP_BEFORE_OPEN, POPUP_AFTER_CLOSE);

    /** 打点侧判据：位置字面量只此一份，别在控制器/服务层再抄一个集合。 */
    public static boolean isKnownPosition(String position) {
        return position != null && POSITIONS.contains(position.trim().toUpperCase());
    }
    private static final Set<String> POPUPS = Set.of(POPUP_BEFORE_OPEN, POPUP_AFTER_CLOSE);
    private static final String ACTIVITY_ACTIVE = "ACTIVE";

    private static final Logger log = LoggerFactory.getLogger(PromotionPlacementService.class);

    private final PromotionActivityMapper activityRepository;
    private final PromotionPlacementMapper placementRepository;
    private final MediaAssetMapper assetRepository;
    private final PromotionPlacementProperties properties;
    /** MA-082：展示位替换直接决定消费端轮播内容，此前零留痕。 */
    private final AdminAuditService auditService;

    public PromotionPlacementService(PromotionActivityMapper activityRepository,
                                     PromotionPlacementMapper placementRepository,
                                     MediaAssetMapper assetRepository,
                                     PromotionPlacementProperties properties,
                                     AdminAuditService auditService) {
        this.activityRepository = activityRepository;
        this.placementRepository = placementRepository;
        this.assetRepository = assetRepository;
        this.properties = properties;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<PromotionPlacementDto> list(Long activityId) {
        requireActivity(activityId);
        return placementRepository.findByActivityId(activityId).stream().map(this::toDto).toList();
    }

    /** 全量替换（弹窗位互斥由本方法保证；DB 唯一索引兜底并发）。 */
    @Transactional
    public List<PromotionPlacementDto> replace(Long operatorId, Long activityId, List<UpsertPromotionPlacementRequest> items) {
        PromotionActivity activity = requireActivity(activityId);
        if (ACTIVITY_ACTIVE.equalsIgnoreCase(activity.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "进行中的活动请先停止再编辑展示位");
        }
        List<PromotionPlacement> resolved = validateAndResolve(activity, items);
        placementRepository.delete(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<PromotionPlacement>lambdaQuery()
                        .eq(PromotionPlacement::getActivityId, activityId));
        for (PromotionPlacement p : resolved) {
            placementRepository.insert(p);
        }
        log.info("promotion placements replaced activity={} count={}", activityId, resolved.size());
        auditService.appendLog(operatorId, "PROMOTION_PLACEMENT_REPLACE", "PROMOTION",
                String.valueOf(activityId), "count=" + resolved.size());
        return placementRepository.findByActivityId(activityId).stream().map(this::toDto).toList();
    }

    /** 上线前校验：banner 必选（弹窗可选）。 */
    @Transactional(readOnly = true)
    public void requireBannerForLaunch(Long activityId) {
        if (placementRepository.countByActivityIdAndPosition(activityId, BANNER) < 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请先配置轮播横幅图片再启用活动");
        }
    }

    private List<PromotionPlacement> validateAndResolve(PromotionActivity activity,
                                                        List<UpsertPromotionPlacementRequest> items) {
        if (items == null || items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请至少配置轮播横幅");
        }
        List<PromotionPlacement> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int popups = 0;
        for (UpsertPromotionPlacementRequest item : items) {
            if (item == null || item.position() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "展示位缺少位置");
            }
            String position = item.position().trim().toUpperCase();
            if (!POSITIONS.contains(position)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知的展示位: " + item.position());
            }
            if (!seen.add(position)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "展示位重复: " + position);
            }
            if (POPUPS.contains(position)) {
                popups++;
            }
            MediaAsset asset = requireImage(item.assetId());
            PromotionPlacementProperties.Rule rule = POPUPS.contains(position)
                    ? properties.popup() : properties.banner();
            if (!rule.matches(asset.getImageWidth(), asset.getImageHeight())) {
                String actual = asset.getImageWidth() == null || asset.getImageHeight() == null
                        ? "未记录尺寸，请重新上传图片"
                        : asset.getImageWidth() + "×" + asset.getImageHeight() + "px";
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        label(position) + "图片尺寸不符合要求（" + rule.describe() + "），当前: " + actual);
            }
            PromotionPlacement p = new PromotionPlacement();
            p.setActivityId(activity.getActivityId());
            p.setPosition(position);
            p.setAssetId(asset.getAssetId());
            p.setImageWidth(asset.getImageWidth());
            p.setImageHeight(asset.getImageHeight());
            p.setSortOrder(Math.max(0, Math.min(9999,
                    item.sortOrder() == null ? 0 : item.sortOrder())));
            p.setCreatedAt(Instant.now());
            result.add(p);
        }
        if (!seen.contains(BANNER)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "轮播横幅为必选项");
        }
        if (popups > 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "开门前弹窗与关门后弹窗只能二选一");
        }
        return result;
    }

    private MediaAsset requireImage(Long assetId) {
        if (assetId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "展示位缺少图片");
        }
        MediaAsset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "素材不存在"));
        if (!"IMAGE".equalsIgnoreCase(asset.getAssetType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "展示位只能使用图片素材");
        }
        if (!ACTIVITY_ACTIVE.equalsIgnoreCase(asset.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "素材不可用（非启用状态）");
        }
        return asset;
    }

    private PromotionActivity requireActivity(Long activityId) {
        if (activityId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "activityId required");
        }
        return activityRepository.findById(activityId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "活动不存在"));
    }

    private PromotionPlacementDto toDto(PromotionPlacement p) {
        return new PromotionPlacementDto(
                p.getPlacementId(), p.getActivityId(), p.getPosition(), p.getAssetId(),
                p.getImageWidth(), p.getImageHeight(), p.getSortOrder(),
                "/api/v2/media/ad-assets/" + p.getAssetId());
    }

    private static String label(String position) {
        return switch (position) {
            case BANNER -> "轮播横幅";
            case POPUP_BEFORE_OPEN -> "开门前弹窗";
            case POPUP_AFTER_CLOSE -> "关门后弹窗";
            default -> position;
        };
    }
}
