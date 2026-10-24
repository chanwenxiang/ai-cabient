package com.aicabinet.trade.service;

import com.aicabinet.common.dto.SkuSceneLimitItem;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.domain.SkuSceneLimit;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.SkuSceneLimitMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 商品×场景上架限额（CB-049 扩展，2026-10-16 用户需求）。
 *
 * <p>口径（用户拍板）：柜内该 SKU 的「上架合计」（各货道最大容量之和）不得大于该商品
 * 在柜子所属场景下的限额。校验点：货道绑定/容量变更（upsertSlots）、机型模板与命名模板套用
 * （模板路径超限跳过+计数，不炸事务——与单柜 SKU 上限同语义）。商品建档必须至少配一个场景。</p>
 */
@Service
public class SkuSceneLimitService {

    /**
     * 场景取值白名单（fail-closed）：与 shared-dict device_scene_type（CB-049）一致。
     * 后端没有字典表，这里钉死集合，新增场景须两端同步。
     */
    public static final Set<String> KNOWN_SCENES = Set.of(
            "OFFICE", "CAMPUS", "FACTORY", "HOSPITAL", "TRANSIT",
            "MALL", "RESIDENCE", "HOTEL", "GYM");

    private final SkuSceneLimitMapper limitRepository;
    private final SkuCatalogMapper skuCatalogRepository;
    private final DeviceSkuInventoryMapper inventoryRepository;

    public SkuSceneLimitService(SkuSceneLimitMapper limitRepository, SkuCatalogMapper skuCatalogRepository,
                                DeviceSkuInventoryMapper inventoryRepository) {
        this.limitRepository = limitRepository;
        this.skuCatalogRepository = skuCatalogRepository;
        this.inventoryRepository = inventoryRepository;
    }

