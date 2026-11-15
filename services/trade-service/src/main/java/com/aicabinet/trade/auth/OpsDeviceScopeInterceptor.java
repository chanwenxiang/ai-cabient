package com.aicabinet.trade.auth;

import com.aicabinet.trade.service.MerchantScopeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 运营后台「柜机对象级授权」统一闸（R23X-01/02/03/05/06 的机制解，2026-11-15）。
 *
 * <p>🔴 <b>为什么放在拦截器而不是逐个端点补</b>：{@code requireDeviceAccess} 从 MA-068
 * （盘点凭证跨租户写）到本轮已是<b>第四次</b>复发——每次都是「新入口只挂功能码、不查数据范围」。
 * 逐端点补的修法把正确性押在「下一个写端点的人记得加」，本次审计里
 * {@code AdminJiangyiController} 一个类就有 34 个 {@code /devices/{deviceId}/...} 端点没查。
 * 改为在 {@code /api/v2/ops/**} 前缀上一次性拦：路径里有 {@code {deviceId}} 就先过范围，
 * 端点作者不需要记得任何事，新端点自动继承。</p>
 *
 * <p>判据与 {@link MerchantScopeService#requireDeviceAccess} 完全同源（不新建第二套口径）：
 * 全局（显式 admin 角色）直接放行；其余账号按「设备范围 ∩ 商户范围」判定，超范围 403。
 * 拒绝计数在 {@code canAccessDevice} 内累加，此处不重复记。</p>
 *
 * <p>顺序要求：必须晚于 {@link AuthInterceptor}（依赖其写入的 {@code ATTR_USER_ID}），
 * 见 {@code WebConfig#addInterceptors} 的 {@code .order(...)}。</p>
 */
@Component
public class OpsDeviceScopeInterceptor implements HandlerInterceptor {

    /** 运营后台统一前缀：本工程所有运营控制器都在其下（见各控制器类级 @RequestMapping）。 */
    public static final String OPS_PREFIX = "/api/v2/ops/**";

    private static final String DEVICE_VAR = "deviceId";

    private final MerchantScopeService merchantScopeService;

    public OpsDeviceScopeInterceptor(MerchantScopeService merchantScopeService) {
        this.merchantScopeService = merchantScopeService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String deviceId = pathDeviceId(request);
        if (deviceId == null || deviceId.isBlank()) {
            // 路由里没有 {deviceId} = 不是柜机对象操作；请求体/查询串里的 deviceId 由服务层
            // 各自校验（本闸只管路径变量，避免把「批量按柜筛选」误判成单柜越权）。
            return true;
        }
        Long operatorId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        if (operatorId == null) {
            // fail-closed：能走到这里说明该路径本应已登录（/api/v2/ops/** 不在免登录白名单内）。
            // 静默放行会把「鉴权链被改坏」变成无声越权。
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未认证");
        }
        merchantScopeService.requireDeviceAccess(operatorId, deviceId.trim());
        return true;
    }

    /** 取路径变量 {@code {deviceId}}（Spring 在 handler 匹配阶段写入 request attribute）。 */
    private static String pathDeviceId(HttpServletRequest request) {
        Object attr = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(attr instanceof Map<?, ?> vars)) {
            return null;
        }
        Object raw = vars.get(DEVICE_VAR);
        return raw == null ? null : raw.toString();
    }
}
