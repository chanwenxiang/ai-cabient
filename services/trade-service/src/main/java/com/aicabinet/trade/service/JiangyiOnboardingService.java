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
        // ① setDomain（将邑侧把设备指向我方）；返回 identifier 存疑（文档笔误），有则采纳
        String identifier = jiangyiMerchantClient
                .setDomain(tenantId, secret, domain, socketUrl)
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
}
