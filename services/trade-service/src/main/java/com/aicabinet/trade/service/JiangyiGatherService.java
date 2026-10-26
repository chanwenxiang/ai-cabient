package com.aicabinet.trade.service;

import com.aicabinet.trade.client.JiangyiGatherClient;
import com.aicabinet.trade.domain.JiangyiDevice;
import com.aicabinet.trade.domain.JiangyiTrainingTicket;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.dto.JiangyiGatherDtos.GatherCheckItem;
import com.aicabinet.trade.dto.JiangyiGatherDtos.JiangyiModelFile;
import com.aicabinet.trade.dto.JiangyiGatherDtos.MerchantGatherStatusDto;
import com.aicabinet.trade.dto.JiangyiGatherDtos.MerchantTrainingItemDto;
import com.aicabinet.trade.dto.JiangyiGatherDtos.MerchantTrainingStatusDto;
import com.aicabinet.trade.dto.JiangyiGatherDtos.TrainedProduct;
import com.aicabinet.trade.mapper.JiangyiDeviceMapper;
import com.aicabinet.trade.mapper.JiangyiSkuJiangyiLinkMapper;
import com.aicabinet.trade.mapper.JiangyiTrainingTicketMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 将邑采集流程编排（CB-023 二期，范围 C）：进入/退出采集模式、触发学习（finishNotify
 * 一次性凭据）、进度聚合（开门状态/学习中/审核列表）。
 *
 * <p>边界（CB-023 台账结论 3）：4.3.x 采集批次操作留在将邑商户 App 人工完成，本期只编排
 * 我们侧可治理的部分。采集开门（§4.2.3）由将邑直接下发、不经我方 gateway——采集模式锁
 * 管住我方营业开门（409），将邑侧开门不产生我方会话，账务天然隔离。学习完成回调交叉验证
 * 通过后自动退出采集模式（恢复营业），运营无需再手工点退出。</p>
 *
 * <p>🔴 学习是<b>集合粒度</b>（§4.4.3 请求体无任何商品参数）：一次 {@code commitTrainingTenantOne}
 * 训练的是厂商侧「已采集待学习」的全集。所以批量触发只发 <b>一次</b> 厂商调用，再为我方勾选的
 * 每个 SKU 各落一张票据、共享同一个 finishNotifyId（V407 起该列不唯一）；回调到达后逐张按
 * trained 列表交叉验证。若按 SKU 逐个调厂商，会重复训练同一个模型，不存在这种用法。</p>
 */
@Service
public class JiangyiGatherService {

    private static final Logger log = LoggerFactory.getLogger(JiangyiGatherService.class);

    private static final String FINISH_NOTIFY_PATH = "/jiangyi/api/gather-finish-notify";
    /** 批量触发上限：一次调用最多扇出这么多张票据。 */
    private static final int MAX_BATCH_SKUS = 20;

    private final JiangyiGatherClient jiangyiGatherClient;
    private final JiangyiDeviceMapper jiangyiDeviceMapper;
    private final JiangyiTrainingTicketMapper ticketMapper;
    private final JiangyiSkuJiangyiLinkMapper linkMapper;
    private final SkuCatalogMapper skuCatalogMapper;
    private final String publicBaseUrl;

    public JiangyiGatherService(JiangyiGatherClient jiangyiGatherClient,
                                JiangyiDeviceMapper jiangyiDeviceMapper,
                                JiangyiTrainingTicketMapper ticketMapper,
                                JiangyiSkuJiangyiLinkMapper linkMapper,
                                SkuCatalogMapper skuCatalogMapper,
                                @Value("${JIANGYI_PUBLIC_BASE_URL:}") String publicBaseUrl) {
        this.jiangyiGatherClient = jiangyiGatherClient;
        this.jiangyiDeviceMapper = jiangyiDeviceMapper;
        this.ticketMapper = ticketMapper;
        this.linkMapper = linkMapper;
        this.skuCatalogMapper = skuCatalogMapper;
        this.publicBaseUrl = publicBaseUrl;
    }

    /** 采集模式是否生效（营业开门链路 409 判据；即时查库，不走缓存——模式切换必须立即生效）。 */
    public boolean isGatherLocked(String deviceId) {
        JiangyiDevice device = jiangyiDeviceMapper.selectById(deviceId);
        return device != null && device.getGatherLockedAt() != null;
    }

