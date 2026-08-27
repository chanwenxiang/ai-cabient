package com.aicabinet.trade.service;

import com.aicabinet.common.dto.GravityDeltaRequest;
import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.domain.ShoppingSession;
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
 * 补货关门后：重力/视觉快照回写货道实测数量（不结算、不扣库存）。
 */
@Service
public class RestockSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(RestockSnapshotService.class);
    public static final String MODE_INVENTORY_SNAPSHOT = "INVENTORY_SNAPSHOT";

    private final GravitySettlementHelper gravityHelper;
    private final DeviceSlotService deviceSlotService;
    private final VisionServiceClient visionClient;
    private final DistributedLockService distributedLockService;

    public RestockSnapshotService(GravitySettlementHelper gravityHelper,
                                  DeviceSlotService deviceSlotService,
                                  VisionServiceClient visionClient,
                                  DistributedLockService distributedLockService) {
        this.gravityHelper = gravityHelper;
        this.deviceSlotService = deviceSlotService;
        this.visionClient = visionClient;
        this.distributedLockService = distributedLockService;
    }

    @Transactional
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

        if (hasRecognizableVideo(session)) {
            try {
                VisionServiceClient.RecognitionResult result =
                        visionClient.recognizeInventorySnapshot(session);
                int n = applyVisionSkuCounts(deviceId, result.items(), refId);
                log.info("restock vision snapshot device={} session={} slots={}", deviceId, refId, n);
                return n;
            } catch (Exception e) {
                log.warn("restock vision snapshot failed session={}, fallback gravity", refId, e);
            }
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
            if (delta.slotId() != null && !delta.slotId().isBlank()) {
                continue;
            }
            if (delta.delta() == 0) {
                continue;
            }
            skuNet.merge(delta.skuId(), delta.delta(), Integer::sum);
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

    private int applyVisionSkuCounts(String deviceId,
                                     List<VisionServiceClient.RecognizedItem> items,
                                     String refId) {
        Map<String, Integer> skuTotals = new HashMap<>();
        for (VisionServiceClient.RecognizedItem item : items) {
            if (item.quantity() <= 0) {
                continue;
            }
            skuTotals.merge(item.skuId(), item.quantity(), Integer::sum);
        }
        return deviceSlotService.allocateSkuCountsToSlots(deviceId, skuTotals, "VISION", refId);
    }

    private static boolean hasRecognizableVideo(ShoppingSession session) {
        return session.getVideoUri() != null && !session.getVideoUri().isBlank();
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
