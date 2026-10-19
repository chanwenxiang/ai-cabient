package com.aicabinet.trade.service;

import com.aicabinet.trade.client.JiangyiMerchantClient;
import com.aicabinet.trade.domain.JiangyiDevice;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.JiangyiDeviceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;

/**
 * 将邑设备入驻（CB-022，方案 §6.1）：登记 → setDomain 绑定 → 退役。
 *
 * <p>一期入驻流程（真机联调日 1 执行）：</p>
 * <ol>
 *   <li>{@link #register}：登记 jiangyi_device（status=UNBOUND），deviceId 与 device_info
 *       同域（我方业务域柜机 ID）；</li>
 *   <li>{@link #bindDomain}：调将邑 setDomain（tenantId+secret，env-only）把设备的
 *       domain/socketUrl 指向我方 → 取 identifier（setDomain 返回值存疑，缺失时
 *       getIdentifier 兜底）→ status=BOUND；</li>
 *   <li>设备侧 modifyAffiliationTenant 由本服务一并执行（新设备还需 confirmActivate，
 *       V16 §5.4.10——一期测试柜已在将邑平台实验过，无需激活，暂不实现）。</li>
 * </ol>
 *
 * <p>RETIRED 设备拒绝一切上报与路由（gateway 校验 status）。</p>
 */
@Service
public class JiangyiOnboardingService {

    private static final Logger log = LoggerFactory.getLogger(JiangyiOnboardingService.class);

    private final JiangyiDeviceMapper jiangyiDeviceMapper;
    private final DeviceInfoMapper deviceInfoMapper;
    private final JiangyiMerchantClient jiangyiMerchantClient;
    private final JiangyiDeviceDirectory jiangyiDeviceDirectory;
    private final long tenantId;
    private final String secret;

    public JiangyiOnboardingService(JiangyiDeviceMapper jiangyiDeviceMapper,
                                    DeviceInfoMapper deviceInfoMapper,
                                    JiangyiMerchantClient jiangyiMerchantClient,
                                    JiangyiDeviceDirectory jiangyiDeviceDirectory,
                                    @Value("${JIANGYI_TENANT_ID:0}") long tenantId,
                                    @Value("${JIANGYI_SECRET:}") String secret) {
        this.jiangyiDeviceMapper = jiangyiDeviceMapper;
        this.deviceInfoMapper = deviceInfoMapper;
        this.jiangyiMerchantClient = jiangyiMerchantClient;
        this.jiangyiDeviceDirectory = jiangyiDeviceDirectory;
        this.tenantId = tenantId;
        this.secret = secret;
    }

    /** 登记设备（幂等：按 deviceSn 判存；deviceId 冲突视为配置错误直接报错）。 */
    public JiangyiDevice register(String deviceId, String deviceSn, String modelName) {
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(deviceSn, "deviceSn");
        JiangyiDevice existing = jiangyiDeviceMapper.findByDeviceSn(deviceSn);
        if (existing != null) {
            if (!existing.getDeviceId().equals(deviceId)) {
                throw new IllegalStateException("设备 SN 已登记为其他柜机 deviceSn=" + deviceSn
                        + " existingDeviceId=" + existing.getDeviceId());
            }
            return existing;
        }
        Instant now = Instant.now();
        JiangyiDevice device = new JiangyiDevice();
        device.setDeviceId(deviceId);
        device.setDeviceSn(deviceSn);
        device.setModelName(modelName);
        device.setStatus("UNBOUND");
        device.setTokenVersion(0L);
        device.setCreatedAt(now);
        device.setUpdatedAt(now);
        jiangyiDeviceMapper.insert(device);
        jiangyiDeviceDirectory.evict(deviceId);
        log.info("jiangyi device registered deviceId={} deviceSn={} status=UNBOUND", deviceId, deviceSn);
        return device;
    }