    /** 建档/编辑请求里的限额条目校验：至少一条、场景合法、同场景不重复。 */
    public void validateRequestItems(List<SkuSceneLimitItem> items) {
        if (items == null || items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "商品必须至少配置一个投放场景与上架上限");
        }
        Set<String> seen = new HashSet<>();
        for (SkuSceneLimitItem item : items) {
            if (item == null || item.sceneType() == null || item.sceneType().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "场景限额条目缺少场景");
            }
            String scene = item.sceneType().trim();
            if (!KNOWN_SCENES.contains(scene)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知投放场景：" + scene);
            }
            if (item.maxQty() == null || item.maxQty() < 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "场景 " + scene + " 的上架上限必须 >= 1");
            }
            if (!seen.add(scene)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "场景限额重复：" + scene);
            }
        }
    }

    /** 整单替换某 SKU 的限额（建档/编辑保存路径）。 */
    @Transactional
    public void replaceLimits(String skuId, List<SkuSceneLimitItem> items) {
        validateRequestItems(items);
        limitRepository.delete(Wrappers.<SkuSceneLimit>lambdaQuery().eq(SkuSceneLimit::getSkuId, skuId));
        Instant now = Instant.now();
        for (SkuSceneLimitItem item : items) {
            SkuSceneLimit row = new SkuSceneLimit();
            row.setSkuId(skuId);
            row.setSceneType(item.sceneType().trim());
            row.setMaxQty(item.maxQty());
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            limitRepository.insert(row);
        }
    }

    /** 批量读若干 SKU 的限额，按 skuId 分组（admin 列表/详情回显）。 */
    @Transactional(readOnly = true)
    public Map<String, List<SkuSceneLimitItem>> limitsBySku(Collection<String> skuIds) {
        Map<String, List<SkuSceneLimitItem>> out = new HashMap<>();
        if (skuIds == null || skuIds.isEmpty()) {
            return out;
        }
        List<SkuSceneLimit> rows = limitRepository.selectList(
                Wrappers.<SkuSceneLimit>lambdaQuery().in(SkuSceneLimit::getSkuId, skuIds));
        for (SkuSceneLimit row : rows) {
            out.computeIfAbsent(row.getSkuId(), k -> new java.util.ArrayList<>())
                    .add(new SkuSceneLimitItem(row.getSceneType(), row.getMaxQty()));
        }
        return out;
    }

    /** 指定场景下若干 SKU 的限额（skuId → maxQty）；场景为空或无条目返回空 Map（=不限制）。 */
    @Transactional(readOnly = true)
    public Map<String, Integer> limitQtyBySku(String scene, Collection<String> skuIds) {
        if (scene == null || scene.isBlank() || skuIds == null || skuIds.isEmpty()) {
            return Map.of();
        }
        List<SkuSceneLimit> rows = limitRepository.selectList(
                Wrappers.<SkuSceneLimit>lambdaQuery()
                        .eq(SkuSceneLimit::getSceneType, scene)
                        .in(SkuSceneLimit::getSkuId, skuIds));
        Map<String, Integer> out = new HashMap<>();
        for (SkuSceneLimit row : rows) {
            out.put(row.getSkuId(), row.getMaxQty());
        }
        return out;
    }

    /**
     * 硬校验：柜内各 SKU 的上架合计（最大容量之和）是否超出其在该柜场景下的限额。
     *
     * @param device               目标柜（sceneType 必须已设置）
     * @param totalMaxLevelBySku   落库后柜内 skuId → 最大容量合计
     */
    public void assertWithinSceneLimit(DeviceInfo device, Map<String, Integer> totalMaxLevelBySku) {
        if (totalMaxLevelBySku == null || totalMaxLevelBySku.isEmpty()) {
            return;
        }
        String scene = device.getSceneType();
        if (scene == null || scene.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "该柜未设置投放场景：请先在「资产与投放」选择场景，再上架商品（场景决定各商品的上架上限）");
        }
        List<SkuSceneLimit> limits = limitRepository.selectList(
                Wrappers.<SkuSceneLimit>lambdaQuery()
                        .eq(SkuSceneLimit::getSceneType, scene)
                        .in(SkuSceneLimit::getSkuId, totalMaxLevelBySku.keySet()));
        if (limits.isEmpty()) {
            return;
        }
        for (SkuSceneLimit limit : limits) {
            Integer total = totalMaxLevelBySku.get(limit.getSkuId());
            if (total == null || total <= limit.getMaxQty()) {
                continue;
            }
            String skuName = skuCatalogRepository.findById(limit.getSkuId())
                    .map(SkuCatalog::getSkuName).orElse(limit.getSkuId());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "「" + skuName + "」在场景「" + scene + "」下限上架 " + limit.getMaxQty()
                            + "，当前柜内上架合计 " + total + "，请调低容量或减少绑定货道");
        }
    }

    /**
     * 「编辑库存」容量口径的场景限额校验：商品配置了该场景的限额条目且编辑后的容量
     * 超出上限即拒绝（未配置条目=不限制）。与 {@link #assertWithinSceneLimit} 的货道
     * maxLevel 合计口径互补——直接改库存容量的路径此前绕过了场景限额（2026-10-22 用户反馈）。
     */
    public void assertCapacityWithinSceneLimit(String sceneType, String skuId, int capacity) {
        if (sceneType == null || sceneType.isBlank() || capacity <= 0) {
            return;
        }
        SkuSceneLimit limit = limitRepository.selectOne(
                Wrappers.<SkuSceneLimit>lambdaQuery()
                        .eq(SkuSceneLimit::getSceneType, sceneType)
                        .eq(SkuSceneLimit::getSkuId, skuId));
        if (limit == null || capacity <= limit.getMaxQty()) {
            return;
        }
        String skuName = skuCatalogRepository.findById(skuId)
                .map(SkuCatalog::getSkuName).orElse(skuId);
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "「" + skuName + "」在投放场景「" + sceneType + "」的上架上限为 " + limit.getMaxQty()
                        + "，编辑容量 " + capacity + " 超出限制");
    }

    /**
     * 换场景预检（CB-049 扩展，2026-10-23 用户拍板「预检拒绝」）：柜子改投放场景时，
     * 按目标场景校验柜内在售商品容量（device_sku_inventory.capacity，CB-051 口径：
     * 运营侧库存以该表为准、slot 记账仅保留后端语义）。任一 SKU 超限即 400 并一次性
     * 列出全部超限商品——不静默钳制容量（与模板套用「超限跳过+计数」是两种有意不同的语义）。
     * 目标场景为空（=清除场景）或柜内无在售商品时放行。
     */
    public void assertSceneSwitchAllowed(String deviceId, String newSceneType) {
        if (newSceneType == null || newSceneType.isBlank()) {
            return;
        }
        String scene = newSceneType.trim();
        if (!KNOWN_SCENES.contains(scene)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知投放场景：" + scene);
        }
        List<DeviceSkuInventory> rows = inventoryRepository.selectList(
                Wrappers.<DeviceSkuInventory>lambdaQuery()
                        .eq(DeviceSkuInventory::getDeviceId, deviceId));
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Map<String, Integer> capacityBySku = new HashMap<>();
        for (DeviceSkuInventory row : rows) {
            if (row.getCapacity() > 0) {
                capacityBySku.merge(row.getSkuId(), row.getCapacity(), Integer::sum);
            }
        }
        if (capacityBySku.isEmpty()) {
            return;
        }
        List<SkuSceneLimit> limits = limitRepository.selectList(
                Wrappers.<SkuSceneLimit>lambdaQuery()
                        .eq(SkuSceneLimit::getSceneType, scene)
                        .in(SkuSceneLimit::getSkuId, capacityBySku.keySet()));
        List<String> offenders = new java.util.ArrayList<>();
        for (SkuSceneLimit limit : limits) {
            Integer total = capacityBySku.get(limit.getSkuId());
            if (total == null || total <= limit.getMaxQty()) {
                continue;
            }
            String skuName = skuCatalogRepository.findById(limit.getSkuId())
                    .map(SkuCatalog::getSkuName).orElse(limit.getSkuId());
            offenders.add("「" + skuName + "」上限 " + limit.getMaxQty() + "，当前 " + total);
        }
        if (!offenders.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "切换到场景「" + scene + "」后被以下商品的上架容量超出限制：" + String.join("；", offenders)
                            + "。请先调低对应商品容量，再修改场景");
        }
    }
}
