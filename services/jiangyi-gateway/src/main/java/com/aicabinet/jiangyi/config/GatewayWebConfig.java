package com.aicabinet.jiangyi.config;

import com.aicabinet.common.security.InternalApiAuthInterceptor;
import com.aicabinet.jiangyi.auth.DeviceAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * gateway 拦截器注册：两个鉴权面互不重叠。
 * <ul>
 *   <li>{@code /internal/**}：服务间调用（trade → gateway 开门转发等），
 *       复用 common-core InternalApiAuthInterceptor（X-Internal-Api-Key）；</li>
 *   <li>{@code /jiangyi/api/**}：将邑设备面上报，DeviceAuthInterceptor（Bearer JWT），
 *       排除 {@code /jiangyi/api/token}（设备换 token 的唯一免鉴权入口）。</li>
 * </ul>
 */
@Configuration
public class GatewayWebConfig implements WebMvcConfigurer {

    private final InternalApiAuthInterceptor internalApiAuthInterceptor;
    private final DeviceAuthInterceptor deviceAuthInterceptor;

    public GatewayWebConfig(InternalApiAuthInterceptor internalApiAuthInterceptor,
                            DeviceAuthInterceptor deviceAuthInterceptor) {
        this.internalApiAuthInterceptor = internalApiAuthInterceptor;
        this.deviceAuthInterceptor = deviceAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(internalApiAuthInterceptor)
                .addPathPatterns("/internal/**");
        registry.addInterceptor(deviceAuthInterceptor)
                .addPathPatterns("/jiangyi/api/**")
                // 设备换 token 的免鉴权入口（文档路径 + 简短别名），排除缺口必须成对；
                // gather-finish-notify：将邑云学习完成回调（CB-023），将邑侧无法持设备 JWT，
                // 防伪造由 trade 侧 finishNotifyId 一次性凭据 + 交叉验证承担
                .excludePathPatterns("/jiangyi/api/token/openDoorDeviceStatus", "/jiangyi/api/token",
                        "/jiangyi/api/gather-finish-notify");
    }
}
