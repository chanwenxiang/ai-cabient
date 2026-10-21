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
 * <p><b>成因</b>：{@code domainConfig/*} 这三条 V15/V16 原文都没记载（V16 目录无此节，PDF 原页已核），
 * 是真机 log3 实测出来的「设备主动问服务器该连哪儿」的拉取路径。证据（log3 首次开机进程 2579）：开机第一枪
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
 * 一旦 {@code JIANGYI_PUBLIC_BASE_URL} 配错，会把设备主动钉到一个错地址上，且救回依赖厂商侧
 * （WS 重推 {@code sendDeviceRuntimeType} 写 prefs {@code url}=COMMON_URL 槽；563 smali 实证
 * {@code sendDeviceDomain} 推送只触发重启、不携带值）。所以基址缺失/非 http(s)/含空白时，本类<b>不</b>
 * 猜一个值，而是与不设自钉时行为一致：回 404 + 一条 {@code JIANGYI_DOMAIN_PIN_DISABLED} 告警。
 * 三个端点共用同一个开关：绝不允许「告诉设备有待拉配置」而拉取又 404 的半开状态。</p>
 *
 * <p><b>第三个端点 {@code getConfigSocketUrlByIdentifier}（2026-10-20 补；同日事实修订）</b>：
 * APK V9.10.563 反编译实证（{@code InitAppServerInfoControl$19.onSuccess} 判定
 * 「拉取域名 ≠ {@code COMMON_URL} 且 prefs 的 {@code url}/{@code socketurl} <b>未同时非空</b>」
 * → {@code getOtherToken → getOtherDeviceNum → getOtherSocketUrl} 迁移链）——本端点<b>只在迁移链里</b>
 * 被调用，且请求地址取 {@code COMMON_URL}（设备当前所在服务器）配 {@code COMMON_TOKEN}：
 * 首次切到我方时设备问的是<b>厂商云</b>，只有「我方已是旧服务器、再换新域名」的交接场景才会
 * 问到本端点。真机 4 份 logcat 全量检索该路径 <b>0 次</b>，上一版「每次开机必经、缺席卡死
 * 开机链」的描述作废：
 * <ul>
 *   <li>链的进入条件（{@code $19.onSuccess}，smali 逐行）：拉回值非空且 ≠ {@code COMMON_URL}，
 *   且 prefs {@code url}/{@code socketurl} <b>任一为空</b> ⇒ 进链；两者逐字相等、或两键都非空
 *   ⇒ 整条链跳过。切换完成的稳态（两键都有值）永远不进链。</li>
 *   <li>回值 {@code data} 被 {@code $18} 直接写进 {@code Constans.SOCKET_URL}，其消费者只有
 *   MZ / CARD_258 / CD_WB 三种任务（NORMAL 不读），且三家拼法互相冲突（MZ 追加
 *   {@code /websocket/device/}，CARD_258/CD_WB 追加 {@code /}）⇒ 该值在本固件内无自洽形态。</li>
 *   <li>失败时固件确实原地无限重试（{@code $18.onFailed} 自调用），但前提是设备正从本服务器
 *   迁走——保持 200 + null 信封让该链继续，就是本端点的职责；它是<b>换域交接端点</b>，
 *   不是首切的硬前置（首切时设备问厂商云）。</li>
 * </ul></p>
 */
@RestController
public class DomainConfigController {

    private static final Logger log = LoggerFactory.getLogger(DomainConfigController.class);

    /** nginx 保留前缀转发到我方网关的路径尾段，必须跟着域名一起下发（V16 §4.2 时序同源约束）。 */
    private static final String API_TAIL = "/jiangyi/api";

    /** WS base 形态对齐 V16 §4.3.1 拼接式（{@code "wss://…/websocket/device/"+编号+"?token="+token}）。
     * 回查厂商云得到的同形值实为回显我方 setDomain 写入的槽，非厂商原生形态证据（另见类注释）。 */
    private static final String WS_PATH = "/websocket/device/";

    private final String deviceBaseUrl;

    private final String deviceSocketUrl;

    public DomainConfigController(@Value("${aicabinet.jiangyi.public-base-url:}") String publicBaseUrl) {
        this.deviceBaseUrl = normalize(publicBaseUrl);
        this.deviceSocketUrl = this.deviceBaseUrl == null ? null : toSocketBase(this.deviceBaseUrl);
        if (this.deviceBaseUrl == null) {
            log.warn("JIANGYI_DOMAIN_PIN_DISABLED raw=[{}] ⇒ domainConfig 三端点回 404，"
                    + "已切到我方网关的设备重启后拉不到域名，回落行为未实测（见设计文档 §5 域名自钉）", publicBaseUrl);
        } else {
            log.info("JIANGYI_DOMAIN_PIN armed deviceBaseUrl={} deviceSocketUrl={}",
                    this.deviceBaseUrl, this.deviceSocketUrl);
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
     * 设备问「本机按哪种使用模式跑」。<b>10-20 事实修订</b>：本端点回值在固件里被
     * {@code UpdateBaseInfo$3} 以 {@code Double→int} 写进 {@code Constans.USE_TYPE}
     * （调用点 {@code UpdateBaseInfo.getIsAbandon()}，smali 实测），与「域名配置状态」无关——
     * 此前「恒 1 = 配置待拉」的解释作废。云侧对同一台设备回过 1 也回过 2（同一次开机 1→2、
     * 亦有 1→1）；1 是固件多处（{@code DealSocketMsg} / {@code LockMannager} / {@code DealStringMsg$7}）
     * 判定的常规分支，2 的语义（双门？停用？）文档与固件均未定义，固件只拒绝负数
     * （{@code UpLoadApp.setUseType} 校验）。我方<b>恒回 1</b>：本机走常规分支；若厂商确认
     * 2 另有含义（如按门数区分），再按设备类型回值——已列入厂商问题清单。
     */
    @PostMapping("/jiangyi/api/domainConfig/getConfigStatusByIdentifier")
    public Map<String, Object> getConfigStatus(HttpServletRequest request,
                                               @RequestBody(required = false) Map<String, Object> body) {
        requireArmed("getConfigStatus", request, body);
        return cloudEnvelope(1);
    }

    /**
     * 设备问「WebSocket 该连哪儿」——回我方 WS base（{@code wss://<host>/websocket/device/}）。
     * 值从同一个公网基址推导，保证 HTTP 与 WS 永远同宿主、不会各配一半。
     */
    @PostMapping("/jiangyi/api/domainConfig/getConfigSocketUrlByIdentifier")
    public Map<String, Object> getConfigSocketUrl(HttpServletRequest request,
                                                  @RequestBody(required = false) Map<String, Object> body) {
        requireArmed("getConfigSocketUrl", request, body);
        return cloudEnvelope(deviceSocketUrl);
    }

    private void requireArmed(String endpoint, HttpServletRequest request, Map<String, Object> body) {
        // 2026-10-20 11:15:57 实证：domainConfig/* 已改为免鉴权（设备 token 过期后以无
        // Authorization 形态探测配置，这是固件的自举问路，401 会掐死自愈链）——attribute
        // 因此可能缺席（attr 返回 "unknown" 哨兵），交叉校验只在真的持有 token 身份时做。
        boolean hasTokenIdentity = request.getAttribute(DeviceAuthInterceptor.ATTR_IDENTIFIER) != null;
        String deviceId = attr(request, DeviceAuthInterceptor.ATTR_DEVICE_ID);
        String identifier = attr(request, DeviceAuthInterceptor.ATTR_IDENTIFIER);
        Object asked = body == null ? null : body.get("identifier");
        if (hasTokenIdentity && asked != null && !asked.equals(identifier)) {
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

    /** {@code https://host/jiangyi/api} → {@code wss://host/websocket/device/}（与云侧回查值同形；该值实为回显我方 setDomain 写入，见类注释）。 */
    private static String toSocketBase(String normalizedBaseUrl) {
        String origin = normalizedBaseUrl.substring(0, normalizedBaseUrl.length() - API_TAIL.length());
        boolean secure = origin.startsWith("https://");
        String authority = origin.substring(origin.indexOf("://") + 3);
        return (secure ? "wss://" : "ws://") + authority + WS_PATH;
    }

    private static String attr(HttpServletRequest request, String name) {
        Object v = request.getAttribute(name);
        return v == null ? "unknown" : v.toString();
    }
}
