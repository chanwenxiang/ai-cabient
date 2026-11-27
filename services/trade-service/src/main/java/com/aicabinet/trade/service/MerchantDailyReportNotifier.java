package com.aicabinet.trade.service;

import com.aicabinet.trade.config.MerchantDailyReportProperties;
import com.aicabinet.trade.config.WeChatMiniAppProperties;
import com.aicabinet.trade.config.WeChatOfficialAccountProperties;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.NotificationLog;
import com.aicabinet.trade.domain.OpsUserMerchant;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.NotificationLogMapper;
import com.aicabinet.trade.mapper.OpsUserMerchantMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.wechat.MiniAppTarget;
import com.aicabinet.trade.wechat.WeChatMiniAppClient;
import com.aicabinet.trade.wechat.WeChatOfficialAccountClient;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商户「每日经营日报」微信投递（MA-377）：服务号模板优先，未绑定服务号的操作员回落原商户小程序订阅。
 *
 * <p>两套渠道按「操作员 × 日期」共同幂等；服务号已绑定但发送失败时不回退小程序，避免响应丢失造成双发。
 * 服务号模板使用独立模板 ID 与商户小程序跳转，消费者通知模板不会进入本投递链。
 *
 * <p><b>为什么不用 {@code NotificationService.notifyMerchant}</b>：那条链路签名只带
 * {@code merchantId}，内部 {@code userId=null}，而 {@code NotificationService#send} 对
 * 外部渠道的前提是 {@code userId != null}（站内信不需要人、微信/短信必须有收件人）——
 * 于是 {@code merchant_settlement} 在 V168 里配的 {@code WECHAT_SUBSCRIBE} 从来没发出过。
 * 直接改那条链路等于同时给**全部**商户通知（补货/争议/费用催缴）打开短信与微信，属于
 * 一次不可控的群发行为变更；这里按「日报」单点接通道，把爆炸半径限制在一条业务消息上。
 *
 * <p>收件人 = {@code ops_user_merchant} 里绑定该商户的账号（商户小程序的操作员），
 * 逐个判断：有 openid、当日该用户未发过 ⇒ 发一条。**不按 {@code merchant:alerts:view}
 * 过滤**：那是「柜机异常告警」的权限门，日报是商户自己店铺的销售额，能进这个商户的
 * 账号就该能看自己的钱（有意与 {@link MerchantNotifyService} 的告警口径不同）。
 *
 * <p>🔴 失败不抛出：微信侧 {@code errcode != 0} 时客户端只记 WARN，本类再把「零成功」
 * 冒泡给调用方计数，由调度器写进运营台执行记录——日报发不出去必须有人看得见。
 */
@Component
public class MerchantDailyReportNotifier {
    private static final Logger log = LoggerFactory.getLogger(MerchantDailyReportNotifier.class);

    /** 原商户小程序订阅渠道；服务号渠道使用 OFFICIAL_CHANNEL，两者共同参与日报幂等。 */
    static final String TEMPLATE_CODE = "merchant_settlement";
    static final String CHANNEL = "WECHAT_SUBSCRIBE";
    static final String OFFICIAL_CHANNEL = "WECHAT_OFFICIAL";
    static final String BIZ_TYPE = "DAILY_INCOME";
    static final String AUDIENCE = "MERCHANT";

    private final OpsUserMerchantMapper userMerchantRepository;
    private final UserInfoMapper userInfoRepository;
    private final MerchantMapper merchantRepository;
    private final WeChatMiniAppClient weChatMiniAppClient;
    private final WeChatMiniAppProperties weChatMiniAppProperties;
    private final WeChatOfficialAccountClient weChatOfficialAccountClient;
    private final WeChatOfficialAccountProperties officialAccountProperties;
    private final MerchantDailyReportProperties reportProperties;
    private final NotifyChannelSwitchService channelSwitch;
    private final NotificationLogMapper notificationLogRepository;

    public MerchantDailyReportNotifier(OpsUserMerchantMapper userMerchantRepository,
                                       UserInfoMapper userInfoRepository,
                                       MerchantMapper merchantRepository,
                                       WeChatMiniAppClient weChatMiniAppClient,
                                       WeChatMiniAppProperties weChatMiniAppProperties,
                                       WeChatOfficialAccountClient weChatOfficialAccountClient,
                                       WeChatOfficialAccountProperties officialAccountProperties,
                                       MerchantDailyReportProperties reportProperties,
                                       NotifyChannelSwitchService channelSwitch,
                                       NotificationLogMapper notificationLogRepository) {
        this.userMerchantRepository = userMerchantRepository;
        this.userInfoRepository = userInfoRepository;
        this.merchantRepository = merchantRepository;
        this.weChatMiniAppClient = weChatMiniAppClient;
        this.weChatMiniAppProperties = weChatMiniAppProperties;
        this.weChatOfficialAccountClient = weChatOfficialAccountClient;
        this.officialAccountProperties = officialAccountProperties;
        this.reportProperties = reportProperties;
        this.channelSwitch = channelSwitch;
        this.notificationLogRepository = notificationLogRepository;
    }

    /**
     * 日报数据（金额单位：分）。
     *
     * @param orderCount 参与聚合的非冲正分账行数（= 当日该商户的成交笔数）
     */
    public record MerchantDailyReport(
            String merchantId,
            String bizId,
            String period,
            long salesCents,
            int orderCount
    ) {
        /** 客单价（分，四舍五入）；0 笔时回落 0，不产生除零。 */
        public long avgCents() {
            return orderCount <= 0
                    ? 0L
                    : BigDecimal.valueOf(salesCents)
                            .divide(BigDecimal.valueOf(orderCount), 0, RoundingMode.HALF_UP)
                            .longValueExact();
        }
    }

    /**
     * 给该商户的每个已绑定微信操作员发一条日报。
     *
     * @return 实际发送成功的**人数**（0 不代表出错：可能未配模板 / 渠道关闭 / 无人绑微信 /
     *         当日已发过）
     */
    public int send(MerchantDailyReport report) {
        if (report == null || report.merchantId() == null || report.merchantId().isBlank()) {
            return 0;
        }
        if (!channelSwitch.wechatEnabled()) {
            log.debug("merchant daily report skipped: notify.wechat.enabled=false merchant={}", report.merchantId());
            return 0;
        }
        boolean officialReady = officialAccountProperties.isMerchantDailyReportConfigured();
        boolean miniReady = reportProperties.configured()
                && weChatMiniAppProperties.isConfigured(MiniAppTarget.MERCHANT);
        if (!officialReady && !miniReady) {
            log.debug("merchant daily report skipped: no usable template merchant={}", report.merchantId());
            return 0;
        }
        List<OpsUserMerchant> binds = userMerchantRepository.findActiveByMerchantId(report.merchantId());
        if (binds.isEmpty()) {
            log.info("merchant daily report skipped: no bound operator merchant={}", report.merchantId());
            return 0;
        }
        Map<String, String> fields = buildFields(report);
        Map<String, String> officialFields = buildOfficialFields(report,
                resolveMerchantName(report.merchantId()));
        String templateId = reportProperties.resolveTemplateId();
        String page = reportProperties.resolvePage();
        int sent = 0;
        int noOpenId = 0;
        int alreadySent = 0;
        for (OpsUserMerchant bind : binds) {
            Long userId = bind.getUserId();
            if (userId == null) {
                continue;
            }
            if (sentToday(userId, report.merchantId(), report.bizId())) {
                alreadySent++;
                continue;
            }
            UserInfo user = userInfoRepository.findById(userId).orElse(null);
            String clientMsgId = "merchant-daily-report:" + report.merchantId() + ":" + userId + ":" + report.bizId();
            boolean ok;
            String channel;
            if (officialReady && user != null
                    && user.getWxOfficialOpenId() != null && !user.getWxOfficialOpenId().isBlank()
                    && officialAccountProperties.appId().equals(user.getWxOfficialAppId())) {
                ok = weChatOfficialAccountClient.sendTemplateMessage(
                        user.getWxOfficialOpenId(), officialFields, clientMsgId,
                        MiniAppTarget.MERCHANT, reportProperties.resolvePage());
                channel = OFFICIAL_CHANNEL;
            } else if (miniReady && user != null
                    && user.getWxOpenId() != null && !user.getWxOpenId().isBlank()) {
                ok = weChatMiniAppClient.sendSubscribeMessage(
                        user.getWxOpenId(), templateId, page, fields, MiniAppTarget.MERCHANT);
                channel = CHANNEL;
            } else {
                noOpenId++;
                continue;
            }
            if (!ok) {
                continue;
            }
            saveLog(userId, report, channel, clientMsgId);
            sent++;
        }
        if (sent == 0 && noOpenId > 0) {
            log.info("merchant daily report not delivered: {} bound operator(s) without wx openid merchant={}",
                    noOpenId, report.merchantId());
        }
        if (alreadySent > 0) {
            log.debug("merchant daily report deduped: {} operator(s) already notified merchant={} bizId={}",
                    alreadySent, report.merchantId(), report.bizId());
        }
        return sent;
    }

    /**
     * 组装订阅消息 data：键名全部来自配置（模板库决定的 keyword 名），不含任何业务字面量键。
     * 金额按「元」两位小数，笔数为整数——微信 amount/number 类型都按字符串收。
     */
    Map<String, String> buildFields(MerchantDailyReport report) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put(reportProperties.resolveSalesKey(), yuan(report.salesCents()));
        data.put(reportProperties.resolveOrdersKey(), String.valueOf(report.orderCount()));
        data.put(reportProperties.resolveAvgKey(), yuan(report.avgCents()));
        return data;
    }

    /**
     * 服务号「账单结算成功通知」字段：amount9=结算金额、thing16=商户名称、time11=结算时间。
     * 模板没有订单数字段，微信卡片只发金额+商户名+时间；订单数与客单价仍在站内信文案里。
     */
    Map<String, String> buildOfficialFields(MerchantDailyReport report, String merchantName) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put(reportProperties.resolveOfficialSalesKey(), yuan(report.salesCents()));
        if (merchantName != null && !merchantName.isBlank()) {
            data.put(reportProperties.resolveOfficialMerchantNameKey(),
                    merchantName.length() <= 20 ? merchantName : merchantName.substring(0, 20));
        }
        data.put(reportProperties.resolveOfficialTimeKey(), formatPeriodForWechat(report.period()));
        return data;
    }

    private String resolveMerchantName(String merchantId) {
        try {
            Merchant merchant = merchantRepository.selectById(merchantId);
            return merchant == null ? null : merchant.getMerchantName();
        } catch (Exception e) {
            log.warn("merchant daily report merchant name lookup failed merchant={}", merchantId, e);
            return null;
        }
    }

    /** 微信 time/date 字段接受「2026年11月26日」形式；非法日期保底原值，避免丢字段。 */
    static String formatPeriodForWechat(String period) {
        if (period == null || period.isBlank()) {
            return "";
        }
        try {
            return java.time.LocalDate.parse(period.trim())
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日"));
        } catch (java.time.format.DateTimeParseException e) {
            return period;
        }
    }

    /**
     * 按「用户 × 商户 × 日期」幂等：同一操作员管理多个商户时，各商户日报互不抑制。
     * 站内信那条（channel=IN_APP）不参与本判断——两个渠道各自补发，互不阻塞。
     */
    private boolean sentToday(Long userId, String merchantId, String bizId) {
        Long c = notificationLogRepository.selectCount(Wrappers.<NotificationLog>lambdaQuery()
                .eq(NotificationLog::getTemplateCode, TEMPLATE_CODE)
                .in(NotificationLog::getChannel, List.of(CHANNEL, OFFICIAL_CHANNEL))
                .eq(NotificationLog::getAudience, AUDIENCE)
                .eq(NotificationLog::getUserId, userId)
                .eq(NotificationLog::getMerchantId, merchantId)
                .eq(NotificationLog::getBizType, BIZ_TYPE)
                .eq(NotificationLog::getBizId, bizId));
        return c != null && c > 0;
    }

    private void saveLog(Long userId, MerchantDailyReport report, String channel, String clientMsgId) {
        // CONCURRENCY: append-only 通知日志；调用方受日报任务锁与 user×date 查重保护。
        NotificationLog entry = new NotificationLog();
        entry.setTemplateCode(TEMPLATE_CODE);
        entry.setChannel(channel);
        entry.setAudience(AUDIENCE);
        entry.setUserId(userId);
        entry.setMerchantId(report.merchantId());
        entry.setTitle("经营日报");
        entry.setBody(report.period() + " 销售额 " + yuan(report.salesCents()) + " 元、"
                + report.orderCount() + " 笔订单、客单价 " + yuan(report.avgCents()) + " 元");
        entry.setBizType(BIZ_TYPE);
        entry.setBizId(report.bizId());
        entry.setClientMsgId(clientMsgId);
        entry.setStatus("SENT");
        entry.setCreatedAt(Instant.now());
        notificationLogRepository.insert(entry);
    }

    static String yuan(long cents) {
        return BigDecimal.valueOf(cents, 2).toPlainString();
    }
}
