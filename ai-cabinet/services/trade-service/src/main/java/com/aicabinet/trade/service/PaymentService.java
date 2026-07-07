package com.aicabinet.trade.service;

import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.RechargeOrderDto;
import com.aicabinet.common.dto.RechargeRequest;
import com.aicabinet.common.dto.WxPayParams;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.domain.RechargeOrder;
import com.aicabinet.trade.domain.UserAccount;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.payment.WeChatPayClient;
import com.aicabinet.trade.payment.WeChatPayNotifyService;
import com.aicabinet.trade.payment.WeChatPayV3Signer;
import com.aicabinet.trade.repository.RechargeOrderRepository;
import com.aicabinet.trade.repository.UserAccountRepository;
import com.aicabinet.trade.repository.UserInfoRepository;
import com.aicabinet.trade.support.ApiMessages;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final RechargeOrderRepository rechargeOrderRepository;
    private final UserInfoRepository userInfoRepository;
    private final UserAccountRepository userAccountRepository;
    private final WeChatPayProperties weChatPayProperties;
    private final SecurityProperties securityProperties;
    private final WeChatPayClient weChatPayClient;
    private final WeChatPayV3Signer v3Signer;
    private final WeChatPayNotifyService notifyService;

    public PaymentService(RechargeOrderRepository rechargeOrderRepository,
                          UserInfoRepository userInfoRepository,
                          UserAccountRepository userAccountRepository,
                          WeChatPayProperties weChatPayProperties,
                          SecurityProperties securityProperties,
                          WeChatPayClient weChatPayClient,
                          WeChatPayV3Signer v3Signer,
                          WeChatPayNotifyService notifyService) {
        this.rechargeOrderRepository = rechargeOrderRepository;
        this.userInfoRepository = userInfoRepository;
        this.userAccountRepository = userAccountRepository;
        this.weChatPayProperties = weChatPayProperties;
        this.securityProperties = securityProperties;
        this.weChatPayClient = weChatPayClient;
        this.v3Signer = v3Signer;
        this.notifyService = notifyService;
    }

    @Transactional
    public WxPayParams createRechargePrepay(Long userId, RechargeRequest request, String clientIp) {
        RechargeOrder order = new RechargeOrder();
        order.setOrderId("R" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
        order.setUserId(userId);
        order.setAmountCents(request.amountCents());
        order.setChannel(request.channel());
        order.setStatus("PENDING");
        rechargeOrderRepository.save(order);

        if (weChatPayProperties.isConfigured()) {
            UserInfo user = userInfoRepository.findById(userId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.USER_NOT_FOUND));
            if (user.getWxOpenId() == null || user.getWxOpenId().isBlank()) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, ApiMessages.WX_OPENID_NOT_BOUND);
            }
            String prepayId = weChatPayClient.unifiedOrderJsapi(
                    user.getWxOpenId(), order.getOrderId(), order.getAmountCents(),
                    "AI开门柜充值", clientIp);
            order.setWxPrepayId(prepayId);
            rechargeOrderRepository.save(order);

            String timeStamp = String.valueOf(Instant.now().getEpochSecond());
            String nonceStr = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            String packageValue = "prepay_id=" + prepayId;
            String paySign = v3Signer.signJsapi(
                    weChatPayProperties.appId(), timeStamp, nonceStr, packageValue,
                    weChatPayProperties.privateKey());
            return new WxPayParams(timeStamp, nonceStr, packageValue, "RSA", paySign,
                    Map.of("orderId", order.getOrderId(), "mode", "live", "apiVersion", "v3"));
        }

        if (!securityProperties.mockEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ApiMessages.WECHAT_PAY_NOT_CONFIGURED);
        }
        order.setWxPrepayId("mock_prepay_" + order.getOrderId());
        rechargeOrderRepository.save(order);
        return new WxPayParams(
                String.valueOf(Instant.now().getEpochSecond()),
                UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                "prepay_id=" + order.getWxPrepayId(),
                "RSA",
                "MOCK_SIGN",
                Map.of("orderId", order.getOrderId(), "mode", "mock", "apiVersion", "v3")
        );
    }

    @Transactional
    public void confirmRechargeMock(String orderId) {
        RechargeOrder order = rechargeOrderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
        creditRecharge(order);
    }

    @Transactional
    public void handleWeChatNotify(String body,
                                   String timestamp,
                                   String nonce,
                                   String signature,
                                   String serial) {
        JsonNode transaction = notifyService.parseAndVerify(body, timestamp, nonce, signature, serial);
        if (!"SUCCESS".equals(transaction.path("trade_state").asText())) {
            log.info("wechat notify ignored trade_state={}", transaction.path("trade_state").asText());
            return;
        }
        String outTradeNo = transaction.path("out_trade_no").asText(null);
        if (outTradeNo == null) {
            throw new IllegalArgumentException(ApiMessages.MISSING_OUT_TRADE_NO);
        }
        rechargeOrderRepository.findById(outTradeNo).ifPresent(order -> {
            if (!"PAID".equals(order.getStatus())) {
                String txnId = transaction.path("transaction_id").asText(null);
                if (txnId != null && !txnId.isBlank()) {
                    order.setWxTransactionId(txnId);
                }
                creditRecharge(order);
            }
        });
    }

    @Transactional(readOnly = true)
    public PageResult<RechargeOrderDto> listMyRecharges(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50));
        Page<RechargeOrder> result = rechargeOrderRepository.search(null, userId, pageable);
        return new PageResult<>(
                result.getContent().stream().map(PaymentService::toDto).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements()
        );
    }

    @Transactional
    public RechargeOrderDto getRechargeOrder(Long userId, String orderId) {
        RechargeOrder order = requireOwnedOrder(userId, orderId);
        syncPendingFromWeChat(order);
        return toDto(order);
    }

    @Transactional
    public RechargeOrderDto cancelRecharge(Long userId, String orderId) {
        RechargeOrder order = requireOwnedOrder(userId, orderId);
        if (!"PENDING".equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.ORDER_NOT_PENDING);
        }
        if (weChatPayProperties.isConfigured()) {
            try {
                weChatPayClient.closeOrder(orderId);
            } catch (Exception e) {
                log.warn("wechat close order failed orderId={}, trying query sync", orderId, e);
                syncPendingFromWeChat(order);
                if ("PAID".equals(order.getStatus())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.ORDER_ALREADY_PAID);
                }
            }
        }
        order.setStatus("CANCELLED");
        rechargeOrderRepository.save(order);
        log.info("recharge cancelled orderId={}", orderId);
        return toDto(order);
    }

    @Transactional
    public RechargeOrderDto refundRecharge(String orderId, String reason) {
        RechargeOrder order = rechargeOrderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
        if (!"PAID".equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.ORDER_NOT_PAID);
        }
        UserAccount account = userAccountRepository.findById(order.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ACCOUNT_NOT_FOUND));
        if (account.getBalanceCents() < order.getAmountCents()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.INSUFFICIENT_REFUND);
        }

        if (weChatPayProperties.isConfigured()) {
            String outRefundNo = "RF" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase();
            weChatPayClient.createRefund(
                    orderId, outRefundNo, order.getAmountCents(), order.getAmountCents(), reason);
        } else if (!securityProperties.mockEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ApiMessages.WECHAT_PAY_NOT_CONFIGURED);
        }

        account.setBalanceCents(account.getBalanceCents() - order.getAmountCents());
        userAccountRepository.save(account);

        order.setStatus("REFUNDED");
        order.setRefundedAt(Instant.now());
        rechargeOrderRepository.save(order);
        log.info("recharge refunded orderId={} user={} amount={}", orderId, order.getUserId(), order.getAmountCents());
        return toDto(order);
    }

    private RechargeOrder requireOwnedOrder(Long userId, String orderId) {
        RechargeOrder order = rechargeOrderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
        if (!order.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ApiMessages.ORDER_ACCESS_DENIED);
        }
        return order;
    }

    private void syncPendingFromWeChat(RechargeOrder order) {
        if (!"PENDING".equals(order.getStatus()) || !weChatPayProperties.isConfigured()) {
            return;
        }
        try {
            JsonNode remote = weChatPayClient.queryByOutTradeNo(order.getOrderId());
            String tradeState = remote.path("trade_state").asText("");
            if ("SUCCESS".equals(tradeState)) {
                String txnId = remote.path("transaction_id").asText(null);
                if (txnId != null && !txnId.isBlank()) {
                    order.setWxTransactionId(txnId);
                }
                creditRecharge(order);
            } else if ("CLOSED".equals(tradeState) || "REVOKED".equals(tradeState) || "PAYERROR".equals(tradeState)) {
                order.setStatus("CANCELLED");
                rechargeOrderRepository.save(order);
            }
        } catch (Exception e) {
            log.debug("wechat query sync skipped orderId={}: {}", order.getOrderId(), e.getMessage());
        }
    }

    private static RechargeOrderDto toDto(RechargeOrder order) {
        return new RechargeOrderDto(
                order.getOrderId(),
                order.getUserId(),
                order.getAmountCents(),
                order.getChannel(),
                order.getStatus(),
                order.getWxPrepayId(),
                order.getWxTransactionId(),
                order.getCreatedAt(),
                order.getPaidAt(),
                order.getRefundedAt()
        );
    }

    private void creditRecharge(RechargeOrder order) {
        order.setStatus("PAID");
        order.setPaidAt(Instant.now());
        rechargeOrderRepository.save(order);

        UserAccount account = userAccountRepository.findById(order.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ACCOUNT_NOT_FOUND));
        account.setBalanceCents(account.getBalanceCents() + order.getAmountCents());
        userAccountRepository.save(account);
        log.info("recharge credited user={} amount={}", order.getUserId(), order.getAmountCents());
    }
}
