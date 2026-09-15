package com.aicabinet.trade.service;

import com.aicabinet.common.dto.GravityDeltaRequest;
import com.aicabinet.common.dto.LiveCartDto;
import com.aicabinet.common.dto.LiveCartUpdateRequest;
import com.aicabinet.common.dto.SessionCartRequest;
import com.aicabinet.common.dto.SessionDto;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.support.ApiMessages;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话购物车：点选同步（重力证据）与视觉实时购物车展示。
 * <p>会话锁 / DTO / 所有者校验委托 {@link SessionService}，避免行为漂移。</p>
 */
@Service
public class SessionLiveCartService {
    private static final Logger log = LoggerFactory.getLogger(SessionLiveCartService.class);

    private final ShoppingSessionMapper repository;
    private final GravitySettlementHelper gravityHelper;
    private final InventoryLotService inventoryLotService;
    private final ObjectMapper objectMapper;
    private final SessionService sessionService;

    public SessionLiveCartService(ShoppingSessionMapper repository,
                                  GravitySettlementHelper gravityHelper,
                                  InventoryLotService inventoryLotService,
                                  ObjectMapper objectMapper,
                                  @Lazy SessionService sessionService) {
        this.repository = repository;
        this.gravityHelper = gravityHelper;
        this.inventoryLotService = inventoryLotService;
        this.objectMapper = objectMapper;
        this.sessionService = sessionService;
    }

    /** 演示/开发：消费者点选商品同步到会话，关门 mock 结算时按此列表扣款。 */
    @Transactional
    public SessionDto updateSessionCart(Long userId, String sessionId, SessionCartRequest request) {
        return sessionService.runWithSessionLifeLock(sessionId,
                () -> doUpdateSessionCart(userId, sessionId, request));
    }