    /**
     * 商户侧采集状态（小程序入口渲染判据，CB-023 商户端补充）：与 admin 入口共用
     * startGather/exitGatherMode/409 闸门，权限与柜机归属由商户门户层把关（此处只看绑定态）。
     */
    public MerchantGatherStatusDto merchantStatus(String deviceId) {
        JiangyiDevice device = jiangyiDeviceMapper.selectById(deviceId);
        boolean bound = device != null && "BOUND".equals(device.getStatus());
        return new MerchantGatherStatusDto(bound, bound && device.getGatherLockedAt() != null);
    }

    /**
     * 商户端只读学习进度（补货员看「学完没有」，不需要运营转述）。
     *
     * <p>只读本地票据，<b>不读厂商读面</b>——商户端没有运营的排障能力，厂商 503 时整块进度
     * 不该变成错误页；「学习中」以我方 PENDING 票据为准（回调是学习的唯一完成信号）。
     * 一次触发（含批量）共享一个 finishNotifyId，所以按最近一张票据把整组 SKU 摊出来。</p>
     */
    public MerchantTrainingStatusDto merchantTrainingStatus(String deviceId) {
        JiangyiDevice device = jiangyiDeviceMapper.selectById(deviceId);
        boolean bound = device != null && "BOUND".equals(device.getStatus());
        if (!bound) {
            return new MerchantTrainingStatusDto(false, false, null, null, null, null, List.of());
        }
        boolean gatherLocked = device.getGatherLockedAt() != null;
        Optional<JiangyiTrainingTicket> latest = ticketMapper.latestByDevice(deviceId);
        if (latest.isEmpty()) {
            return new MerchantTrainingStatusDto(true, gatherLocked, null, null, null, null, List.of());
        }
        JiangyiTrainingTicket anchor = latest.get();
        List<JiangyiTrainingTicket> tickets =
                ticketMapper.listByFinishNotifyId(anchor.getFinishNotifyId());
        Map<String, String> names = skuNames(tickets.stream()
                .map(JiangyiTrainingTicket::getSkuId).filter(Objects::nonNull).toList());
        Instant createdAt = tickets.stream().map(JiangyiTrainingTicket::getCreatedAt)
                .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        Instant finishedAt = tickets.stream().map(JiangyiTrainingTicket::getFinishedAt)
                .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        List<MerchantTrainingItemDto> items = tickets.stream()
                .map(t -> new MerchantTrainingItemDto(t.getSkuId(), names.get(t.getSkuId()), t.getStatus()))
                .toList();
        return new MerchantTrainingStatusDto(true, gatherLocked, anchor.getModelName(),
                aggregateStatus(tickets), createdAt, finishedAt, items);
    }

    /** 一次触发的整体状态：还有 PENDING 就是学习中；全成功才成功；否则失败。 */
    static String aggregateStatus(List<JiangyiTrainingTicket> tickets) {
        if (tickets.stream().anyMatch(t -> "PENDING".equals(t.getStatus()))) {
            return "PENDING";
        }
        boolean allFinished = !tickets.isEmpty()
                && tickets.stream().allMatch(t -> "FINISHED".equals(t.getStatus()));
        return allFinished ? "FINISHED" : "FAILED";
    }

