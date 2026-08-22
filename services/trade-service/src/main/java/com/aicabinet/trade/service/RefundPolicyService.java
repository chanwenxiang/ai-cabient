package com.aicabinet.trade.service;

import com.aicabinet.common.enums.RefundPolicy;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

@Service
public class RefundPolicyService {

    public static final String DEFAULT_POLICY_KEY = SystemConfigService.REFUND_DEFAULT_POLICY;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final DeviceInfoMapper deviceRepository;
    private final SystemConfigService systemConfigService;
    private final PaymentOperationMapper paymentOperationMapper;

    public RefundPolicyService(DeviceInfoMapper deviceRepository,
                               SystemConfigService systemConfigService,
                               PaymentOperationMapper paymentOperationMapper) {
        this.deviceRepository = deviceRepository;
        this.systemConfigService = systemConfigService;
        this.paymentOperationMapper = paymentOperationMapper;
    }

    @Transactional(readOnly = true)
    public RefundPolicy globalDefault() {
        return RefundPolicy.fromOrDefault(
                systemConfigService.getValue(DEFAULT_POLICY_KEY, RefundPolicy.AUTO_REFUND.name()),
                RefundPolicy.AUTO_REFUND);
    }

    @Transactional(readOnly = true)
    public RefundPolicy resolveForDevice(String deviceId) {
        if (deviceId != null && !deviceId.isBlank()) {
            DeviceInfo device = deviceRepository.findById(deviceId.trim()).orElse(null);
            if (device != null) {
                RefundPolicy override = RefundPolicy.from(device.getRefundPolicy());
                if (override != null) {
                    return override;
                }
            }
        }
        return globalDefault();
    }

    @Transactional(readOnly = true)
    public boolean allowsAutoRefund(String deviceId) {
        return resolveForDevice(deviceId) == RefundPolicy.AUTO_REFUND;
    }

    @Transactional(readOnly = true)
    public boolean allowsConsumerPartialRefund() {
        return systemConfigService.getBoolean(SystemConfigService.REFUND_SELF_PARTIAL_ENABLED, true);
    }

    /**
     * 消费者自助退款限额：柜机策略 + 时限 + 单笔上限 + 日次数。
     */
    @Transactional(readOnly = true)
    public void assertConsumerSelfRefundAllowed(CabinetOrder order, int refundCents, boolean partial) {
        if (order == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "订单无效");
        }
        if (!allowsAutoRefund(order.getDeviceId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该柜机未开启自助退款，请提交账单申诉，由运营审核后处理");
        }
        if (partial && !allowsConsumerPartialRefund()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "暂未开放按行自助退款，请全额退款或联系客服");
        }
        int maxHours = systemConfigService.getInt(SystemConfigService.REFUND_SELF_MAX_HOURS, 24);
        if (maxHours > 0 && order.getCreatedAt() != null
                && order.getCreatedAt().isBefore(Instant.now().minus(maxHours, ChronoUnit.HOURS))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "已超过自助退款时限（" + maxHours + " 小时），请提交申诉");
        }
        int maxCents = systemConfigService.getInt(SystemConfigService.REFUND_SELF_MAX_CENTS, 5000);
        if (maxCents > 0 && refundCents > maxCents) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "单笔自助退款上限为 ¥" + String.format("%.2f", maxCents / 100.0) + "，请减少退款金额或联系客服");
        }
        int maxDaily = systemConfigService.getInt(SystemConfigService.REFUND_SELF_MAX_DAILY, 3);
        if (maxDaily > 0 && order.getUserId() != null) {
            Instant dayStart = Instant.now().atZone(ZONE).toLocalDate().atStartOfDay(ZONE).toInstant();
            long used = paymentOperationMapper.countRefundsSince(order.getUserId(), dayStart);
            if (used >= maxDaily) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "今日自助退款次数已达上限（" + maxDaily + " 次），请明日再试或联系客服");
            }
        }
    }

    public static String normalizeStored(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim().toUpperCase();
        if ("INHERIT".equals(v) || "DEFAULT".equals(v) || "NULL".equals(v) || "-".equals(v)) {
            return null;
        }
        RefundPolicy p = RefundPolicy.from(v);
        return p != null ? p.name() : null;
    }
}