    /**
     * setDomain 绑定：将设备 domain/socketUrl 指向我方并落 identifier，status=BOUND。
     * domain/socketUrl 由部署侧提供（nginx 公网 HTTPS/WSS 地址）。
     */
    public JiangyiDevice bindDomain(String deviceSn, String domain, String socketUrl) {
        if (tenantId <= 0 || secret == null || secret.isBlank()) {
            throw new IllegalStateException("将邑租户凭据未配置（JIANGYI_TENANT_ID/JIANGYI_SECRET）");
        }
        JiangyiDevice device = jiangyiDeviceMapper.findByDeviceSn(deviceSn);
        if (device == null) {
            throw new IllegalStateException("设备未登记，请先 register：deviceSn=" + deviceSn);
        }
        if ("RETIRED".equals(device.getStatus())) {
            throw new IllegalStateException("设备已退役，禁止绑定：deviceSn=" + deviceSn);
        }
        // 成因注释（2026-10-19 实证）：将邑云 setDomain 的 socketUrl 为必填（缺省报
        // status=503「socketUrl不能为空」），而 UI 一直宣传「默认自动生成」但后端从未
        // 实现（原样透传 null）——此处补齐：为空时按 V16 §4.2.2 惯例从 domain 推导
        // https://host → wss://host/websocket
        String effectiveSocketUrl = socketUrl == null || socketUrl.isBlank()
                ? deriveSocketUrl(domain)
                : socketUrl.trim();
        // ① setDomain（将邑侧把设备指向我方）；返回 identifier 存疑（文档笔误），有则采纳
        String identifier = jiangyiMerchantClient
                .setDomain(tenantId, secret, domain.trim(), effectiveSocketUrl)
                // ② 兜底：getIdentifier（SN → 将邑签发编码，如 CQYB11253）
                .or(() -> jiangyiMerchantClient.getIdentifier(deviceSn))
                .orElseThrow(() -> new IllegalStateException(
                        "setDomain/getIdentifier 均未返回 identifier，deviceSn=" + deviceSn));
        // ③ 设备绑租户（幂等；已在租户下时重复执行无害）
        jiangyiMerchantClient.modifyAffiliationTenant(deviceSn, tenantId);

        device.setIdentifier(identifier);
        device.setStatus("BOUND");
        device.setUpdatedAt(Instant.now());
        jiangyiDeviceMapper.updateById(device);
        jiangyiDeviceDirectory.evict(device.getDeviceId());
        log.info("jiangyi device bound deviceId={} deviceSn={} identifier={} status=BOUND",
                device.getDeviceId(), deviceSn, identifier);
        return device;
    }

    /**
     * socketUrl 缺省推导（V16 §4.2.2 组装规则：「地址+设备编号+token，这里只需要设置前面的地址，
     * 设备号和 token 在代码中组装」）。成因注释（2026-10-19 实证）：此前推导为
     * wss://host/websocket，少了 /device/ 前缀——设备按「socketUrl+编号+?token=」直拼，
     * 拼出非法路径 ⇒ 真机 HTTP 全通却从不发起 WS（开机每 2 分钟循环）。正确前缀到
     * /websocket/device/ 为止（§4.3.1 完整地址示例反推）。
     */
    private static String deriveSocketUrl(String domain) {
        String d = domain.trim().replaceAll("/+$", "");
        if (d.startsWith("https://")) {
            return "wss://" + d.substring("https://".length()) + "/websocket/device/";
        }
        if (d.startsWith("http://")) {
            return "ws://" + d.substring("http://".length()) + "/websocket/device/";
        }
        return "wss://" + d + "/websocket/device/";
    }

    /** 退役：拒绝一切后续路由与上报（gateway 校验 status）。 */
    public JiangyiDevice retire(String deviceId) {
        JiangyiDevice device = jiangyiDeviceMapper.selectById(deviceId);
        if (device == null) {
            throw new IllegalStateException("将邑设备不存在：deviceId=" + deviceId);
        }
        device.setStatus("RETIRED");
        device.setUpdatedAt(Instant.now());
        jiangyiDeviceMapper.updateById(device);
        jiangyiDeviceDirectory.evict(deviceId);
        log.info("jiangyi device retired deviceId={}", deviceId);
        return device;
    }

    /**
     * token 签发激活开关（审计 A1/P0-3，2026-10-15 拍板块 B）：SN 为出厂可枚举标识，
     * 仅凭「已登记+BOUND」无法防仿冒——运营台逐台激活后方可领 token。
     * 关闭时同步 bump token_version：已发 token（exp 7 天）即刻失效，不能只关开关。
     */
    public JiangyiDevice setDeviceAuthEnabled(String deviceId, boolean enabled) {
        JiangyiDevice device = jiangyiDeviceMapper.selectById(deviceId);
        if (device == null) {
            throw new IllegalStateException("将邑设备不存在：deviceId=" + deviceId);
        }
        if ("RETIRED".equals(device.getStatus())) {
            throw new IllegalStateException("已退役设备不能变更激活状态：deviceId=" + deviceId);
        }
        Instant now = Instant.now();
        jiangyiDeviceMapper.setAuthEnabled(deviceId, enabled, now);
        if (!enabled) {
            jiangyiDeviceMapper.bumpTokenVersion(deviceId, now);
        }
        jiangyiDeviceDirectory.evict(deviceId);
        log.info("jiangyi device auth {} deviceId={} tokenVersion={}",
                enabled ? "ACTIVATED" : "DEACTIVATED+REVOKED", deviceId, device.getTokenVersion());
        return jiangyiDeviceMapper.selectById(deviceId);
    }

    // ---------- 运营后台入口（CB-022 收尾：登记/绑定从手工 SQL 升级为后台 UI） ----------

    /** 按我方柜机 ID 查绑定档案；null = 未登记（后台展示「未接入」态）。 */
    public JiangyiDevice findByDeviceId(String deviceId) {
        return deviceId == null || deviceId.isBlank() ? null : jiangyiDeviceMapper.selectById(deviceId);
    }