    private SessionDto doUpdateSessionCart(Long userId, String sessionId, SessionCartRequest request) {
        ShoppingSession session = repository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        sessionService.requireSessionOwner(userId, session);
        if (!EnumSet.of(SessionState.CREATED, SessionState.OPENING, SessionState.SHOPPING).contains(session.getState())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.SESSION_STATE_INVALID);
        }
        List<GravityDeltaRequest.GravityDeltaItem> deltas =
                (request.items() == null ? List.<SessionCartRequest.CartItem>of() : request.items())
                .stream()
                .filter(item -> item.qty() > 0)
                .map(item -> {
                    int available = inventoryLotService.availableSellableQuantity(
                            session.getDeviceId(), item.skuId());
                    if (item.qty() > available) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                "库存不足 sku=" + item.skuId() + " 当前=" + available + " 选购=" + item.qty());
                    }
                    return new GravityDeltaRequest.GravityDeltaItem(item.skuId(), -item.qty(), null);
                })
                .toList();
        session.setGravityDeltas(deltas.isEmpty() ? null : gravityHelper.fromRequestItems(deltas));
        repository.save(session);
        log.info("session cart updated session={} items={}", sessionId, deltas.size());
        return sessionService.toDto(session);
    }

    /**
     * 第三方识别推送实时购物车（internal）。仅更新展示字段，不触发扣款。
     */
    @Transactional
    public LiveCartDto updateLiveCartFromVision(String sessionId, LiveCartUpdateRequest request) {
        return sessionService.runWithSessionLifeLock(sessionId,
                () -> doUpdateLiveCartFromVision(sessionId, request));
    }

    private LiveCartDto doUpdateLiveCartFromVision(String sessionId, LiveCartUpdateRequest request) {
        ShoppingSession session = repository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        if (!EnumSet.of(SessionState.CREATED, SessionState.OPENING, SessionState.SHOPPING).contains(session.getState())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.SESSION_STATE_INVALID);
        }
        String mode = request == null ? "REPLACE" : request.resolvedMode();
        Map<String, LiveCartDto.LiveCartLine> bySku = seedCartFromExisting(session, mode);
        List<LiveCartUpdateRequest.LiveCartItem> incoming =
                request == null || request.items() == null ? List.of() : request.items();
        mergeIncomingCartItems(bySku, incoming, mode);
        if ("REPLACE".equals(mode) && incoming.isEmpty()) {
            bySku.clear();
        }
        List<LiveCartDto.LiveCartLine> lines = new ArrayList<>(bySku.values());
        session.setLiveCart(writeLiveCartJson(lines));
        repository.save(session);
        log.info("live cart updated session={} mode={} lines={}", sessionId, mode, lines.size());
        return toLiveCartDto(sessionId, lines);
    }

    private Map<String, LiveCartDto.LiveCartLine> seedCartFromExisting(ShoppingSession session, String mode) {
        Map<String, LiveCartDto.LiveCartLine> bySku = new LinkedHashMap<>();
        if (!"DELTA".equals(mode)) {
            return bySku;
        }
        for (LiveCartDto.LiveCartLine existing : parseLiveCartLines(session.getLiveCart())) {
            bySku.put(existing.skuId(), existing);
        }
        return bySku;
    }

    private void mergeIncomingCartItems(Map<String, LiveCartDto.LiveCartLine> bySku,
                                        List<LiveCartUpdateRequest.LiveCartItem> incoming,
                                        String mode) {
        for (LiveCartUpdateRequest.LiveCartItem item : incoming) {
            if (item == null || item.skuId() == null || item.skuId().isBlank()) {
                continue;
            }
            if ("DELTA".equals(mode)) {
                applyDeltaCartItem(bySku, item);
            } else {
                applyReplaceCartItem(bySku, item);
            }
        }
    }

    private static void applyDeltaCartItem(Map<String, LiveCartDto.LiveCartLine> bySku,
                                           LiveCartUpdateRequest.LiveCartItem item) {
        String sku = item.skuId().trim();
        LiveCartDto.LiveCartLine prev = bySku.get(sku);
        int prevQty = prev == null ? 0 : prev.quantity();
        int nextQty = Math.max(0, prevQty + item.quantity());
        if (nextQty <= 0) {
            bySku.remove(sku);
            return;
        }
        int unit;
        if (item.unitPriceCents() != null && item.unitPriceCents() > 0) {
            unit = item.unitPriceCents();
        } else if (prev != null) {
            unit = prev.unitPriceCents();
        } else {
            unit = 0;
        }
        String name;
        if (item.skuName() != null && !item.skuName().isBlank()) {
            name = item.skuName();
        } else if (prev != null) {
            name = prev.skuName();
        } else {
            name = sku;
        }
        bySku.put(sku, new LiveCartDto.LiveCartLine(sku, name, nextQty, unit, unit * nextQty));
    }

    private static void applyReplaceCartItem(Map<String, LiveCartDto.LiveCartLine> bySku,
                                             LiveCartUpdateRequest.LiveCartItem item) {
        if (item.quantity() <= 0) {
            return;
        }
        String sku = item.skuId().trim();
        int unit = item.unitPriceCents() == null ? 0 : Math.max(0, item.unitPriceCents());
        String name = item.skuName() == null || item.skuName().isBlank() ? sku : item.skuName();
        bySku.put(sku, new LiveCartDto.LiveCartLine(sku, name, item.quantity(), unit, unit * item.quantity()));
    }

    @Transactional(readOnly = true)
    public LiveCartDto getLiveCart(Long userId, String sessionId) {
        ShoppingSession session = repository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        sessionService.requireSessionOwner(userId, session);
        return toLiveCartDto(sessionId, parseLiveCartLines(session.getLiveCart()));
    }

    private List<LiveCartDto.LiveCartLine> parseLiveCartLines(String json) {
        if (json == null || json.isBlank() || objectMapper == null) {
            return List.of();
        }
        try {
            List<LiveCartDto.LiveCartLine> lines = objectMapper.readValue(json, new TypeReference<>() {});
            return lines == null ? List.of() : lines;
        } catch (Exception e) {
            log.warn("parse live_cart failed", e);
            return List.of();
        }
    }

    private String writeLiveCartJson(List<LiveCartDto.LiveCartLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(lines);
        } catch (Exception e) {
            throw new IllegalStateException("serialize live_cart failed", e);
        }
    }

    private static LiveCartDto toLiveCartDto(String sessionId, List<LiveCartDto.LiveCartLine> lines) {
        int qty = 0;
        int amount = 0;
        for (LiveCartDto.LiveCartLine line : lines) {
            qty += line.quantity();
            amount += line.lineAmountCents();
        }
        return new LiveCartDto(sessionId, lines, qty, amount);
    }
}
