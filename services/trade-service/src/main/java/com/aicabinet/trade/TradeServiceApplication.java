package com.aicabinet.trade;

import com.aicabinet.common.security.InternalApiProperties;
import com.aicabinet.common.security.InternalApiAuthInterceptor;
import com.aicabinet.trade.config.AuthProperties;
import com.aicabinet.trade.config.CorsProperties;
import com.aicabinet.trade.config.CheckoutProperties;
import com.aicabinet.trade.config.OpsMonitoringProperties;
import com.aicabinet.trade.config.LineWithdrawProperties;
import com.aicabinet.trade.config.MerchantWithdrawProperties;
import com.aicabinet.trade.config.MinioProperties;
import com.aicabinet.trade.config.NotificationProperties;
import com.aicabinet.trade.config.ProfitSharingProperties;
import com.aicabinet.trade.config.RiskControlProperties;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.StagingProperties;
import com.aicabinet.trade.config.WeChatMiniAppProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.config.WeChatWebProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@Import(InternalApiAuthInterceptor.class)
@EnableConfigurationProperties({WeChatPayProperties.class, WeChatMiniAppProperties.class, WeChatWebProperties.class,
        // MA-407：微信商家转账（场景/报备/回调）业务参数
        com.aicabinet.trade.config.WeChatTransferProperties.class,
        MinioProperties.class, SecurityProperties.class, StagingProperties.class,
        CheckoutProperties.class,
        // MA-377：商户每日经营日报（微信订阅消息）——模板 id/落地页/关键词名独立成组
        com.aicabinet.trade.config.MerchantDailyReportProperties.class,
        OpsMonitoringProperties.class,
        com.aicabinet.trade.config.SessionExpireProperties.class,
        InternalApiProperties.class, AuthProperties.class, CorsProperties.class,
        com.aicabinet.trade.config.ReconciliationProperties.class,
        com.aicabinet.trade.config.AlipayProperties.class,
        com.aicabinet.trade.config.AlipayMiniProperties.class,
        com.aicabinet.trade.config.QrProperties.class,
        ProfitSharingProperties.class,
        LineWithdrawProperties.class,
        MerchantWithdrawProperties.class,
        // V307：收款账号 AES-256-GCM 加密密钥（来源 AICABINET_PAYOUT_ENCRYPTION_KEY，缺失即拒收打款）
        com.aicabinet.trade.payout.PayoutEncryptionProperties.class,
        RiskControlProperties.class,
        com.aicabinet.trade.config.RateLimitProperties.class,
        com.aicabinet.trade.config.PayScoreProperties.class,
        NotificationProperties.class,
        com.aicabinet.trade.config.DisputeSlaProperties.class,
        com.aicabinet.trade.config.DisputeAutoWaiveProperties.class,
        com.aicabinet.trade.config.SmsDeliveryProbeProperties.class,
        com.aicabinet.trade.config.RopProperties.class,
        com.aicabinet.trade.config.RoutePlanningProperties.class,
        com.aicabinet.trade.config.FeeBillProperties.class,
        com.aicabinet.trade.config.IdentityVerifyProperties.class,
        com.aicabinet.trade.config.OpsAdminBootstrapProperties.class,
        com.aicabinet.trade.config.PromotionPlacementProperties.class})
public class TradeServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TradeServiceApplication.class, args);
    }
}
