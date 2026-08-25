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
import com.aicabinet.trade.config.VisionApiProperties;
import com.aicabinet.trade.config.VisionAsyncProperties;
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
        MinioProperties.class, VisionAsyncProperties.class, SecurityProperties.class, StagingProperties.class,
        CheckoutProperties.class,
        OpsMonitoringProperties.class,
        InternalApiProperties.class, AuthProperties.class, CorsProperties.class, VisionApiProperties.class,
        com.aicabinet.trade.config.ReconciliationProperties.class,
        com.aicabinet.trade.config.AlipayProperties.class,
        com.aicabinet.trade.config.QrProperties.class,
        ProfitSharingProperties.class,
        LineWithdrawProperties.class,
        MerchantWithdrawProperties.class,
        RiskControlProperties.class,
        com.aicabinet.trade.config.PayScoreProperties.class,
        NotificationProperties.class,
        com.aicabinet.trade.config.DisputeSlaProperties.class,
        com.aicabinet.trade.config.RopProperties.class,
        com.aicabinet.trade.config.RoutePlanningProperties.class})
public class TradeServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TradeServiceApplication.class, args);
    }
}