    /** 进入采集模式：BOUND + 未锁 → 置锁 → 将邑侧采集开门（§4.2.3）。 */
    public void startGather(String deviceId, String doorPosition) {
        JiangyiDevice device = requireBoundDevice(deviceId);
        if (device.getGatherLockedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "设备已在采集模式中");
        }
        if (device.getIdentifier() == null || device.getIdentifier().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "设备缺少将邑设备编码（identifier）");
        }
        jiangyiDeviceMapper.markGatherLocked(deviceId, Instant.now());
        try {
            jiangyiGatherClient.gatherOpenDoor(device.getIdentifier(), doorPosition);
        } catch (IllegalStateException e) {
            // 开门失败回滚锁——采集模式与将邑侧开门必须成对
            jiangyiDeviceMapper.markGatherUnlocked(deviceId, Instant.now());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "将邑采集开门失败：" + e.getMessage());
        }
        log.info("jiangyi gather started deviceId={} identifier={} door={}", deviceId, device.getIdentifier(), doorPosition);
    }

    /** 退出采集模式（admin 手动；恢复营业）。 */
    public void exitGatherMode(String deviceId) {
        requireBoundDevice(deviceId);
        jiangyiDeviceMapper.markGatherUnlocked(deviceId, Instant.now());
        log.info("jiangyi gather exited deviceId={}", deviceId);
    }

    /** 进度聚合：开门状态 + 待采集 + 学习中 + 审核列表（productIds=全部，§4.4.6 可空）。 */
    public GatherProgress progress(String deviceId) {
        JiangyiDevice device = requireBoundDevice(deviceId);
        var doorStatus = jiangyiGatherClient.doorStatus(device.getIdentifier());
        var training = jiangyiGatherClient.trainingProducts();
        List<GatherCheckItem> checks = jiangyiGatherClient.gatherCheck(null);
        TrainingTicketView lastTraining = ticketMapper.latestByDevice(deviceId)
                .map(TrainingTicketView::of)
                .orElse(null);
        return new GatherProgress(device.getGatherLockedAt() != null, doorStatus, training, checks,
                lastTraining);
    }

    /**
     * 触发学习（单 SKU，§4.4.3）：幂等（同 SKU 有 PENDING ticket 直接返回）；finishNotifyId=UUID
     * 一次性凭据落库；finishNotifyUrl = gateway 公网面（无 token，将邑文档约束）。
     *
     * <p>学习实际是集合粒度，这个入口只用于「只学一个商品」的场景；多个商品请用
     * {@link #startTrainingBatch}，否则会重复触发同一模型的训练。</p>
     */
    public JiangyiTrainingTicket startTraining(String deviceId, String skuId, String modelName) {
        requireBoundDevice(deviceId);
        requirePublicBaseUrl();
        var existing = ticketMapper.findPendingBySku(skuId);
        if (existing.isPresent()) {
            return existing.get();
        }
        String finishNotifyId = UUID.randomUUID().toString();
        JiangyiTrainingTicket ticket = newTicket(deviceId, skuId, resolveProductId(skuId),
                finishNotifyId, modelName, Instant.now());
        ticketMapper.insert(ticket);
        try {
            jiangyiGatherClient.commitTraining(modelName, publicBaseUrl + FINISH_NOTIFY_PATH, finishNotifyId);
        } catch (IllegalStateException e) {
            ticketMapper.finishById(ticket.getId(), false, Instant.now());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "将邑开始学习失败：" + e.getMessage());
        }
        log.info("jiangyi training started deviceId={} skuId={} modelName={} ticket={}",
                deviceId, skuId, modelName, finishNotifyId);
        return ticket;
    }

    /**
     * 批量触发学习：一次 §4.4.3 调用覆盖勾选的多个 SKU（厂商学习=集合粒度，无单品参数）。
     *
     * <p>三道前置：①每个 SKU 必须已挂将邑商品（否则回调无法交叉验证，票据只会停在等待）；
     * ②任一 SKU 已有 PENDING 票据直接 409——在途时再触发会让旧票据永远等不到自己的回执；
     * ③模型名软锁：厂商模型列表能读到时必须命中其一，列表为空/读面失败时放行（首次学习
     * 本来就没有可选模型）。</p>
     *
     * <p>厂商调用失败时整批票据置 FAILED 并抛 502——票据集与厂商侧状态不能一半一半。</p>
     */
    public BatchTrainingResult startTrainingBatch(String deviceId, List<String> skuIds, String modelName) {
        requireBoundDevice(deviceId);
        requirePublicBaseUrl();
        String model = modelName == null ? "" : modelName.trim();
        if (model.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模型名不能为空");
        }
        List<String> skus = skuIds == null ? List.of() : skuIds.stream()
                .filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty())
                .distinct().toList();
        if (skus.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请至少选择一个商品");
        }
        if (skus.size() > MAX_BATCH_SKUS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "一次最多触发 " + MAX_BATCH_SKUS + " 个商品，请分批提交");
        }
        Map<String, String> productIdBySku = new LinkedHashMap<>();
        List<String> unlinked = new ArrayList<>();
        for (String skuId : skus) {
            String productId = resolveProductId(skuId);
            if (productId == null || productId.isBlank()) {
                unlinked.add(skuId);
            } else {
                productIdBySku.put(skuId, productId);
            }
        }
        if (!unlinked.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "以下 SKU 未挂将邑商品，学习结果无法交叉验证：" + String.join("、", unlinked));
        }
        List<String> inFlight = ticketMapper.listPendingBySkus(skus).stream()
                .map(JiangyiTrainingTicket::getSkuId).distinct().toList();
        if (!inFlight.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "以下商品已有学习在途，请等回执后再试：" + String.join("、", inFlight));
        }
        requireKnownModelName(model);

        String finishNotifyId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        List<JiangyiTrainingTicket> tickets = new ArrayList<>();
        for (Map.Entry<String, String> e : productIdBySku.entrySet()) {
            JiangyiTrainingTicket ticket = newTicket(deviceId, e.getKey(), e.getValue(),
                    finishNotifyId, model, now);
            ticketMapper.insert(ticket);
            tickets.add(ticket);
        }
        try {
            jiangyiGatherClient.commitTraining(model, publicBaseUrl + FINISH_NOTIFY_PATH, finishNotifyId);
        } catch (IllegalStateException e) {
            for (JiangyiTrainingTicket ticket : tickets) {
                ticketMapper.finishById(ticket.getId(), false, Instant.now());
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "将邑开始学习失败：" + e.getMessage());
        }
        log.info("jiangyi batch training started deviceId={} modelName={} skus={} tickets={}",
                deviceId, model, productIdBySku.keySet(), finishNotifyId);
        return new BatchTrainingResult(deviceId, model, tickets.size(),
                tickets.stream().map(t -> new BatchTrainingItem(t.getId(), t.getSkuId(),
                        t.getJiangyiProductId(), t.getStatus())).toList());
    }

    /**
     * 模型名软锁：厂商模型列表能读到时必须是其中一项（手打错字会训出对不上的模型）；
     * 列表为空（租户尚无学成模型，首次学习就是这个状态）或读面失败时放行并留日志——
     * 硬锁会让首次学习永远发不出去。
     */
    private void requireKnownModelName(String modelName) {
        List<JiangyiModelFile> models;
        try {
            models = jiangyiGatherClient.modelFiles();
        } catch (RuntimeException e) {
            log.warn("jiangyi model list unavailable; skip modelName validation: {}", e.getMessage());
            return;
        }
        List<String> known = models.stream().map(JiangyiModelFile::modelName)
                .filter(n -> n != null && !n.isBlank()).toList();
        if (!known.isEmpty() && known.stream().noneMatch(modelName::equals)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "模型名不在将邑当前模型列表中：" + String.join("、", known)
                            + "（学习范围由厂商集合决定，名字错会训出对不上的模型）");
        }
    }

    /**
     * 学习完成回调（gateway 转发，无鉴权面）：三重防伪造（CB-023 设计）——
     * ①finishNotifyId 必须命中 PENDING（否则静默 202 语义，不回错误详情）；
     * ②反查将邑已学习商品交叉验证（productId 确实在 trained 列表才 FINISHED）；
     * ③逐张 CAS 一次性消费（重复回调 no-op）。
     *
     * <p>一次厂商回执可能对应多张票据（批量触发），逐张判定：命中的置 FINISHED，未命中的
     * 保留 PENDING（异步延迟容忍，与单张时代同一口径）。有任意一张确认即说明本轮训练已完成，
     * 立刻退出该柜采集模式恢复营业（采集锁只拦我方营业开门，多锁一秒就是少卖一秒）。
     * 失败回调与「回调到了但商品还没学完」都保持采集模式，让运营能手动退出。</p>
     */
    public void handleFinishNotify(String finishNotifyId, String msg) {
        List<JiangyiTrainingTicket> tickets = ticketMapper.listByFinishNotifyId(finishNotifyId).stream()
                .filter(t -> "PENDING".equals(t.getStatus()))
                .toList();
        if (tickets.isEmpty()) {
            log.warn("jiangyi gather finish-notify dropped (no pending ticket) finishNotifyId={}", finishNotifyId);
            return;
        }
        if (!"success".equalsIgnoreCase(msg)) {
            tickets.forEach(t -> ticketMapper.finishById(t.getId(), false, Instant.now()));
            log.warn("jiangyi training failed by notify finishNotifyId={} skus={} msg={}",
                    finishNotifyId, skuIdsOf(tickets), msg);
            return;
        }
        Set<String> trainedIds = jiangyiGatherClient.trainedProducts().stream()
                .map(p -> String.valueOf(p.id())).collect(Collectors.toSet());
        int confirmed = 0;
        for (JiangyiTrainingTicket ticket : tickets) {
            if (!trainedIds.contains(ticket.getJiangyiProductId())) {
                log.warn("jiangyi finish-notify but product not trained yet ticket={} sku={} jiangyiProductId={} — keep PENDING",
                        ticket.getId(), ticket.getSkuId(), ticket.getJiangyiProductId());
                continue;
            }
            if (ticketMapper.finishById(ticket.getId(), true, Instant.now()) > 0) {
                confirmed++;
            }
        }
        if (confirmed == 0) {
            // 并发重复回调：另一条线程已完成终态+退出，这里不再动设备锁
            log.info("jiangyi training confirm already consumed or unmatched finishNotifyId={}", finishNotifyId);
            return;
        }
        String deviceId = tickets.stream().map(JiangyiTrainingTicket::getDeviceId)
                .filter(d -> d != null && !d.isBlank()).findFirst().orElse(null);
        boolean wasLocked = deviceId != null && isGatherLocked(deviceId);
        if (wasLocked) {
            jiangyiDeviceMapper.markGatherUnlocked(deviceId, Instant.now());
        }
        log.info("jiangyi training confirmed finishNotifyId={} confirmed={}/{} deviceId={} autoExitedGather={}",
                finishNotifyId, confirmed, tickets.size(), deviceId, wasLocked);
    }

    /** 审核列表（productIds 从挂接表反查；空=全部）。 */
    public List<GatherCheckItem> checkList(String skuId) {
        var links = linkMapper.listBySku(skuId);
        List<Long> productIds = links.stream()
                .map(l -> safeParse(l.getJiangyiProductId()))
                .filter(id -> id != null && id > 0)
                .toList();
        return jiangyiGatherClient.gatherCheck(productIds.isEmpty() ? null : productIds);
    }

    private JiangyiTrainingTicket newTicket(String deviceId, String skuId, String jiangyiProductId,
                                            String finishNotifyId, String modelName, Instant createdAt) {
        JiangyiTrainingTicket ticket = new JiangyiTrainingTicket();
        ticket.setDeviceId(deviceId);
        ticket.setSkuId(skuId);
        // 学习是商品集合动作（§4.4.3 参数表无 productId）——记挂接商品 id 便于回执交叉验证
        ticket.setJiangyiProductId(jiangyiProductId);
        ticket.setFinishNotifyId(finishNotifyId);
        ticket.setModelName(modelName);
        ticket.setStatus("PENDING");
        ticket.setCreatedAt(createdAt);
        return ticket;
    }

    private Map<String, String> skuNames(List<String> skuIds) {
        if (skuIds.isEmpty()) {
            return Map.of();
        }
        return skuCatalogMapper.findBySkuIds(skuIds).stream()
                .filter(s -> s.getSkuId() != null && s.getSkuName() != null)
                .collect(Collectors.toMap(SkuCatalog::getSkuId, SkuCatalog::getSkuName, (a, b) -> a));
    }

    private static List<String> skuIdsOf(List<JiangyiTrainingTicket> tickets) {
        return tickets.stream().map(JiangyiTrainingTicket::getSkuId).toList();
    }

    private void requirePublicBaseUrl() {
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "未配置将邑回调公网基址（JIANGYI_PUBLIC_BASE_URL），无法接收学习完成通知");
        }
    }

    private Long safeParse(String s) {
        try {
            return s == null ? null : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String resolveProductId(String skuId) {
        var links = linkMapper.listBySku(skuId);
        return links.isEmpty() ? "" : links.get(0).getJiangyiProductId();
    }

    private JiangyiDevice requireBoundDevice(String deviceId) {
        JiangyiDevice device = jiangyiDeviceMapper.selectById(deviceId);
        if (device == null || !"BOUND".equals(device.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "将邑设备未绑定：" + deviceId);
        }
        return device;
    }

    /** 进度聚合视图（openapi inline schema）。 */
    public record GatherProgress(boolean gatherLocked,
                                 Object doorStatus,
                                 Object trainingProducts,
                                 List<GatherCheckItem> checks,
                                 TrainingTicketView lastTraining) {}

    /** 最近一次学习凭据（finishNotifyId 是一次性凭据，不回给前端）。 */
    public record TrainingTicketView(String skuId, String modelName, String jiangyiProductId,
                                     String status, Instant createdAt, Instant finishedAt) {
        static TrainingTicketView of(JiangyiTrainingTicket t) {
            return new TrainingTicketView(t.getSkuId(), t.getModelName(), t.getJiangyiProductId(),
                    t.getStatus(), t.getCreatedAt(), t.getFinishedAt());
        }
    }

    /** 批量触发结果：一次厂商调用 + 多张票据（finishNotifyId 不回给前端）。 */
    public record BatchTrainingResult(String deviceId, String modelName, int count,
                                      List<BatchTrainingItem> items) {}

    public record BatchTrainingItem(Long ticketId, String skuId, String jiangyiProductId,
                                    String status) {}
}
