package com.aicabinet.trade.service;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.domain.*;
import com.aicabinet.trade.mapper.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CompetitiveGapService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final OpsUserDeviceScopeMapper deviceScopeMapper;
    private final OpsUserDeviceScopePrefMapper deviceScopePrefMapper;
    private final MerchantOpsConfigMapper opsConfigMapper;
    private final MerchantRoleTemplateMapper roleTemplateMapper;
    private final DeviceOpsEventMapper deviceOpsEventMapper;
    private final DeviceInfoMapper deviceInfoMapper;
    private final PhoneVerifyLogMapper phoneVerifyLogMapper;
    private final MerchantOnboardingMapper onboardingMapper;
    private final PlatformStoredValueMapper storedValueMapper;
    private final RecognitionComputeAccountMapper computeAccountMapper;
    private final MerchantMapper merchantMapper;
    private final CabinetOrderMapper orderMapper;
    private final CabinetOrderLineMapper lineMapper;
    private final MerchantScopeService merchantScopeService;
    private final PermissionService permissionService;
    private final AdminAuditService auditService;

    public CompetitiveGapService(OpsUserDeviceScopeMapper deviceScopeMapper,
                                 OpsUserDeviceScopePrefMapper deviceScopePrefMapper,
                                 MerchantOpsConfigMapper opsConfigMapper,
                                 MerchantRoleTemplateMapper roleTemplateMapper,
                                 DeviceOpsEventMapper deviceOpsEventMapper,
                                 DeviceInfoMapper deviceInfoMapper,
                                 PhoneVerifyLogMapper phoneVerifyLogMapper,
                                 MerchantOnboardingMapper onboardingMapper,
                                 PlatformStoredValueMapper storedValueMapper,
                                 RecognitionComputeAccountMapper computeAccountMapper,
                                 MerchantMapper merchantMapper,
                                 CabinetOrderMapper orderMapper,
                                 CabinetOrderLineMapper lineMapper,
                                 MerchantScopeService merchantScopeService,
                                 PermissionService permissionService,
                                 AdminAuditService auditService) {
        this.deviceScopeMapper = deviceScopeMapper;
        this.deviceScopePrefMapper = deviceScopePrefMapper;
        this.opsConfigMapper = opsConfigMapper;
        this.roleTemplateMapper = roleTemplateMapper;
        this.deviceOpsEventMapper = deviceOpsEventMapper;
        this.deviceInfoMapper = deviceInfoMapper;
        this.phoneVerifyLogMapper = phoneVerifyLogMapper;
        this.onboardingMapper = onboardingMapper;
        this.storedValueMapper = storedValueMapper;
        this.computeAccountMapper = computeAccountMapper;
        this.merchantMapper = merchantMapper;
        this.orderMapper = orderMapper;
        this.lineMapper = lineMapper;
        this.merchantScopeService = merchantScopeService;
        this.permissionService = permissionService;
        this.auditService = auditService;
    }

    // ---- M2 device scope ----

    @Transactional(readOnly = true)
    public OpsUserDeviceScopeDto getUserDeviceScope(Long operatorId, Long userId) {
        permissionService.requirePermission(operatorId, "ops:rbac:assign");
        String mode = deviceScopePrefMapper.findById(userId)
                .map(OpsUserDeviceScopePref::getScopeMode)
                .orElse("ALL");
        List<String> devices = deviceScopeMapper.findByUserId(userId).stream()
                .map(OpsUserDeviceScope::getDeviceId)
                .toList();
        return new OpsUserDeviceScopeDto(userId, mode, devices);
    }

    @Transactional
    public OpsUserDeviceScopeDto assignUserDeviceScope(Long operatorId, Long userId, OpsUserDeviceScopeDto body) {
        permissionService.requireAnyPermission(operatorId, "ops:rbac:assign:device", "ops:rbac:assign");
        String mode = body.scopeMode() == null || body.scopeMode().isBlank()
                ? "ALL" : body.scopeMode().trim().toUpperCase();
        if (!"ALL".equals(mode) && !"PARTIAL".equals(mode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scopeMode 仅支持 ALL / PARTIAL");
        }
        OpsUserDeviceScopePref pref = deviceScopePrefMapper.findById(userId).orElseGet(OpsUserDeviceScopePref::new);
        pref.setUserId(userId);
        pref.setScopeMode(mode);
        pref.setUpdatedAt(Instant.now());
        deviceScopePrefMapper.save(pref);

        deviceScopeMapper.deleteByUserId(userId);
        if ("PARTIAL".equals(mode) && body.deviceIds() != null) {
            for (String deviceId : body.deviceIds()) {
                if (deviceId == null || deviceId.isBlank()) {
                    continue;
                }
                OpsUserDeviceScope row = new OpsUserDeviceScope();
                row.setUserId(userId);
                row.setDeviceId(deviceId.trim());
                deviceScopeMapper.insert(row);
            }
        }
        auditService.record(operatorId, "OPS_USER_DEVICE_SCOPE", "USER", String.valueOf(userId), mode);
        return getUserDeviceScope(operatorId, userId);
    }

    // ---- M2 org ops config + role templates ----

    @Transactional(readOnly = true)
    public MerchantOpsConfigDto getOpsConfig(Long operatorId, String merchantId) {
        permissionService.requireAnyPermission(operatorId, "ops:merchant:list", "ops:merchant:edit");
        merchantScopeService.requireMerchantAccess(operatorId, merchantId);
        return opsConfigMapper.findById(merchantId)
                .map(this::toOpsConfigDto)
                .orElseGet(() -> defaultOpsConfig(merchantId));
    }

    @Transactional
    public MerchantOpsConfigDto saveOpsConfig(Long operatorId, String merchantId, MerchantOpsConfigDto body) {
        permissionService.requirePermission(operatorId, "ops:merchant:edit");
        merchantScopeService.requireMerchantAccess(operatorId, merchantId);
        MerchantOpsConfig cfg = opsConfigMapper.findById(merchantId).orElseGet(MerchantOpsConfig::new);
        cfg.setMerchantId(merchantId);
        cfg.setStockingType(nz(body.stockingType(), "CAPACITY"));
        cfg.setStockoutThresholdPct(body.stockoutThresholdPct() <= 0 ? 50 : Math.min(100, body.stockoutThresholdPct()));
        cfg.setTallyMode(nz(body.tallyMode(), "INDEPENDENT"));
        cfg.setUseStockingList(body.useStockingList());
        cfg.setReplenishInputType(nz(body.replenishInputType(), "ADD_QTY"));
        cfg.setPhotoStocktake(body.photoStocktake());
        cfg.setPhotoReplenish(body.photoReplenish());
        cfg.setMaxInflightOrders(Math.max(0, Math.min(2, body.maxInflightOrders())));
        cfg.setUpdatedAt(Instant.now());
        opsConfigMapper.save(cfg);
        return toOpsConfigDto(cfg);
    }

    @Transactional(readOnly = true)
    public List<MerchantRoleTemplateDto> listRoleTemplates(Long operatorId) {
        permissionService.requireAnyPermission(operatorId, "ops:merchant:list", "ops:rbac:assign");
        return roleTemplateMapper.findAllOrdered().stream()
                .map(t -> new MerchantRoleTemplateDto(
                        t.getTemplateKey(), t.getTemplateName(), t.getDescription(),
                        t.getPermissionHint(), t.getSortOrder()))
                .toList();
    }

    // ---- M3 device ops + policy ----

    @Transactional(readOnly = true)
    public PageResult<DeviceOpsEventDto> listDeviceOpsEvents(Long operatorId, String eventType,
                                                             int page, int size) {
        permissionService.requireAnyPermission(operatorId, "ops:device-ops:list", "ops:device:list", "ops:sla");
        Set<String> allowed = merchantScopeService.allowedDeviceIds(operatorId);
        if (allowed != null && allowed.isEmpty()) {
            return new PageResult<>(List.of(), page, size, 0);
        }
        ensureSyntheticOfflineEvents(allowed);
        var result = deviceOpsEventMapper.search(
                allowed, eventType, Instant.now().minusSeconds(86400L * 14), Instant.now(),
                page, Math.min(size, 100));
        Map<String, String> names = deviceInfoMapper.findAll().stream()
                .collect(Collectors.toMap(DeviceInfo::getDeviceId, DeviceInfo::getDeviceName, (a, b) -> a));
        List<DeviceOpsEventDto> items = result.getRecords().stream()
                .map(e -> new DeviceOpsEventDto(
                        e.getEventId(), e.getDeviceId(), names.get(e.getDeviceId()),
                        e.getEventType(), e.getSeverity(), e.getTitle(), e.getDetail(), e.getCreatedAt()))
                .toList();
        return new PageResult<>(items, page, size, result.getTotal());
    }

    @Transactional(readOnly = true)
    public DevicePolicyDto getDevicePolicy(Long operatorId, String deviceId) {
        permissionService.requireAnyPermission(operatorId, "ops:device:list", "ops:device:edit");
        merchantScopeService.requireDeviceAccess(operatorId, deviceId);
        DeviceInfo d = deviceInfoMapper.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在"));
        return toPolicy(d);
    }

    @Transactional
    public DevicePolicyDto updateDevicePolicy(Long operatorId, String deviceId, DevicePolicyDto body) {
        permissionService.requirePermission(operatorId, "ops:device:edit");
        merchantScopeService.requireDeviceAccess(operatorId, deviceId);
        DeviceInfo d = deviceInfoMapper.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在"));
        d.setPriceLocked(body.priceLocked());
        d.setSkuEditForbidden(body.skuEditForbidden());
        d.setSaleForbidden(body.saleForbidden());
        if (body.saleForbidden()) {
            d.setSalesLocked(true);
        } else if (!body.salesLocked()) {
            // 仅当明确要求解锁时恢复；saleForbidden 关闭不自动解锁营业锁
        }
        if (body.salesLocked() != d.salesLockedEnabled()) {
            d.setSalesLocked(body.salesLocked());
        }
        deviceInfoMapper.save(d);
        auditService.record(operatorId, "DEVICE_POLICY", "DEVICE", deviceId,
                "priceLocked=" + body.priceLocked() + ";skuEdit=" + body.skuEditForbidden()
                        + ";saleForbidden=" + body.saleForbidden());
        return toPolicy(d);
    }

    // ---- M4 sales reports + phone verify ----

    @Transactional(readOnly = true)
    public List<SalesReportRowDto> salesReport(Long operatorId, String dim, String fromDate, String toDate) {
        permissionService.requireAnyPermission(operatorId, "ops:sales-report:list", "ops:analytics:view", "ops:finance:view");
        LocalDate from = fromDate == null || fromDate.isBlank()
                ? LocalDate.now(ZONE).minusDays(7) : LocalDate.parse(fromDate.trim());
        LocalDate to = toDate == null || toDate.isBlank()
                ? LocalDate.now(ZONE) : LocalDate.parse(toDate.trim());
        Instant start = from.atStartOfDay(ZONE).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(ZONE).toInstant();
        Set<String> deviceIds = merchantScopeService.allowedDeviceIds(operatorId);
        String dimension = dim == null ? "PRODUCT" : dim.trim().toUpperCase();

        return switch (dimension) {
            case "CABINET", "DEVICE" -> aggregateByDevice(deviceIds, start, end);
            case "MERCHANT" -> aggregateByMerchant(deviceIds, start, end);
            case "MARGIN", "PRODUCT" -> aggregateByProduct(deviceIds, start);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dim 支持 PRODUCT/CABINET/MERCHANT/MARGIN");
        };
    }

    @Transactional(readOnly = true)
    public PageResult<PhoneVerifyLogDto> listPhoneVerify(Long operatorId, String phone, String channel,
                                                         int page, int size) {
        permissionService.requireAnyPermission(operatorId, "ops:phone-verify:list", "ops:risk:list", "ops:user:list");
        var result = phoneVerifyLogMapper.search(phone, channel, null, null, page, Math.min(size, 100));
        List<PhoneVerifyLogDto> items = result.getRecords().stream()
                .map(l -> new PhoneVerifyLogDto(l.getLogId(), l.getUserId(), l.getPhone(),
                        l.getChannel(), l.getMerchantId(), l.getVerifiedAt()))
                .toList();
        return new PageResult<>(items, page, size, result.getTotal());
    }

    @Transactional
    public PhoneVerifyLogDto recordPhoneVerify(Long operatorId, PhoneVerifyLogDto body) {
        permissionService.requireAnyPermission(operatorId, "ops:phone-verify:list", "ops:user:list");
        if (body.phone() == null || body.phone().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "手机号不能为空");
        }
        PhoneVerifyLog log = new PhoneVerifyLog();
        log.setUserId(body.userId());
        log.setPhone(body.phone().trim());
        log.setChannel(body.channel() == null || body.channel().isBlank() ? "WECHAT" : body.channel().trim());
        log.setMerchantId(body.merchantId());
        log.setVerifiedAt(Instant.now());
        phoneVerifyLogMapper.insert(log);
        return new PhoneVerifyLogDto(log.getLogId(), log.getUserId(), log.getPhone(),
                log.getChannel(), log.getMerchantId(), log.getVerifiedAt());
    }

    // ---- M5 commercial hub ----

    @Transactional(readOnly = true)
    public List<MerchantOnboardingDto> listOnboarding(Long operatorId) {
        permissionService.requireAnyPermission(operatorId, "ops:commercial-hub:list", "ops:merchant:list");
        Map<String, String> names = merchantMapper.findAll().stream()
                .collect(Collectors.toMap(Merchant::getMerchantId, Merchant::getMerchantName, (a, b) -> a));
        return onboardingMapper.findAllOrdered().stream()
                .map(o -> new MerchantOnboardingDto(
                        o.getOnboardingId(), o.getMerchantId(), names.get(o.getMerchantId()),
                        o.getSubjectType(), o.getAlipayRegStatus(), o.getWechatPayscoreStatus(),
                        o.getOnboardStatus(), o.getExternalMerchantNo(), o.getRemark(),
                        o.getCreatedAt(), o.getUpdatedAt()))
                .toList();
    }

    @Transactional
    public MerchantOnboardingDto upsertOnboarding(Long operatorId, MerchantOnboardingDto body) {
        permissionService.requireAnyPermission(operatorId, "ops:commercial-hub:list", "ops:merchant:edit");
        if (body.merchantId() == null || body.merchantId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "merchantId 不能为空");
        }
        MerchantOnboarding o = body.onboardingId() != null
                ? onboardingMapper.findById(body.onboardingId()).orElseGet(MerchantOnboarding::new)
                : new MerchantOnboarding();
        if (o.getCreatedAt() == null) {
            o.setCreatedAt(Instant.now());
        }
        o.setMerchantId(body.merchantId().trim());
        o.setSubjectType(nz(body.subjectType(), "ENTERPRISE"));
        o.setAlipayRegStatus(nz(body.alipayRegStatus(), "PENDING"));
        o.setWechatPayscoreStatus(nz(body.wechatPayscoreStatus(), "PENDING"));
        o.setOnboardStatus(nz(body.onboardStatus(), "DRAFT"));
        o.setExternalMerchantNo(body.externalMerchantNo());
        o.setRemark(body.remark());
        o.setUpdatedAt(Instant.now());
        if (o.getOnboardingId() == null) {
            onboardingMapper.insert(o);
        } else {
            onboardingMapper.updateById(o);
        }
        Map<String, String> names = merchantMapper.findAll().stream()
                .collect(Collectors.toMap(Merchant::getMerchantId, Merchant::getMerchantName, (a, b) -> a));
        return new MerchantOnboardingDto(
                o.getOnboardingId(), o.getMerchantId(), names.get(o.getMerchantId()),
                o.getSubjectType(), o.getAlipayRegStatus(), o.getWechatPayscoreStatus(),
                o.getOnboardStatus(), o.getExternalMerchantNo(), o.getRemark(),
                o.getCreatedAt(), o.getUpdatedAt());
    }

    @Transactional(readOnly = true)
    public PlatformStoredValueDto getStoredValue(Long operatorId, String merchantId) {
        permissionService.requireAnyPermission(operatorId, "ops:commercial-hub:list", "ops:finance:view");
        return storedValueMapper.findById(merchantId)
                .map(a -> new PlatformStoredValueDto(a.getMerchantId(), a.getBalanceCents(),
                        a.getWarnThresholdCents(), a.getNotifyPhone()))
                .orElse(new PlatformStoredValueDto(merchantId, 0, 0, null));
    }

    @Transactional
    public PlatformStoredValueDto rechargeStoredValue(Long operatorId, String merchantId, long amountCents, String notifyPhone) {
        permissionService.requireAnyPermission(operatorId, "ops:commercial-hub:list", "ops:merchant:edit");
        if (amountCents == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "金额不能为 0");
        }
        PlatformStoredValue acc = storedValueMapper.findById(merchantId).orElseGet(PlatformStoredValue::new);
        acc.setMerchantId(merchantId);
        acc.setBalanceCents(acc.getBalanceCents() + amountCents);
        if (notifyPhone != null && !notifyPhone.isBlank()) {
            acc.setNotifyPhone(notifyPhone.trim());
        }
        acc.setUpdatedAt(Instant.now());
        storedValueMapper.save(acc);
        return new PlatformStoredValueDto(acc.getMerchantId(), acc.getBalanceCents(),
                acc.getWarnThresholdCents(), acc.getNotifyPhone());
    }

    @Transactional
    public PlatformStoredValueDto updateStoredValueWarn(Long operatorId, String merchantId,
                                                        long warnThresholdCents, String notifyPhone) {
        permissionService.requireAnyPermission(operatorId, "ops:commercial-hub:list", "ops:merchant:edit");
        PlatformStoredValue acc = storedValueMapper.findById(merchantId).orElseGet(PlatformStoredValue::new);
        acc.setMerchantId(merchantId);
        acc.setWarnThresholdCents(warnThresholdCents);
        if (notifyPhone != null) {
            acc.setNotifyPhone(notifyPhone.isBlank() ? null : notifyPhone.trim());
        }
        acc.setUpdatedAt(Instant.now());
        storedValueMapper.save(acc);
        return new PlatformStoredValueDto(acc.getMerchantId(), acc.getBalanceCents(),
                acc.getWarnThresholdCents(), acc.getNotifyPhone());
    }

    @Transactional(readOnly = true)
    public RecognitionComputeDto getCompute(Long operatorId, String merchantId) {
        permissionService.requireAnyPermission(operatorId, "ops:commercial-hub:list", "ops:vision:list");
        return computeAccountMapper.findById(merchantId)
                .map(a -> new RecognitionComputeDto(a.getMerchantId(), a.getRemaining(), a.getCumulative(), a.getUsed()))
                .orElse(new RecognitionComputeDto(merchantId, 0, 0, 0));
    }

    @Transactional
    public RecognitionComputeDto grantCompute(Long operatorId, String merchantId, long gained) {
        permissionService.requireAnyPermission(operatorId, "ops:commercial-hub:list", "ops:merchant:edit");
        if (gained <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "发放算力须为正数");
        }
        RecognitionComputeAccount acc = computeAccountMapper.findById(merchantId).orElseGet(RecognitionComputeAccount::new);
        acc.setMerchantId(merchantId);
        acc.setCumulative(acc.getCumulative() + gained);
        acc.setRemaining(acc.getRemaining() + gained);
        acc.setUpdatedAt(Instant.now());
        computeAccountMapper.save(acc);
        return new RecognitionComputeDto(acc.getMerchantId(), acc.getRemaining(), acc.getCumulative(), acc.getUsed());
    }

    // ---- helpers ----

    private void ensureSyntheticOfflineEvents(Set<String> allowed) {
        Instant since = Instant.now().minusSeconds(3600);
        Long recent = deviceOpsEventMapper.selectCount(Wrappers.<DeviceOpsEvent>lambdaQuery()
                .ge(DeviceOpsEvent::getCreatedAt, since));
        if (recent != null && recent > 0) {
            return;
        }
        List<DeviceInfo> devices = allowed == null
                ? deviceInfoMapper.findAll()
                : deviceInfoMapper.findByDeviceIdIn(allowed);
        for (DeviceInfo d : devices) {
            if (d.getOnlineStatus() != null && !"ONLINE".equalsIgnoreCase(d.getOnlineStatus())) {
                DeviceOpsEvent e = new DeviceOpsEvent();
                e.setDeviceId(d.getDeviceId());
                e.setEventType("OFFLINE");
                e.setSeverity("WARN");
                e.setTitle("设备离线");
                e.setDetail("onlineStatus=" + d.getOnlineStatus());
                e.setCreatedAt(Instant.now());
                deviceOpsEventMapper.insert(e);
            }
            if (Boolean.TRUE.equals(d.getSaleForbidden()) || d.salesLockedEnabled()) {
                DeviceOpsEvent e = new DeviceOpsEvent();
                e.setDeviceId(d.getDeviceId());
                e.setEventType("FAULT");
                e.setSeverity("INFO");
                e.setTitle(d.salesLockedEnabled() ? "营业锁机" : "禁售");
                e.setDetail("策略锁生效中");
                e.setCreatedAt(Instant.now());
                deviceOpsEventMapper.insert(e);
            }
        }
    }

    private List<SalesReportRowDto> aggregateByProduct(Set<String> deviceIds, Instant since) {
        List<Object[]> rows = deviceIds == null
                ? lineMapper.skuBreakdownSince(since)
                : deviceIds.isEmpty() ? List.of() : lineMapper.skuBreakdownByDevicesSince(deviceIds, since);
        List<SalesReportRowDto> out = new ArrayList<>();
        for (Object[] row : rows) {
            long qty = ((Number) row[2]).longValue();
            long revenue = ((Number) row[3]).longValue();
            long cogs = ((Number) row[4]).longValue();
            out.add(new SalesReportRowDto(
                    String.valueOf(row[0]),
                    String.valueOf(row[1]),
                    0,
                    qty,
                    revenue,
                    cogs,
                    revenue - cogs
            ));
        }
        return out;
    }

    private List<SalesReportRowDto> aggregateByDevice(Set<String> deviceIds, Instant start, Instant end) {
        List<CabinetOrder> orders = orderMapper.findByCreatedAtBetween(start, end);
        Map<String, Agg> map = new HashMap<>();
        for (CabinetOrder o : orders) {
            if (deviceIds != null && !deviceIds.contains(o.getDeviceId())) {
                continue;
            }
            Agg a = map.computeIfAbsent(o.getDeviceId(), k -> new Agg());
            a.orderCount++;
            a.revenue += o.getTotalAmountCents();
        }
        Map<String, String> names = deviceInfoMapper.findAll().stream()
                .collect(Collectors.toMap(DeviceInfo::getDeviceId, DeviceInfo::getDeviceName, (a, b) -> a));
        return map.entrySet().stream()
                .map(e -> new SalesReportRowDto(
                        e.getKey(), names.getOrDefault(e.getKey(), e.getKey()),
                        e.getValue().orderCount, 0, e.getValue().revenue, 0, e.getValue().revenue))
                .sorted((a, b) -> Long.compare(b.revenueCents(), a.revenueCents()))
                .toList();
    }

    private List<SalesReportRowDto> aggregateByMerchant(Set<String> deviceIds, Instant start, Instant end) {
        Map<String, String> deviceMerchant = deviceInfoMapper.findAll().stream()
                .collect(Collectors.toMap(DeviceInfo::getDeviceId,
                        d -> d.getMerchantId() == null ? "" : d.getMerchantId(), (a, b) -> a));
        List<CabinetOrder> orders = orderMapper.findByCreatedAtBetween(start, end);
        Map<String, Agg> map = new HashMap<>();
        for (CabinetOrder o : orders) {
            if (deviceIds != null && !deviceIds.contains(o.getDeviceId())) {
                continue;
            }
            String mid = deviceMerchant.getOrDefault(o.getDeviceId(), "");
            Agg a = map.computeIfAbsent(mid, k -> new Agg());
            a.orderCount++;
            a.revenue += o.getTotalAmountCents();
        }
        Map<String, String> names = merchantMapper.findAll().stream()
                .collect(Collectors.toMap(Merchant::getMerchantId, Merchant::getMerchantName, (a, b) -> a));
        return map.entrySet().stream()
                .map(e -> new SalesReportRowDto(
                        e.getKey(),
                        names.getOrDefault(e.getKey(), e.getKey().isBlank() ? "(未绑定)" : e.getKey()),
                        e.getValue().orderCount, 0, e.getValue().revenue, 0, e.getValue().revenue))
                .sorted((a, b) -> Long.compare(b.revenueCents(), a.revenueCents()))
                .toList();
    }

    private DevicePolicyDto toPolicy(DeviceInfo d) {
        return new DevicePolicyDto(
                d.getDeviceId(),
                d.salesLockedEnabled(),
                Boolean.TRUE.equals(d.getPriceLocked()),
                Boolean.TRUE.equals(d.getSkuEditForbidden()),
                Boolean.TRUE.equals(d.getSaleForbidden())
        );
    }

    private MerchantOpsConfigDto toOpsConfigDto(MerchantOpsConfig cfg) {
        return new MerchantOpsConfigDto(
                cfg.getMerchantId(),
                cfg.getStockingType(),
                cfg.getStockoutThresholdPct(),
                cfg.getTallyMode(),
                Boolean.TRUE.equals(cfg.getUseStockingList()),
                cfg.getReplenishInputType(),
                Boolean.TRUE.equals(cfg.getPhotoStocktake()),
                Boolean.TRUE.equals(cfg.getPhotoReplenish()),
                cfg.getMaxInflightOrders()
        );
    }

    private static MerchantOpsConfigDto defaultOpsConfig(String merchantId) {
        return new MerchantOpsConfigDto(merchantId, "CAPACITY", 50, "INDEPENDENT",
                true, "ADD_QTY", false, false, 0);
    }

    private static String nz(String v, String def) {
        return v == null || v.isBlank() ? def : v.trim();
    }

    private static class Agg {
        long orderCount;
        long revenue;
    }
}
