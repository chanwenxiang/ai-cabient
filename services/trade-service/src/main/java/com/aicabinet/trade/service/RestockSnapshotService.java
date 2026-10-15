package com.aicabinet.trade.service;

import com.aicabinet.common.dto.GravityDeltaRequest;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 补货关门后：重力/记账快照回写货道实测数量（不结算、不扣库存）。
 * 🔴 视觉快照分支已随旧云识别链退役（2026-10-11，docs/JIANGYI_ONLY_FINAL_VERDICT_2026-10-11.md）：
 * 有视频无重力证据的补货关门回落到记账同步（syncPhysicalFromBook），不再调用任何识别引擎；
 * 将邑补货开门（二期）按协议走补货视频，另接。
 */
@Service
public class RestockSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(RestockSnapshotService.class);
    public static final String MODE_INVENTORY_SNAPSHOT = "INVENTORY_SNAPSHOT";

    private final GravitySettlementHelper gravityHelper;
    private final DeviceSlotService deviceSlotService;
    private final DistributedLockService distributedLockService;
    private final ShoppingSessionMapper sessionRepository;

    public RestockSnapshotService(GravitySettlementHelper gravityHelper,
                                  DeviceSlotService deviceSlotService,
                                  DistributedLockService distributedLockService,
                                  ShoppingSessionMapper sessionRepository) {
        this.gravityHelper = gravityHelper;
        this.deviceSlotService = deviceSlotService;
        this.distributedLockService = distributedLockService;
        this.sessionRepository = sessionRepository;
    }

    /**
     * 该补货任务的关门会话是否产生过**真实重力实测**（P0-2，2026-10-15）：
     * 有真实实测 ⇒ 补货完成时保留快照值，不用回写后账面覆盖（差异留给 SLOT_DISCREPANCY 闭环）。
     * 会话 gravityDeltas 解析出任一非空增量即认定真实实测；无会话/无增量返回 false。
     */
    @Transactional(readOnly = true)
    public boolean hasRealGravitySnapshot(Long taskId) {
        if (taskId == null) {
            return false;
        }
        for (ShoppingSession session : sessionRepository.findByReplenishmentTaskId(taskId)) {
            List<GravityDeltaRequest.GravityDeltaItem> deltas =
                    gravityHelper.parse(session.getGravityDeltas());
            if (!deltas.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public int applySnapshot(ShoppingSession session) {
        return runWithRestockSnapshotLock(session.getSessionId(), () -> doApplySnapshot(session));
    }

    private int doApplySnapshot(ShoppingSession session) {
        String deviceId = session.getDeviceId();
        String refId = session.getSessionId();
        List<GravityDeltaRequest.GravityDeltaItem> deltas = gravityHelper.parse(session.getGravityDeltas());

        if (gravityHelper.hasSlotSpecificDeltas(deltas)) {
            int n = applySlotGravity(deviceId, deltas, refId);
            log.info("restock slot-gravity snapshot device={} session={} slots={}", deviceId, refId, n);
            return n;
        }

        int n = applySkuGravity(deviceId, deltas, refId);
        log.info("restock sku-gravity snapshot device={} session={} slots={}", deviceId, refId, n);
        return n;
    }

    private int applySlotGravity(String deviceId,
                                 List<GravityDeltaRequest.GravityDeltaItem> deltas,
                                 String refId) {
        Map<String, Integer> bookBySlot = deviceSlotService.loadBookQtyBySlot(deviceId);
        Map<String, Integer> physical = new HashMap<>();
        for (GravityDeltaRequest.GravityDeltaItem delta : deltas) {
            if (delta.slotId() == null || delta.slotId().isBlank()) {
                continue;
            }
            String slotCode = delta.slotId().trim().toUpperCase();
            int book = bookBySlot.getOrDefault(slotCode, 0);
            physical.put(slotCode, Math.max(0, book + delta.delta()));
        }
        return deviceSlotService.applyPhysicalSnapshot(deviceId, physical, "GRAVITY_SLOT", refId);
    }

    private int applySkuGravity(String deviceId,
                                List<GravityDeltaRequest.GravityDeltaItem> deltas,
                                String refId) {
        Map<String, Integer> skuNet = new HashMap<>();
        for (GravityDeltaRequest.GravityDeltaItem delta : deltas) {
            if ((delta.slotId() == null || delta.slotId().isBlank()) && delta.delta() != 0) {
                skuNet.merge(delta.skuId(), delta.delta(), Integer::sum);
            }
        }
        if (skuNet.isEmpty()) {
            return deviceSlotService.syncPhysicalFromBook(deviceId, refId);
        }
        Map<String, Integer> bookBySlot = deviceSlotService.loadBookQtyBySlot(deviceId);
        Map<String, Integer> physical = new HashMap<>();
        for (Map.Entry<String, Integer> entry : skuNet.entrySet()) {
            applySkuNetDeltaToPhysical(deviceId, entry.getKey(), entry.getValue(), bookBySlot, physical);
        }
        return deviceSlotService.applyPhysicalSnapshot(deviceId, physical, "GRAVITY_SKU", refId);
    }

    private void applySkuNetDeltaToPhysical(String deviceId, String skuId, int netDelta,
                                            Map<String, Integer> bookBySlot,
                                            Map<String, Integer> physical) {
        List<DeviceSlotService.SlotBookView> slots =
                deviceSlotService.listEnabledSlotsForSku(deviceId, skuId);
        if (slots.isEmpty()) {
            return;
        }
        if (netDelta >= 0) {
            DeviceSlotService.SlotBookView primary = slots.get(0);
            int book = bookBySlot.getOrDefault(primary.slotCode(), 0);
            physical.put(primary.slotCode(), Math.max(0, book + netDelta));
            return;
        }
        applyNegativeSkuDelta(slots, -netDelta, bookBySlot, physical);
    }

    private static void applyNegativeSkuDelta(List<DeviceSlotService.SlotBookView> slots, int remaining,
                                              Map<String, Integer> bookBySlot,
                                              Map<String, Integer> physical) {
        int left = remaining;
        List<DeviceSlotService.SlotBookView> ordered = slots.stream()
                .sorted((a, b) -> Integer.compare(
                        bookBySlot.getOrDefault(b.slotCode(), 0),
                        bookBySlot.getOrDefault(a.slotCode(), 0)))
                .toList();
        for (DeviceSlotService.SlotBookView slot : ordered) {
            int book = bookBySlot.getOrDefault(slot.slotCode(), 0);
            if (left <= 0) {
                physical.putIfAbsent(slot.slotCode(), book);
                continue;
            }
            int take = ordered.size() == 1 || book <= 0 ? left : Math.min(book, left);
            physical.put(slot.slotCode(), Math.max(0, book - take));
            left -= take;
        }
        if (left > 0 && !ordered.isEmpty()) {
            String code = ordered.get(0).slotCode();
            physical.put(code, Math.max(0, physical.getOrDefault(code, 0) - left));
        }
    }

    private <T> T runWithRestockSnapshotLock(String sessionId, java.util.function.Supplier<T> action) {
        String key = SessionService.sessionLifeLockKey(sessionId);
        if (!distributedLockService.tryLock(key, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "补货快照处理中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            distributedLockService.unlock(key);
        }
    }
}
