package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.payment.WeChatProfitSharingService;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Service
public class RevenueSplitService {

    private static final Logger log = LoggerFactory.getLogger(RevenueSplitService.class);

    private final OrderRevenueSplitMapper splitRepository;
    private final DeviceInfoMapper deviceRepository;
    private final MerchantMapper merchantRepository;
    private final WeChatProfitSharingService profitSharingService;

    public RevenueSplitService(OrderRevenueSplitMapper splitRepository,
                               DeviceInfoMapper deviceRepository,
                               MerchantMapper merchantRepository,
                               WeChatProfitSharingService profitSharingService) {
        this.splitRepository = splitRepository;
        this.deviceRepository = deviceRepository;
        this.merchantRepository = merchantRepository;
        this.profitSharingService = profitSharingService;
    }

    @Transactional
    public Optional<OrderRevenueSplit> recordSplit(CabinetOrder order) {
        if (splitRepository.findByOrderId(order.getOrderId()).isPresent()) {
            return splitRepository.findByOrderId(order.getOrderId());
        }
        DeviceInfo device = deviceRepository.findById(order.getDeviceId()).orElse(null);
        if (device == null || device.getMerchantId() == null || device.getMerchantId().isBlank()) {
            log.debug("skip revenue split: no merchant on device {}", order.getDeviceId());
            return Optional.empty();
        }
        Merchant merchant = merchantRepository.findById(device.getMerchantId()).orElse(null);
        if (merchant == null || !"ACTIVE".equalsIgnoreCase(merchant.getStatus())) {
            log.warn("skip revenue split: merchant missing or inactive {}", device.getMerchantId());
            return Optional.empty();
        }

        long gross = order.getTotalAmountCents();
        long platform = gross * merchant.getPlatformRateBps() / 10_000L;
        long merchantShare = gross - platform;

        OrderRevenueSplit split = new OrderRevenueSplit();
        split.setSplitId("S" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
        split.setOrderId(order.getOrderId());
        split.setMerchantId(merchant.getMerchantId());
        split.setDeviceId(order.getDeviceId());
        split.setGrossCents(gross);
        split.setPlatformCents(platform);
        split.setMerchantCents(merchantShare);
        split.setSettleAfter(LocalDate.now().plusDays(1));
        split.setSettlementBatchNo("MS-" + LocalDate.now() + "-" + merchant.getMerchantId());
        if (merchant.getWechatReceiverId() == null || merchant.getWechatReceiverId().isBlank()) {
            split.setStatus("LEDGER_ONLY");
        } else if (!profitSharingService.isApiReady()) {
            split.setStatus("ACCRUED");
        } else {
            split.setStatus("ACCRUED");
        }
        splitRepository.save(split);
        log.info("分账记账 order={} merchant={} gross={} platform={} merchantShare={}",
                order.getOrderId(), merchant.getMerchantId(), gross, platform, merchantShare);
        return Optional.of(split);
    }

    /**
     * 订单全额退款后冲正分账：已有分账记录改为 VOIDED，避免账本仍显示应分金额。
     */
    @Transactional
    public void voidSplitOnFullRefund(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return;
        }
        splitRepository.findByOrderId(orderId).ifPresent(split -> {
            if ("VOIDED".equalsIgnoreCase(split.getStatus()) || "REVERSED".equalsIgnoreCase(split.getStatus())) {
                return;
            }
            split.setStatus("VOIDED");
            splitRepository.save(split);
            log.info("分账已冲正（全额退款） order={} splitId={}", orderId, split.getSplitId());
        });
    }
}
