package com.aicabinet.trade.config;

import com.aicabinet.common.security.InternalApiAuthInterceptor;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.DeviceHttpCredentialInterceptor;
import com.aicabinet.trade.auth.OpsDeviceScopeInterceptor;
import com.aicabinet.trade.service.DeviceHttpCredentialService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * 免登录白名单（默认拒绝）。新增公开/回调端点必须在这里登记；
     * 支付平台回调各自做签名验签，登录态反而会让渠道回调 401。
     */
    static final List<String> AUTH_EXCLUDE_PATTERNS = List.of(
            // 登录/刷新/验证码
            "/api/v2/auth/**",
            // 支付平台真实回调（各自做签名验签）。勿用 wechat/**、alipay/**：
            // 否则 /notify/mock/** 也会裸奔，可任意 userId 模拟入账。
            "/api/v2/payment/wechat/notify",
            // 微信 V3 退款结果通知（同样自带验签；商户平台手工退款同步扣余额，
            // 2026-10-23 实证：手工退款无此端点则系统盲区，产生幽灵余额）
            "/api/v2/payment/wechat/refund-notify",
            "/api/v2/payment/alipay/notify",
            // 微信支付分授权/订单事件回调（CB-064；与支付结果通知同一套 v3 验签链）
            "/api/v2/payment/payscore/notify",
            // 商家转账免确认收款授权结果通知（MA-407）。官方 4015901167 把
            // authorization_notify_url 列为必填 ⇒ 微信会真的往这里投递，漏白名单=渠道重投全 401。
            "/api/v2/payout/wechat/authorization-notify",
            // 商家转账结果通知（MA-407 确认收款模式）：用户确认收款后到账的终态入口
            "/api/v2/payout/wechat/transfer-notify",
            // 消费者公开公告
            "/api/v2/announcements/**",
            // 商品图/品牌 Logo/广告素材等 <img> 直链（H43：运营头像不公开，
            // /api/v2/media/ops-avatars/** 要求运营登录，见 MediaController#opsAvatar）
            "/api/v2/media/sku-images/**",
            "/api/v2/media/ops-brand/**",
            "/api/v2/media/ad-assets/**",
            // 消费者公开配置
            "/api/v2/public/**",
            // 服务号 OAuth 回调：显式锚点，供回调契约门禁核验；安全性由 5 分钟一次性 state 票据提供
            "/api/v2/public/wechat-official-account/callback",
            // 营销活动/轮播（游客可见；领券 /claim 仍需登录）
            "/api/v2/marketing/banners",
            "/api/v2/marketing/popups",
            "/api/v2/marketing/campaigns/active");

    private final AuthInterceptor authInterceptor;
    private final InternalApiAuthInterceptor internalApiAuthInterceptor;
    private final ApiVersionInterceptor apiVersionInterceptor;
    private final CorsProperties corsProperties;
    private final DeviceHttpCredentialService deviceHttpCredentialService;
    private final OpsDeviceScopeInterceptor opsDeviceScopeInterceptor;

    public WebConfig(AuthInterceptor authInterceptor,
                     InternalApiAuthInterceptor internalApiAuthInterceptor,
                     ApiVersionInterceptor apiVersionInterceptor,
                     CorsProperties corsProperties,
                     DeviceHttpCredentialService deviceHttpCredentialService,
                     OpsDeviceScopeInterceptor opsDeviceScopeInterceptor) {
        this.authInterceptor = authInterceptor;
        this.internalApiAuthInterceptor = internalApiAuthInterceptor;
        this.apiVersionInterceptor = apiVersionInterceptor;
        this.corsProperties = corsProperties;
        this.deviceHttpCredentialService = deviceHttpCredentialService;
        this.opsDeviceScopeInterceptor = opsDeviceScopeInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiVersionInterceptor)
                .addPathPatterns("/api/**");

        // 设备面一机一密（审计 A1/P0-2）：先于 fleet-key 拦截器命中专属路径。
        // 覆盖：OTA check/progress（模拟器联调件；edge android-app 已随 47188717 退役）。
        // 其余 /internal/** 仍为服务间 fleet key（生产有 CIDR）。
        registry.addInterceptor(new DeviceHttpCredentialInterceptor(deviceHttpCredentialService))
                .addPathPatterns(
                        "/internal/v1/devices/*/ota/*",
                        "/internal/v1/devices/*/screen-content",
                        "/internal/v1/devices/*/ad-play")
                .order(0);

        registry.addInterceptor(internalApiAuthInterceptor)
                .addPathPatterns("/internal/**")
                .excludePathPatterns(
                        "/internal/v1/devices/*/ota/*",
                        "/internal/v1/devices/*/screen-content",
                        "/internal/v1/devices/*/ad-play");

        // 默认拒绝：/api/v2/** 一律要求登录，仅显式放行公开/回调端点。
        // 新增控制器无需改这里即可获得鉴权；若确属公开接口，才追加到 excludePathPatterns。
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/v2/**")
                .excludePathPatterns(AUTH_EXCLUDE_PATTERNS);

        // 柜机对象级授权统一闸（R23X-01/02/03/05/06）：/api/v2/ops/** 下任何带 {deviceId}
        // 路径变量的端点，先按操作员数据范围判定。🔴 order 必须 > 0：它依赖 AuthInterceptor
        // 写入的 ATTR_USER_ID，先跑会拿不到身份（fail-closed 401，等于全站运营后台不可用）。
        registry.addInterceptor(opsDeviceScopeInterceptor)
                .addPathPatterns(OpsDeviceScopeInterceptor.OPS_PREFIX)
                .order(10);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (corsProperties.allowedOrigins() == null || corsProperties.allowedOrigins().isEmpty()) {
            return;
        }
        // 2026-10-13 生产首启实证：application-prod.yml 以 ${CORS_ORIGIN:} 注入，留空时列表含
        // 一个空串占位——空串不是合法 origin，过滤掉；过滤后为空则等价于「同源部署，不放宽跨域」。
        String[] origins = corsProperties.allowedOrigins().stream()
                .filter(o -> o != null && !o.isBlank())
                .toArray(String[]::new);
        if (origins.length == 0) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
