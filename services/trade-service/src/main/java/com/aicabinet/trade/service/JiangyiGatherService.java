package com.aicabinet.trade.service;

import com.aicabinet.trade.client.JiangyiGatherClient;
import com.aicabinet.trade.domain.JiangyiDevice;
import com.aicabinet.trade.domain.JiangyiTrainingTicket;
import com.aicabinet.trade.dto.JiangyiGatherDtos.GatherCheckItem;
import com.aicabinet.trade.dto.JiangyiGatherDtos.TrainedProduct;
import com.aicabinet.trade.mapper.JiangyiDeviceMapper;
import com.aicabinet.trade.mapper.JiangyiSkuJiangyiLinkMapper;
import com.aicabinet.trade.mapper.JiangyiTrainingTicketMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 将邑采集流程编排（CB-023 二期，范围 C）：进入/退出采集模式、触发学习（finishNotify
 * 一次性凭据）、进度聚合（开门状态/学习中/审核列表）。
 *
 * <p>边界（CB-023 台账结论 3）：4.3.x 采集批次操作留在将邑商户 App 人工完成，本期只编排
 * 我们侧可治理的部分。采集开门（§4.2.3）由将邑直接下发、不经我方 gateway——采集模式锁
 * 管住我方营业开门（409），将邑侧开门不产生我方会话，账务天然隔离。</p>
 */
@Service
public class JiangyiGatherService {

    private static final Logger log = LoggerFactory.getLogger(JiangyiGatherService.class);

    private final JiangyiGatherClient jiangyiGatherClient;
    private final JiangyiDeviceMapper jiangyiDeviceMapper;
    private final JiangyiTrainingTicketMapper ticketMapper;
    private final JiangyiSkuJiangyiLinkMapper linkMapper;
    private final String publicBaseUrl;

    public JiangyiGatherService(JiangyiGatherClient jiangyiGatherClient,
                                JiangyiDeviceMapper jiangyiDeviceMapper,
                                JiangyiTrainingTicketMapper ticketMapper,
                                JiangyiSkuJiangyiLinkMapper linkMapper,
                                @Value("${JIANGYI_PUBLIC_BASE_URL:}") String publicBaseUrl) {
        this.jiangyiGatherClient = jiangyiGatherClient;
        this.jiangyiDeviceMapper = jiangyiDeviceMapper;
        this.ticketMapper = ticketMapper;
        this.linkMapper = linkMapper;
        this.publicBaseUrl = publicBaseUrl;
    }

    /** 采集模式是否生效（营业开门链路 409 判据；即时查库，不走缓存——模式切换必须立即生效）。 */
    public boolean isGatherLocked(String deviceId) {
        JiangyiDevice device = jiangyiDeviceMapper.selectById(deviceId);
        return device != null && device.getGatherLockedAt() != null;
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
        return new GatherProgress(device.getGatherLockedAt() != null, doorStatus, training, checks);
    }

    /**
     * 触发学习（§4.4.3）：幂等（同 SKU 有 PENDING ticket 直接返回）；finishNotifyId=UUID
     * 一次性凭据落库；finishNotifyUrl = gateway 公网面（无 token，将邑文档约束）。
     */
    public JiangyiTrainingTicket startTraining(String deviceId, String skuId, String modelName) {
        requireBoundDevice(deviceId);
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "未配置将邑回调公网基址（JIANGYI_PUBLIC_BASE_URL），无法接收学习完成通知");
        }
        var existing = ticketMapper.findPendingBySku(skuId);
        if (existing.isPresent()) {
            return existing.get();
        }
        String finishNotifyId = UUID.randomUUID().toString();
        JiangyiTrainingTicket ticket = new JiangyiTrainingTicket();
        ticket.setDeviceId(deviceId);
        ticket.setSkuId(skuId);
        // 学习是商品集合动作（§4.4.3 参数表无 productId）——记挂接商品 id 便于回执交叉验证
        ticket.setJiangyiProductId(resolveProductId(skuId));
        ticket.setFinishNotifyId(finishNotifyId);
        ticket.setModelName(modelName);
        ticket.setStatus("PENDING");
        ticket.setCreatedAt(Instant.now());
        ticketMapper.insert(ticket);
        try {
            jiangyiGatherClient.commitTraining(modelName,
                    publicBaseUrl + "/jiangyi/api/gather-finish-notify", finishNotifyId);
        } catch (IllegalStateException e) {
            ticketMapper.finish(finishNotifyId, false, Instant.now());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "将邑开始学习失败：" + e.getMessage());
        }
        log.info("jiangyi training started deviceId={} skuId={} modelName={} ticket={}",
                deviceId, skuId, modelName, finishNotifyId);
        return ticket;
    }

    /**
     * 学习完成回调（gateway 转发，无鉴权面）：三重防伪造（CB-023 设计）——
     * ①finishNotifyId 必须命中 PENDING（否则静默 202 语义，不回错误详情）；
     * ②反查将邑已学习商品交叉验证（productId 确实在 trained 列表才 FINISHED）；
     * ③CAS 一次性消费（重复回调 no-op）。
     */
    public void handleFinishNotify(String finishNotifyId, String msg) {
        var ticketOpt = ticketMapper.byFinishNotifyId(finishNotifyId);
        if (ticketOpt.isEmpty() || !"PENDING".equals(ticketOpt.get().getStatus())) {
            log.warn("jiangyi gather finish-notify dropped (no pending ticket) finishNotifyId={}", finishNotifyId);
            return;
        }
        JiangyiTrainingTicket ticket = ticketOpt.get();
        boolean success = "success".equalsIgnoreCase(msg);
        if (!success) {
            ticketMapper.finish(finishNotifyId, false, Instant.now());
            log.warn("jiangyi training failed by notify ticket={} msg={}", finishNotifyId, msg);
            return;
        }
        // 交叉验证：productId 出现在将邑已学习商品中才 FINISHED；否则保留 PENDING 等下轮（异步延迟容忍）
        boolean trained = jiangyiGatherClient.trainedProducts().stream()
                .anyMatch(p -> String.valueOf(p.id()).equals(ticket.getJiangyiProductId()));
        if (trained) {
            ticketMapper.finish(finishNotifyId, true, Instant.now());
            log.info("jiangyi training confirmed ticket={} jiangyiProductId={}", finishNotifyId, ticket.getJiangyiProductId());
        } else {
            log.warn("jiangyi finish-notify but product not trained yet ticket={} jiangyiProductId={} — keep PENDING",
                    finishNotifyId, ticket.getJiangyiProductId());
        }
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
                                 List<GatherCheckItem> checks) {}
}