    /**
     * 后台登记：前置校验柜机档案存在（防对不存在的 deviceId 造出孤儿绑定），
     * 再走幂等 register（按 SN 判存）。
     */
    public JiangyiDevice registerForDevice(String deviceId, String deviceSn, String modelName) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId 不能为空");
        }
        if (deviceInfoMapper.selectById(deviceId) == null) {
            throw new IllegalArgumentException("柜机不存在，请先在设备管理创建柜机档案：" + deviceId);
        }
        return register(deviceId, deviceSn, modelName);
    }

    /** 后台绑定：deviceId → 已登记 SN → setDomain（将邑租户凭据 env-only）。 */
    public JiangyiDevice bindForDevice(String deviceId, String domain, String socketUrl) {
        JiangyiDevice device = findByDeviceId(deviceId);
        if (device == null) {
            throw new IllegalStateException("设备未登记，请先登记将邑 SN：deviceId=" + deviceId);
        }
        return bindDomain(device.getDeviceSn(), domain, socketUrl);
    }

    /**
     * 后台修改 SN（2026-10-19）：登记时 SN 抄错（铭牌 J/1、O/0 易混）的纠正入口。
     * 仅限 UNBOUND——BOUND 设备的 identifier 与将邑侧 setDomain 指向都跟 SN 走，
     * 直接改 SN 会造成 identifier 悬空，须退役后重新登记+接入。
     */
    public JiangyiDevice updateDeviceSn(String deviceId, String newSn) {
        Objects.requireNonNull(newSn, "newSn");
        String sn = newSn.trim();
        if (sn.isEmpty()) {
            throw new IllegalArgumentException("设备 SN 不能为空");
        }
        JiangyiDevice device = jiangyiDeviceMapper.selectById(deviceId);
        if (device == null) {
            throw new IllegalStateException("将邑设备不存在：deviceId=" + deviceId);
        }
        if ("RETIRED".equals(device.getStatus())) {
            throw new IllegalStateException("已退役设备不能修改 SN：deviceId=" + deviceId);
        }
        if (!"UNBOUND".equals(device.getStatus())) {
            throw new IllegalStateException(
                    "已绑定设备不能直接修改 SN（identifier 跟 SN 走），请先退役再重新登记接入：deviceSn="
                            + device.getDeviceSn());
        }
        if (sn.equals(device.getDeviceSn())) {
            return device; // 幂等：SN 未变化
        }
        JiangyiDevice conflict = jiangyiDeviceMapper.findByDeviceSn(sn);
        if (conflict != null) {
            throw new IllegalStateException("该 SN 已登记为其他柜机：deviceSn=" + sn
                    + " existingDeviceId=" + conflict.getDeviceId());
        }
        String oldSn = device.getDeviceSn();
        device.setDeviceSn(sn);
        device.setUpdatedAt(Instant.now());
        jiangyiDeviceMapper.updateById(device);
        jiangyiDeviceDirectory.evict(device.getDeviceId());
        log.info("jiangyi device sn updated deviceId={} oldSn={} newSn={}", deviceId, oldSn, sn);
        return device;
    }

    /**
     * 将邑侧激活（V16 §5.4.10 confirmActivate，2026-10-19）：出厂未入库设备在将邑云查无
     * SN（getIdentifier 报「设备sn获取设备为空」），须先用铭牌「设备号」confirmActivate
     * 入库。identifier 缺失时由本方法入参补录（铭牌手工核对），传了就落库。
     */
    public JiangyiDevice activateAtJiangyi(String deviceId, String identifier, String deviceName,
                                           String deviceAddress, boolean hasScreen) {
        JiangyiDevice device = jiangyiDeviceMapper.selectById(deviceId);
        if (device == null) {
            throw new IllegalStateException("将邑设备不存在：deviceId=" + deviceId);
        }
        if ("RETIRED".equals(device.getStatus())) {
            throw new IllegalStateException("已退役设备不能激活：deviceId=" + deviceId);
        }
        String effectiveIdentifier = identifier == null || identifier.isBlank()
                ? device.getIdentifier()
                : identifier.trim();
        if (effectiveIdentifier == null || effectiveIdentifier.isBlank()) {
            throw new IllegalStateException(
                    "缺少设备编码（identifier）：请对照柜体铭牌「设备号」（CQYBxxxxx）填入");
        }
        jiangyiMerchantClient.confirmActivate(effectiveIdentifier, device.getDeviceSn(),
                deviceName, deviceAddress, hasScreen ? "has" : "no");
        if (!effectiveIdentifier.equals(device.getIdentifier())) {
            device.setIdentifier(effectiveIdentifier);
            device.setUpdatedAt(Instant.now());
            jiangyiDeviceMapper.updateById(device);
            jiangyiDeviceDirectory.evict(device.getDeviceId());
        }
        log.info("jiangyi device activated at vendor deviceId={} identifier={} deviceSn={}",
                deviceId, effectiveIdentifier, device.getDeviceSn());
        return device;
    }
}
