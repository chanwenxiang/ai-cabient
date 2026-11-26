package com.aicabinet.trade.service;

import com.aicabinet.common.dto.NotificationDispatchMessage;
import com.aicabinet.trade.config.WeChatMiniAppProperties;
import com.aicabinet.trade.config.WeChatOfficialAccountProperties;
import com.aicabinet.trade.domain.NotificationLog;
import com.aicabinet.trade.domain.NotificationTemplate;
import com.aicabinet.trade.mapper.NotificationLogMapper;
import com.aicabinet.trade.mapper.NotificationTemplateMapper;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.sms.WebhookSmsSender;
import com.aicabinet.trade.sms.AliyunSmsSender;
import com.aicabinet.trade.wechat.WeChatMiniAppClient;
import com.aicabinet.trade.wechat.WeChatOfficialAccountClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import com.aicabinet.trade.wechat.MiniAppTarget;

/** 外部渠道通知分发：微信订阅消息 / 短信；同步与异步（Kafka 消费）共用。 */
@Component
public class ExternalNotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ExternalNotificationDispatcher.class);

    private final NotificationLogMapper logRepository;
    private final UserInfoMapper userInfoRepository;
    private final NotificationTemplateMapper templateRepository;
    private final WeChatMiniAppClient weChatMiniAppClient;
    private final WeChatMiniAppProperties weChatMiniAppProperties;
    private final WeChatOfficialAccountClient weChatOfficialAccountClient;
    private final WeChatOfficialAccountProperties weChatOfficialAccountProperties;
    private final WebhookSmsSender smsSender;
    private final NotifyChannelSwitchService channelSwitch;
    private final AliyunSmsSender aliyunSmsSender;

    public ExternalNotificationDispatcher(NotificationLogMapper logRepository,
                                          UserInfoMapper userInfoRepository,
                                          NotificationTemplateMapper templateRepository,
                                          WeChatMiniAppClient weChatMiniAppClient,
                                          WeChatMiniAppProperties weChatMiniAppProperties,
                                          WeChatOfficialAccountClient weChatOfficialAccountClient,
                                          WeChatOfficialAccountProperties weChatOfficialAccountProperties,
                                          WebhookSmsSender smsSender,
                                          NotifyChannelSwitchService channelSwitch,
                                          AliyunSmsSender aliyunSmsSender) {
        this.logRepository = logRepository;
        this.userInfoRepository = userInfoRepository;
        this.templateRepository = templateRepository;
        this.weChatMiniAppClient = weChatMiniAppClient;
        this.weChatMiniAppProperties = weChatMiniAppProperties;
        this.weChatOfficialAccountClient = weChatOfficialAccountClient;
        this.weChatOfficialAccountProperties = weChatOfficialAccountProperties;
        this.smsSender = smsSender;
        this.channelSwitch = channelSwitch;
        this.aliyunSmsSender = aliyunSmsSender;
    }

    /** 按消息渠道配置分发微信订阅消息与短信；异步消费时渠道失败必须重抛以触发 Kafka 重试。 */
    public void dispatch(NotificationDispatchMessage msg) {
        if (msg.userId() == null) {
            return;
        }
        boolean failed = false;
        Exception firstFailure = null;
        if (channelSwitch.wechatEnabled()) {
            try {
                failed |= wechatSendFailed(msg);
            } catch (Exception e) {
                log.warn("wechat notify failed userId={} template={}: {}",
                        msg.userId(), msg.templateCode(), e.toString());
                saveFailedLog(msg, "WECHAT_SUBSCRIBE", e.toString());
                failed = true;
                if (firstFailure == null) firstFailure = e;
            }
        }
        if (channelSwitch.smsEnabled()) {
            try {
                sendSms(msg);
            } catch (Exception e) {
                log.warn("sms notify failed userId={} template={}: {}",
                        msg.userId(), msg.templateCode(), e.toString());
                saveFailedLog(msg, "SMS", e.toString());
                failed = true;
                if (firstFailure == null) firstFailure = e;
            }
        }
        if (failed && channelSwitch.asyncEnabled()) {
            throw new IllegalStateException("notification dispatch failed", firstFailure);
        }
    }

    /** @return 仅渠道实际发送返回 false 时为 true；未绑定/模板缺失是确定性跳过，重试也不会改变。 */
    private boolean wechatSendFailed(NotificationDispatchMessage msg) {
        UserInfo user = userInfoRepository.findById(msg.userId()).orElse(null);
        if (user != null
                && user.getWxOfficialOpenId() != null && !user.getWxOfficialOpenId().isBlank()
                && weChatOfficialAccountProperties.appId().equals(user.getWxOfficialAppId())) {
            OfficialSendSpec spec = resolveOfficialSpec(msg);
            if (spec != null && spec.configured()) {
                String clientMsgId = resolveClientMsgId(msg);
                boolean ok = weChatOfficialAccountClient.sendTemplateMessage(
                        user.getWxOfficialOpenId(), spec.templateId(), spec.data(),
                        clientMsgId, spec.target(), spec.pagePath(), true);
                if (ok) {
                    saveLog(msg, "WECHAT_OFFICIAL");
                    return false;
                }
                saveFailedLog(msg, "WECHAT_OFFICIAL", "服务号模板消息发送失败");
                return true;
            }
            if (spec != null) {
                saveFailedLog(msg, "WECHAT_OFFICIAL", "该业务模板未配置");
                return false;
            }
            // 服务号只覆盖 ORDER/RECHARGE/REFUND/WITHDRAW_SUCCESS/WITHDRAW_FAILED/DISPUTE；
            // 其它业务类型继续走下面的消费者小程序订阅链。
        }
        if (isMerchantAudience(msg)) {
            saveFailedLog(msg, "WECHAT_SUBSCRIBE", "商户操作员未绑定服务号");
            return false;
        }
        if (user == null || user.getWxOpenId() == null || user.getWxOpenId().isBlank()) {
            saveFailedLog(msg, "WECHAT_SUBSCRIBE", "用户未绑定微信 openid");
            return false;
        }
        // MA-384：消费者模板缺失时 fail-closed——旧实现回落商户模板，两个 appid 不通用必 400；
        // 现在明确跳过并发日志，让运营能从日志/审计看到「不是用户没订阅，是模板没配」。
        String templateId = weChatMiniAppProperties.resolveConsumerTemplateId();
        if (templateId == null || templateId.isBlank()) {
            log.warn("wechat notify skipped: consumer template not configured userId={} templateCode={}",
                    msg.userId(), msg.templateCode());
            saveFailedLog(msg, "WECHAT_SUBSCRIBE", "消费者订阅模板未配置");
            return false;
        }
        boolean ok = weChatMiniAppClient.sendSubscribeMessage(
                user.getWxOpenId(),
                templateId,
                weChatMiniAppProperties.resolveConsumerNotifyPage(),
                Map.of("thing1", clipToThingLimit(msg.title()), "thing2", clipToThingLimit(msg.body())),
                MiniAppTarget.CONSUMER);
        if (ok) {
            saveLog(msg, "WECHAT_SUBSCRIBE");
            return false;
        }
        saveFailedLog(msg, "WECHAT_SUBSCRIBE", "微信订阅消息发送失败");
        return true;
    }

    /** 服务号模板投递规格：模板 ID + 字段 + 可用性 + 目标端/落地页。返回 null = 该业务类型不投服务号。 */
    private record OfficialSendSpec(String templateId, Map<String, String> data, boolean configured,
                                    MiniAppTarget target, String pagePath) {}

    /**
     * 服务号模板选型（2026-11-26 与微信后台选定模板逐字对齐，键名经 get_all_private_template 核实）：
     *   ORDER/RECHARGE     → 「消费成功通知」：thing2=消费项目(标题)、amount3=消费金额、
     *                        time6=消费时间、thing20=商家名称
     *   REFUND             → 「退款成功通知」：amount3=退款金额、time5=退款时间、
     *                        character_string1=订单编号、thing16=商家名称
     *   WITHDRAW_SUCCESS   → 「提现成功通知」：amount2=提现金额、amount4=手续费、
     *                        amount5=到账金额、time3=提现时间、time9=到账时间
     *   WITHDRAW_FAILED    → 「提现失败提醒」：character_string5=用户账号、amount2=提现金额、
     *                        time3=提现时间、const4=失败原因（枚举：备付金不足/账户信息有误/
     *                        银行卡拒收/其他原因）
     *   DISPUTE            → 「售后订单处理通知」：character_string1=订单编号、thing5=商品名称、
     *                        amount2=订单金额、const7=处理结果（处理中/已退款/免单/已驳回/已关闭）
     * 金额取业务方渲染参数里的 {@code amount}；失败原因取 {@code failureReason}。
     */
    private OfficialSendSpec resolveOfficialSpec(NotificationDispatchMessage msg) {
        String type = msg.bizType() == null ? "" : msg.bizType().trim().toUpperCase(Locale.ROOT);
        Map<String, String> params = msg.smsParams() == null ? Map.of() : msg.smsParams();
        String amount = params.get("amount");
        String now = nowForWechat();
        if ("ORDER".equals(type) || "RECHARGE".equals(type)) {
            Map<String, String> data = new java.util.LinkedHashMap<>();
            data.put("thing2", clipToThingLimit(msg.title()));
            if (amount != null && !amount.isBlank()) {
                data.put("amount3", amount);
            }
            data.put("time6", now);
            putBrand(data, "thing20");
            return new OfficialSendSpec(weChatOfficialAccountProperties.templateId(), data,
                    weChatOfficialAccountProperties.isConfigured(), MiniAppTarget.CONSUMER,
                    resolveConsumerPage(msg));
        }
        if ("REFUND".equals(type) || "BALANCE_REFUND".equals(type)) {
            Map<String, String> data = new java.util.LinkedHashMap<>();
            if (amount != null && !amount.isBlank()) {
                data.put("amount3", amount);
            }
            data.put("time5", now);
            if (msg.bizId() != null && !msg.bizId().isBlank()) {
                data.put("character_string1", msg.bizId().trim());
            }
            putBrand(data, "thing16");
            data.put("thing2", firstNonBlank(params.get("productName"), "订单商品"));
            return new OfficialSendSpec(weChatOfficialAccountProperties.resolveRefundTemplateId(), data,
                    weChatOfficialAccountProperties.isRefundConfigured(), MiniAppTarget.CONSUMER,
                    resolveConsumerPage(msg));
        }
        if ("WITHDRAW_SUCCESS".equals(type)) {
            Map<String, String> data = new java.util.LinkedHashMap<>();
            if (amount != null && !amount.isBlank()) {
                data.put("amount2", amount);
            }
            putNonBlank(data, "amount4", params.get("fee"));
            putNonBlank(data, "amount5", params.get("receivedAmount"));
            data.put("time3", firstNonBlank(params.get("requestTime"), now));
            data.put("time9", now);
            return new OfficialSendSpec(weChatOfficialAccountProperties.resolveWithdrawTemplateId(), data,
                    weChatOfficialAccountProperties.isWithdrawConfigured(), MiniAppTarget.MERCHANT,
                    "pages/wallet/wallet");
        }
        if ("WITHDRAW_FAILED".equals(type)) {
            Map<String, String> data = new java.util.LinkedHashMap<>();
            putNonBlank(data, "character_string5", params.get("account"));
            if (amount != null && !amount.isBlank()) {
                data.put("amount2", amount);
            }
            data.put("time3", firstNonBlank(params.get("requestTime"), now));
            data.put("const4", normalizeFailureReason(params.get("failureReason")));
            return new OfficialSendSpec(weChatOfficialAccountProperties.resolveWithdrawFailedTemplateId(), data,
                    weChatOfficialAccountProperties.isWithdrawFailedConfigured(), MiniAppTarget.MERCHANT,
                    "pages/wallet/wallet");
        }
        if ("DISPUTE".equals(type)) {
            Map<String, String> data = new java.util.LinkedHashMap<>();
            String orderId = params.get("orderId");
            if (orderId == null || orderId.isBlank()) {
                orderId = msg.bizId();
            }
            if (orderId != null && !orderId.isBlank()) {
                data.put("character_string1", orderId.trim());
            }
            putNonBlank(data, "thing5", firstNonBlank(params.get("productName"), "待核实"));
            String orderAmount = params.get("orderAmount");
            if (orderAmount == null || orderAmount.isBlank()) {
                orderAmount = amount;
            }
            data.put("amount2", firstNonBlank(orderAmount, "0.00"));
            data.put("const7", normalizeDisputeResult(msg.templateCode(),
                    params.get("resolutionType")));
            return new OfficialSendSpec(weChatOfficialAccountProperties.resolveDisputeTemplateId(), data,
                    weChatOfficialAccountProperties.isDisputeConfigured(), MiniAppTarget.CONSUMER,
                    resolveConsumerPage(msg));
        }
        return null;
    }

    private void putBrand(Map<String, String> data, String key) {
        String brand = weChatOfficialAccountProperties.resolveBrandName();
        if (!brand.isBlank()) {
            data.put(key, brand);
        }
    }

    private static void putNonBlank(Map<String, String> data, String key, String value) {
        if (value != null && !value.isBlank()) {
            data.put(key, value);
        }
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** 失败原因必须落在模板审核通过的枚举内；未知原因统一「其他原因」，绝不能原样透传渠道错误串。 */
    private static String normalizeFailureReason(String reason) {
        if (reason == null) {
            return "其他原因";
        }
        return switch (reason.trim()) {
            case "备付金不足", "账户信息有误", "银行卡拒收", "其他原因" -> reason.trim();
            default -> "其他原因";
        };
    }

    /** 处理结果必须落在售后模板审核通过的枚举内；未知状态保守显示「处理中」。 */
    private static String normalizeDisputeResult(String templateCode, String resolutionType) {
        if (templateCode != null && templateCode.toLowerCase(Locale.ROOT).contains("created")) {
            return "处理中";
        }
        if (resolutionType == null) {
            return "处理中";
        }
        return switch (resolutionType.trim().toUpperCase(Locale.ROOT)) {
            case "REFUND", "REFUNDED", "已退款" -> "已退款";
            case "WAIVE", "免单" -> "免单";
            case "CONFIRM", "REJECTED", "已驳回" -> "已驳回";
            case "CANCELLED", "CANCELED", "CLOSED", "已关闭" -> "已关闭";
            default -> "处理中";
        };
    }

    private static boolean isMerchantAudience(NotificationDispatchMessage msg) {
        return "MERCHANT".equalsIgnoreCase(msg.audience())
                || "WITHDRAW_SUCCESS".equalsIgnoreCase(msg.bizType())
                || "WITHDRAW_FAILED".equalsIgnoreCase(msg.bizType());
    }

    /** 微信 time/date 字段格式：如「2026年11月26日 10:53」。 */
    private static String nowForWechat() {
        return java.time.format.DateTimeFormatter
                .ofPattern("yyyy年M月d日 HH:mm")
                .withZone(com.aicabinet.trade.support.ScheduleZones.ZONE)
                .format(java.time.Instant.now());
    }

    private String resolveConsumerPage(NotificationDispatchMessage msg) {
        String fallback = weChatOfficialAccountProperties.resolveConsumerMiniPagePath();
        String bizId = msg.bizId() == null ? "" : msg.bizId().trim();
        if (bizId.isEmpty()) {
            return fallback;
        }
        String type = msg.bizType() == null ? "" : msg.bizType().trim().toUpperCase(Locale.ROOT);
        String value = URLEncoder.encode(bizId, StandardCharsets.UTF_8);
        if ("ORDER".equals(type) || "REFUND".equals(type)) {
            return "pages/order-detail/order-detail?orderId=" + value;
        }
        if ("RECHARGE".equals(type)) {
            return "pages/recharge/recharge?orderId=" + value;
        }
        if ("DISPUTE".equals(type)) {
            return "pages/dispute/detail?ticketId=" + value;
        }
        return fallback;
    }

    private static String resolveClientMsgId(NotificationDispatchMessage msg) {
        if (msg.clientMsgId() != null && !msg.clientMsgId().isBlank()) {
            return msg.clientMsgId().trim();
        }
        String raw = String.join("|", String.valueOf(msg.templateCode()), String.valueOf(msg.userId()),
                String.valueOf(msg.bizType()), String.valueOf(msg.bizId()),
                String.valueOf(msg.title()), String.valueOf(msg.body()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void sendSms(NotificationDispatchMessage msg) {
        UserInfo user = userInfoRepository.findById(msg.userId()).orElse(null);
        if (user == null || user.getPhoneNumber() == null || user.getPhoneNumber().isBlank()) {
            return;
        }
        NotificationTemplate template = msg.templateCode() == null || msg.templateCode().isBlank()
                ? null
                : templateRepository.findByCode(msg.templateCode()).orElse(null);
        if (!smsChannelConfigured(template)) {
            // M09：模板未配置短信渠道（如微信-only）时不得因全局 sms-enabled 而双发
            log.info("sms skipped: template {} has no SMS channel, userId={}", msg.templateCode(), msg.userId());
            return;
        }
        if (sendViaAliyunTemplate(msg, template, user.getPhoneNumber())) {
            saveLog(msg, "SMS");
            return;
        }
        smsSender.sendMessage(user.getPhoneNumber(), msg.title() + "：" + msg.body());
        saveLog(msg, "SMS");
    }

    /**
     * 阿里云通知短信（模板渲染）：消息带结构化参数、且**该模板自己登记了**阿里云 CODE 时走模板发送。
     * <p>CODE 来自 {@code notification_template.aliyun_template_code}（V441）——一条模板对一个 CODE，
     * 不再有「全局催缴模板」兜底：没登记的模板绝不会被套成别的文案（此前 order_paid 会被套成催缴）。
     * 键名映射：催缴链路内部参数是 orderId/amount（V325 站内信模板 {orderId} 键名，固定迁移不可改），
     * 阿里云模板变量是 order_no/amount；发送前做纯键名归一化（orderId→order_no 且不保留原键），
     * 保证「参数个数 = 模板变量个数」（阿里云硬要求，多传/少传都会被拒）。
     * 阿里云未配齐/无参数/未登记 CODE ⇒ 返回 false 回退 webhook
     * 文本通道；发送失败也回退，催缴不因单通道故障丢失。</p>
     */
    private boolean sendViaAliyunTemplate(
            NotificationDispatchMessage msg, NotificationTemplate template, String phoneNumber) {
        String aliyunTemplateCode = template == null ? null : blankToNull(template.getAliyunTemplateCode());
        if (aliyunTemplateCode == null
                || msg.smsParams() == null || msg.smsParams().isEmpty()
                || !aliyunSmsSender.isConfigured()) {
            return false;
        }
        Map<String, String> params = new java.util.LinkedHashMap<>();
        msg.smsParams().forEach((k, v) -> {
            if (!"orderId".equals(k)) {
                params.put(k, v);
            }
        });
        if (!params.containsKey("order_no") && msg.smsParams().get("orderId") != null) {
            params.put("order_no", msg.smsParams().get("orderId"));
        }
        try {
            aliyunSmsSender.sendMessage(phoneNumber, aliyunTemplateCode, params);
            log.info("sms sent via aliyun template={} userId={}", aliyunTemplateCode, msg.userId());
            return true;
        } catch (Exception e) {
            log.warn("aliyun template sms failed userId={} template={}: {}",
                    msg.userId(), aliyunTemplateCode, e.toString());
            return false;
        }
    }

    private static String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    /**
     * M09：按模板 channels（逗号分隔 IN_APP/WECHAT_SUBSCRIBE/SMS）判断短信渠道是否启用。
     * 模板缺失/无模板上下文时保持既有直发行为。
     */
    private boolean smsChannelConfigured(NotificationTemplate template) {
        if (template == null) {
            return true;
        }
        String raw = template.getChannels() != null && !template.getChannels().isBlank()
                ? template.getChannels()
                : template.getChannel();
        if (raw == null || raw.isBlank()) {
            return false;
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .map(c -> c.toUpperCase(Locale.ROOT))
                .anyMatch("SMS"::equals);
    }

    /**
     * 微信订阅消息 thing 类型字段限 20 字符，超长整条被微信拒发（errcode 47003）；
     * 发送侧兜底截断，保证通知不会因文案过长而静默丢失。
     */
    private static String clipToThingLimit(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= WECHAT_THING_MAX_CHARS ? value : value.substring(0, WECHAT_THING_MAX_CHARS);
    }

    private static final int WECHAT_THING_MAX_CHARS = 20;

    private void saveLog(NotificationDispatchMessage msg, String channel) {
        saveLog(msg, channel, "SENT", msg.body());
    }

    private void saveFailedLog(NotificationDispatchMessage msg, String channel, String reason) {
        try {
            saveLog(msg, channel, "FAILED", msg.body() + "（投递失败：" + reason + "）");
        } catch (RuntimeException logFailure) {
            log.error("notification failure log persist failed channel={} template={} userId={}",
                    channel, msg.templateCode(), msg.userId(), logFailure);
        }
    }

    private void saveLog(NotificationDispatchMessage msg, String channel, String status, String body) {
        NotificationLog logEntry = new NotificationLog();
        logEntry.setTemplateCode(msg.templateCode());
        logEntry.setChannel(channel);
        logEntry.setAudience(msg.audience() == null || msg.audience().isBlank()
                ? "CONSUMER" : msg.audience().toUpperCase(Locale.ROOT));
        logEntry.setUserId(msg.userId());
        logEntry.setMerchantId(msg.merchantId());
        logEntry.setTitle(msg.title());
        logEntry.setBody(body);
        logEntry.setBizType(msg.bizType());
        logEntry.setBizId(msg.bizId());
        logEntry.setClientMsgId("WECHAT_OFFICIAL".equals(channel) ? resolveClientMsgId(msg) : msg.clientMsgId());
        // SENT = 渠道已受理（阿里云 Code=OK / 微信 errcode=0），不代表用户已收到；
        // notification_log 没有投递回执字段，需要到达率时另接渠道回执查询。
        logEntry.setStatus(status);
        logEntry.setCreatedAt(Instant.now());
        logRepository.save(logEntry);
        log.info("external notification {} channel={} template={} userId={}",
                status, channel, msg.templateCode(), msg.userId());
    }
}
