package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.constants.PayChannels;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.UserAccount;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.payment.AlipayPayClient;
import com.aicabinet.trade.payment.WeChatPayClient;
import com.aicabinet.trade.repository.UserAccountRepository;
import com.aicabinet.trade.repository.UserInfoRepository;
import com.aicabinet.trade.support.ApiMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

@Service
public class OrderPaymentService {

    private static final Logger log = LoggerFactory.getLogger(OrderPaymentService.class);

    private final UserInfoRepository userInfoRepository;
    private final UserAccountRepository userAccountRepository;
    private final PayScoreService payScoreService;
    private final WeChatPayClient weChatPayClient;
    private final AlipayPayClient alipayPayClient;
    private final WeChatPayProperties weChatPayProperties;
    private final SecurityProperties securityProperties;

    public OrderPaymentService(UserInfoRepository userInfoRepository,
                               UserAccountRepository userAccountRepository,
                               PayScoreService payScoreService,
                               WeChatPayClient weChatPayClient,
                               AlipayPayClient alipayPayClient,
                               WeChatPayProperties weChatPayProperties,
                               SecurityProperties securityProperties) {
        this.userInfoRepository = userInfoRepository;
        this.userAccountRepository = userAccountRepository;
        this.payScoreService = payScoreService;
        this.weChatPayClient = weChatPayClient;
        this.alipayPayClient = alipayPayClient;
        this.weChatPayProperties = weChatPayProperties;
        this.securityProperties = securityProperties;
    }

    @Transactional
    public void chargeOrder(CabinetOrder order) {
        if (order.getUserId() >= CabinetConstants.OPERATOR_USER_ID_START) {
            order.setPayChannel(PayChannels.BALANCE);
            return;
        }
        UserInfo user = userInfoRepository.findById(order.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.USER_NOT_FOUND));

        PayScoreService.ChargeResult charge = payScoreService.charge(
                user, order.getOrderId(), order.getTotalAmountCents(), "AI开门柜购物");
        if (!PayChannels.BALANCE.equals(charge.channel())) {
            order.setPayChannel(charge.channel());
            order.setPayTradeNo(charge.tradeNo());
            log.info("order charged channel={} order={} tradeNo={}", charge.channel(), order.getOrderId(), charge.tradeNo());
            return;
        }

        deductBalance(order.getUserId(), order.getTotalAmountCents());
        order.setPayChannel(PayChannels.BALANCE);
    }

    @Transactional
    public void applyPaymentDelta(CabinetOrder order, int deltaCents) {
        if (deltaCents == 0 || order.getUserId() >= CabinetConstants.OPERATOR_USER_ID_START) {
            return;
        }
        if (deltaCents > 0) {
            chargeDelta(order, deltaCents);
        } else {
            refundAmount(order, -deltaCents, "争议改单退差");
        }
    }

    @Transactional
    public void refundOrder(CabinetOrder order, int amountCents, String reason) {
        if (amountCents <= 0 || order.getUserId() >= CabinetConstants.OPERATOR_USER_ID_START) {
            return;
        }
        refundAmount(order, amountCents, reason);
        order.setRefundedAt(Instant.now());
    }

    private void chargeDelta(CabinetOrder order, int deltaCents) {
        String channel = order.getPayChannel() != null ? order.getPayChannel() : PayChannels.BALANCE;
        if (PayChannels.WECHAT.equalsIgnoreCase(channel) || PayChannels.ALIPAY.equalsIgnoreCase(channel)) {
            UserInfo user = userInfoRepository.findById(order.getUserId()).orElseThrow();
            PayScoreService.ChargeResult charge = payScoreService.charge(user, order.getOrderId() + "-ADJ", deltaCents, reasonOrDefault(null));
            if (!PayChannels.BALANCE.equals(charge.channel())) {
                log.info("order adjust charge channel={} order={} delta={}", charge.channel(), order.getOrderId(), deltaCents);
                return;
            }
        }
        deductBalance(order.getUserId(), deltaCents);
    }

    private void refundAmount(CabinetOrder order, int amountCents, String reason) {
        String channel = order.getPayChannel() != null ? order.getPayChannel() : PayChannels.BALANCE;
        if (PayChannels.WECHAT.equalsIgnoreCase(channel)) {
            refundWeChat(order, amountCents, reason);
            return;
        }
        if (PayChannels.ALIPAY.equalsIgnoreCase(channel)) {
            refundAlipay(order, amountCents, reason);
            return;
        }
        creditBalance(order.getUserId(), amountCents);
    }

    private void refundWeChat(CabinetOrder order, int amountCents, String reason) {
        if (weChatPayProperties.isConfigured() && order.getPayTradeNo() != null) {
            String outRefundNo = "RF" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase();
            weChatPayClient.createRefund(order.getOrderId(), outRefundNo, amountCents, order.getTotalAmountCents(), reasonOrDefault(reason));
            log.info("wechat order refund order={} amount={} (原路退回零钱)", order.getOrderId(), amountCents);
            return;
        }
        if (securityProperties.mockEnabled()) {
            log.info("wechat mock order refund order={} amount={} (原路退回零钱)", order.getOrderId(), amountCents);
            return;
        }
        creditBalance(order.getUserId(), amountCents);
    }

    private void refundAlipay(CabinetOrder order, int amountCents, String reason) {
        if (alipayPayClient.isConfigured()) {
            String outRefundNo = "RF" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase();
            alipayPayClient.refund(order.getOrderId(), outRefundNo, amountCents, reasonOrDefault(reason));
            log.info("alipay order refund order={} amount={}", order.getOrderId(), amountCents);
            return;
        }
        if (securityProperties.mockEnabled()) {
            log.info("alipay mock order refund order={} amount={}", order.getOrderId(), amountCents);
            return;
        }
        creditBalance(order.getUserId(), amountCents);
    }

    private void deductBalance(Long userId, int amountCents) {
        UserAccount account = userAccountRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ACCOUNT_NOT_FOUND));
        if (account.getBalanceCents() < amountCents) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, ApiMessages.INSUFFICIENT_BALANCE);
        }
        account.setBalanceCents(account.getBalanceCents() - amountCents);
        userAccountRepository.save(account);
    }

    private void creditBalance(Long userId, int amountCents) {
        UserAccount account = userAccountRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ACCOUNT_NOT_FOUND));
        account.setBalanceCents(account.getBalanceCents() + amountCents);
        userAccountRepository.save(account);
    }

    private static String reasonOrDefault(String reason) {
        return reason != null && !reason.isBlank() ? reason : "AI开门柜退款";
    }
}
