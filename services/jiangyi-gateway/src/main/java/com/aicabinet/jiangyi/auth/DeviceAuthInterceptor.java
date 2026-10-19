package com.aicabinet.jiangyi.auth;

import com.aicabinet.jiangyi.client.TradeInternalClient;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 将邑设备面 HTTP 鉴权（CB-022，方案 §3）：拦截 {@code /jiangyi/api/**}
 * （token 签发端点除外），校验 {@code Authorization} 头中的 JWT——兼容
 * 裸 token（文档 V16 §4.2.4 口径、真机实际行为）与 {@code Bearer <JWT>}
 * 两种写法。
 *
 * <p>校验链：验签+exp（无状态）→ 表 status=BOUND → claims.ver ≥ 表 token_version
 * （吊销即失效）。每请求查 trade 换取吊销实时性——一期设备量级个位数，无性能压力；
 * 规模上来后再加进程内短 TTL 缓存。</p>
 *
 * <p>通过后把 deviceId/identifier 写入 request attribute 供 controller 使用；
 * 每次校验记审计 INFO 与指标 {@code cabinet.jiangyi.auth{outcome}}（方案 §3）。</p>
 */
@Component
public class DeviceAuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(DeviceAuthInterceptor.class);

    public static final String ATTR_DEVICE_ID = "jiangyi.deviceId";
    public static final String ATTR_IDENTIFIER = "jiangyi.identifier";

    private final DeviceTokenService deviceTokenService;
    private final TradeInternalClient tradeInternalClient;
    private final MeterRegistry meterRegistry;

    public DeviceAuthInterceptor(DeviceTokenService deviceTokenService,
                                 TradeInternalClient tradeInternalClient,
                                 MeterRegistry meterRegistry) {
        this.deviceTokenService = deviceTokenService;
        this.tradeInternalClient = tradeInternalClient;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();
        String outcome = "ok";
        try {
            String auth = request.getHeader("Authorization");
            // 2026-10-19 深夜修订（log3 15 万行复核，第三轮审计 P2-8）：真机 HTTP 请求
            // Authorization 全部带 Bearer 前缀（token=Bearer 425 次）；裸 JWT 仅 30 次且
            // 全部出现在 WS URL 的 ?token= 查询参数里。旧注释「真机发裸 token（实证）」
            // 是误判。两种写法都兼容（成本≈0，勿再单改一种）：
            //   Authorization: Bearer <JWT>（真机 HTTP 实际行为）
            //   Authorization: <裸JWT>（文档 §4.2.4 字面口径，保留兼容）
            if (auth == null || auth.isBlank()) {
                throw unauthorized("missing_token", "缺少 Authorization token");
            }
            String token = auth.startsWith("Bearer ") ? auth.substring(7).trim() : auth.trim();
            if (token.isBlank()) {
                throw unauthorized("missing_token", "缺少 token");
            }
            Claims claims;
            try {
                claims = deviceTokenService.verify(token);
            } catch (JwtException e) {
                // 2026-10-19 真机排障：advertising 端点报 bad_token 而 getDetail 同 token 通过，
                // 打 token 头部片段（前 24 字符，非完整凭据）定位设备实际传参格式
                log.info("jiangyi bad_token head={} path={}",
                        token.length() <= 24 ? token : token.substring(0, 24) + "...", path);
                throw unauthorized("bad_token", "token 无效或已过期");
            }
            String deviceId = claims.getSubject();
            String identifier = claims.get(DeviceTokenService.CLAIM_IDENTIFIER, String.class);
            Long verClaim = claims.get(DeviceTokenService.CLAIM_VER, Long.class);
            long tokenVer = verClaim == null ? 0L : verClaim;

            TradeInternalClient.JiangyiDeviceView device = tradeInternalClient.device(deviceId).orElse(null);
            if (device == null) {
                throw unauthorized("unknown_device", "设备未登记");
            }
            if (!"BOUND".equals(device.status())) {
                throw unauthorized("not_bound", "设备未绑定或已退役");
            }
            long dbVer = device.tokenVersion() == null ? 0L : device.tokenVersion();
            if (tokenVer < dbVer) {
                throw unauthorized("revoked", "token 已被吊销");
            }
            request.setAttribute(ATTR_DEVICE_ID, deviceId);
            request.setAttribute(ATTR_IDENTIFIER, identifier);
            return true;
        } catch (AuthReject reject) {
            outcome = reject.outcome;
            throw reject.failure;
        } catch (ResponseStatusException e) {
            outcome = "error";
            log.error("jiangyi device auth rejection path={}", path, e);
            throw e;
        } catch (Exception e) {
            // trade 不可达等基础设施故障：fail-closed（拒绝上报好过收脏数据）
            outcome = "error";
            log.error("jiangyi device auth error path={}", path, e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "鉴权依赖暂不可用");
        } finally {
            Counter.builder("cabinet.jiangyi.auth").tag("outcome", outcome)
                    .register(meterRegistry).increment();
            if (!"ok".equals(outcome)) {
                log.info("jiangyi auth outcome={} path={} remote={}", outcome, path,
                        request.getRemoteAddr());
            }
        }
    }

    private static final class AuthReject extends RuntimeException {
        final String outcome;
        final ResponseStatusException failure;

        AuthReject(String outcome, ResponseStatusException failure) {
            // 审计走 finally 分支，栈轨迹无价值，别填
            super(failure.getMessage(), null, false, false);
            this.outcome = outcome;
            this.failure = failure;
        }
    }

    private AuthReject unauthorized(String outcome, String message) {
        return new AuthReject(outcome, new ResponseStatusException(HttpStatus.UNAUTHORIZED, message));
    }
}
