package com.aicabinet.jiangyi.api;

import com.aicabinet.jiangyi.auth.DeviceAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设备域名自钉（2026-10-19 拍板 A，落地于设计文档 §5「域名自钉已实装」）。
 *
 * <p><b>成因</b>：{@code domainConfig/*} 这两条 V15/V16 都没记载，是真机 log3 实测出来的
 * 「设备主动问服务器该连哪儿」的拉取路径。证据（log3 首次开机进程 2579）：开机第一枪
 * {@code token/openDoorDeviceStatus}（行 90，15:34:04.806）→ {@code getConfigStatusByIdentifier}
 * （行 106）→ {@code getConfigDomainByIdentifier}（行 283），三次都带同一个 Bearer、打在同一个
 * base 上 ⇒ 它跟着 {@code Constans.COMMON_URL} 走，<b>不是</b>硬编码的 bootstrap 域名。
 * 云侧响应形如 {@code {"status":null,"msg":null,"data":"https://lingshouapi.hunanjysmart.com/device"}}
 * —注意 {@code status}/{@code msg} 都是 null，与文档「status=200 才算成功」的通用口径不同，
 * 本类照抄这个形态。</p>
 *
 * <p><b>为什么要自钉</b>：设备一旦切到我方（{@code setDomain} + 重启），这三条就会打到我方
 * 网关。此前它们吃 {@link DeviceUnknownPathController} 的 404，而<b>固件对「域名拉取失败」
 * 的处置未经任何实测</b>——若它回落到出厂硬编码域名，症状是「昨天还在线，今早全部回到厂商云」，
 * 属于最难诊断的一类静默失联。回自己的 base 让这个拉取变成幂等操作：无论固件怎么处理，
 * 每次开机都把我方域名再钉一遍。</p>
 *
 * <p><b>fail-safe（不能反过来变成放大器）</b>：自钉等于「我方说什么，设备就信什么」，
 * 一旦 {@code JIANGYI_PUBLIC_BASE_URL} 配错，会把设备主动钉到一个错地址上，且只有厂商
 * 重推 {@code sendDeviceDomain} 才能救回。所以基址缺失/非 http(s)/含空白时，本类<b>不</b>
 * 猜一个值，而是与不设自钉时行为一致：回 404 + 一条 {@code JIANGYI_DOMAIN_PIN_DISABLED} 告警。
 * 两个端点共用同一个开关：绝不允许「告诉设备有待拉配置」而拉取又 404 的半开状态。</p>
 */
@RestController
public class DomainConfigController {

    private static final Logger log = LoggerFactory.getLogger(DomainConfigController.class);

    /** nginx 保留前缀转发到我方网关的路径尾段，必须跟着域名一起下发（V16 §4.2 时序同源约束）。 */
    private static final String API_TAIL = "/jiangyi/api";

    private final String deviceBaseUrl;

    public DomainConfigController(@Value("${aicabinet.jiangyi.public-base-url:}") String publicBaseUrl) {
        this.deviceBaseUrl = normalize(publicBaseUrl);
        if (this.deviceBaseUrl == null) {
            log.warn("JIANGYI_DOMAIN_PIN_DISABLED raw=[{}] ⇒ domainConfig 两端点回 404，"
                    + "已切到我方网关的设备重启后拉不到域名，回落行为未实测（见设计文档 §5 域名自钉）", publicBaseUrl);
        } else {
            log.info("JIANGYI_DOMAIN_PIN armed deviceBaseUrl={}", this.deviceBaseUrl);
        }
    }

    /**
     * 设备问「该连哪个域名」——回我方自己的 base（含 {@code /jiangyi/api} 尾段）。
     * body 里的 identifier 只交叉校验（铁律：固件不可信，不一致以 token 为准并 WARN）。
     */
    @PostMapping("/jiangyi/api/domainConfig/getConfigDomainByIdentifier")
    public Map<String, Object> getConfigDomain(HttpServletRequest request,
                                               @RequestBody(required = false) Map<String, Object> body) {
        requireArmed("getConfigDomain", request, body);
        return cloudEnvelope(deviceBaseUrl);
    }

    /**
     * 设备问「有没有域名配置要拉」。云侧对同一台设备实测回过 {@code data:1}（行 132/540/657）
     * 和 {@code data:2}（行 296），且同一次开机内 1→2、也出现过 1→1 ⇒ 该值不是严格的状态位，
     * 我方不自造状态机：<b>恒回 1</b>（最坏代价是每次开机多拉一次域名，而那次拉取回的就是
     * 当前值，幂等）。回 2 有「设备据此跳过拉取」的可能，跳过 = 放弃自愈，故选 1。
     */
    @PostMapping("/jiangyi/api/domainConfig/getConfigStatusByIdentifier")
    public Map<String, Object> getConfigStatus(HttpServletRequest request,
                                               @RequestBody(required = false) Map<String, Object> body) {
        requireArmed("getConfigStatus", request, body);
        return cloudEnvelope(1);
    }

    private void requireArmed(String endpoint, HttpServletRequest request, Map<String, Object> body) {
        String deviceId = attr(request, DeviceAuthInterceptor.ATTR_DEVICE_ID);
        String identifier = attr(request, DeviceAuthInterceptor.ATTR_IDENTIFIER);
        Object asked = body == null ? null : body.get("identifier");
        if (asked != null && !asked.equals(identifier)) {
            log.warn("jiangyi domainConfig {} identifier MISMATCH token={} body={}", endpoint, identifier, asked);
        }
        if (deviceBaseUrl == null) {
            log.warn("jiangyi domainConfig {} unanswered deviceId={} — 公网基址未配置", endpoint, deviceId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "jiangyi domain pin not configured");
        }
        log.info("jiangyi domainConfig {} pinned deviceId={} identifier={} baseUrl={}",
                endpoint, deviceId, identifier, deviceBaseUrl);
    }

    /** 照抄云侧形态：{@code status}/{@code msg} 为 null，只有 data 有值。 */
    private static Map<String, Object> cloudEnvelope(Object data) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", null);
        resp.put("msg", null);
        resp.put("data", data);
        return resp;
    }

    /**
     * 归一化成「域名 + {@code /jiangyi/api}」；任何拿不准的输入都返回 null（= 不启用自钉），
     * 绝不回退成「猜一个地址」——回错域名比不回域名破坏性大得多。
     */
    private static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty() || value.chars().anyMatch(Character::isWhitespace)) {
            return null;
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            return null;
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.endsWith(API_TAIL) ? value : value + API_TAIL;
    }

    private static String attr(HttpServletRequest request, String name) {
        Object v = request.getAttribute(name);
        return v == null ? "unknown" : v.toString();
    }
}
