package com.aicabinet.trade.service;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.domain.*;
import com.aicabinet.trade.mapper.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Lazy;
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
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
public class CompetitiveGapService {
    private static final String PERM_OPS_PHONE_VERIFY_LIST = "ops:phone-verify:list";
    private static final String PERM_OPS_RBAC_ASSIGN = "ops:rbac:assign";
    private static final String PERM_OPS_USER_LIST = "ops:user:list";
    private static final String DEVICE_IDS = "DEVICE_IDS";
    private static final String LIMIT_2000 = "LIMIT 2000";
    private static final String PRODUCT = "PRODUCT";
    private static final String DEVICE = "DEVICE";


    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final OpsUserDeviceScopeMapper deviceScopeMapper;
    private final OpsUserDeviceScopePrefMapper deviceScopePrefMapper;
    private final MerchantOpsConfigMapper opsConfigMapper;
    private final MerchantRoleTemplateMapper roleTemplateMapper;
    private final DeviceOpsEventMapper deviceOpsEventMapper;
    private final DeviceInfoMapper deviceInfoMapper;
    private final PhoneVerifyLogMapper phoneVerifyLogMapper;
    private final MerchantMapper merchantMapper;
    private final CabinetOrderMapper orderMapper;
    private final CabinetOrderLineMapper lineMapper;
    private final MerchantScopeService merchantScopeService;
    private final PermissionService permissionService;
    private final AdminAuditService auditService;
    private final DeviceSalesLockService salesLockService;
    private final SecurityProperties securityProperties;
    private final OpsUserRouteScopeMapper routeScopeMapper;
    private final DistributedLockService distributedLockService;
    /** 经 Spring 代理调用本类 @Transactional 方法，避免自调用失效。 */
    private final CompetitiveGapService self;

    public CompetitiveGapService(OpsUserDeviceScopeMapper deviceScopeMapper,
                                 OpsUserDeviceScopePrefMapper deviceScopePrefMapper,
                                 MerchantOpsConfigMapper opsConfigMapper,
                                 MerchantRoleTemplateMapper roleTemplateMapper,
                                 DeviceOpsEventMapper deviceOpsEventMapper,
                                 DeviceInfoMapper deviceInfoMapper,
                                 PhoneVerifyLogMapper phoneVerifyLogMapper,
                                 MerchantMapper merchantMapper,
                                 CabinetOrderMapper orderMapper,
                                 CabinetOrderLineMapper lineMapper,
                                 MerchantScopeService merchantScopeService,
                                 PermissionService permissionService,
                                 AdminAuditService auditService,
                                 DeviceSalesLockService salesLockService,
                                 SecurityProperties securityProperties,
                                 OpsUserRouteScopeMapper routeScopeMapper,
                                 DistributedLockService distributedLockService, @Lazy CompetitiveGapService self) {
        this.deviceScopeMapper = deviceScopeMapper;
        this.deviceScopePrefMapper = deviceScopePrefMapper;
        this.opsConfigMapper = opsConfigMapper;
        this.roleTemplateMapper = roleTemplateMapper;
        this.deviceOpsEventMapper = deviceOpsEventMapper;
        this.deviceInfoMapper = deviceInfoMapper;
        this.phoneVerifyLogMapper = phoneVerifyLogMapper;
        this.merchantMapper = merchantMapper;
        this.orderMapper = orderMapper;
        this.lineMapper = lineMapper;
        this.merchantScopeService = merchantScopeService;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.salesLockService = salesLockService;
        this.securityProperties = securityProperties;
        this.routeScopeMapper = routeScopeMapper;
        this.distributedLockService = distributedLockService;
        this.self = self;
    }

    // ---- M2 device scope ----

    @Transactional(readOnly = true)
    public OpsUserDeviceScopeDto getUserDeviceScope(Long operatorId, Long userId) {
        permissionService.requirePermission(operatorId, PERM_OPS_RBAC_ASSIGN);
        String mode = deviceScopePrefMapper.findById(userId)
                .map(OpsUserDeviceScopePref::getScopeMode)
                .orElse("ALL");
        if ("PARTIAL".equalsIgnoreCase(mode)) {
            mode = DEVICE_IDS;
        }
        List<String> devices = deviceScopeMapper.findByUserId(userId).stream()
                .map(OpsUserDeviceScope::getDeviceId)
                .toList();
        List<String> routes = routeScopeMapper.findByUserId(userId).stream()
                .map(OpsUserRouteScope::getRouteCode)
                .toList();
        return new OpsUserDeviceScopeDto(userId, mode, devices, routes);
    }

    @Transactional
    public OpsUserDeviceScopeDto assignUserDeviceScope(Long operatorId, Long userId, OpsUserDeviceScopeDto body) {
        permissionService.requireAnyPermission(operatorId, "ops:rbac:assign:device", PERM_OPS_RBAC_ASSIGN);
        return runWithDeviceScopeLock(userId, () -> doAssignUserDeviceScope(operatorId, userId, body));
    }

    private OpsUserDeviceScopeDto doAssignUserDeviceScope(Long operatorId, Long userId, OpsUserDeviceScopeDto body) {
        String mode = normalizeScopeMode(body.scopeMode());
        saveScopePreference(userId, mode);
        deviceScopeMapper.deleteByUserId(userId);
        routeScopeMapper.deleteByUserId(userId);
        persistDeviceScopes(userId, mode, body.deviceIds());
        persistRouteScopes(userId, mode, body.routeCodes());
        auditService.record(operatorId, "OPS_USER_DEVICE_SCOPE", "USER", String.valueOf(userId), mode);
        return self.getUserDeviceScope(operatorId, userId);
    }

    private static String normalizeScopeMode(String rawMode) {
        String mode = rawMode == null || rawMode.isBlank()
                ? "ALL" : rawMode.trim().toUpperCase();
        if ("PARTIAL".equals(mode)) {
            mode = DEVICE_IDS;
        }
        if (!Set.of("ALL", DEVICE_IDS, "ROUTE").contains(mode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scopeMode 仅支持 ALL / DEVICE_IDS / ROUTE");
        }
        return mode;
    }

    private void saveScopePreference(Long userId, String mode) {
        OpsUserDeviceScopePref pref = deviceScopePrefMapper.findByIdForUpdate(userId).orElseGet(OpsUserDeviceScopePref::new);
        pref.setUserId(userId);
        pref.setScopeMode(mode);
        pref.setUpdatedAt(Instant.now());
        deviceScopePrefMapper.save(pref);
    }

    private void persistDeviceScopes(Long userId, String mode, List<String> deviceIds) {
        if (!DEVICE_IDS.equals(mode) || deviceIds == null) {
            return;
        }
        for (String deviceId : deviceIds) {
            if (deviceId == null || deviceId.isBlank()) {
                continue;
            }
            OpsUserDeviceScope row = new OpsUserDeviceScope();
            row.setUserId(userId);
            row.setDeviceId(deviceId.trim());
            deviceScopeMapper.insert(row);
        }
    }

    private void persistRouteScopes(Long userId, String mode, List<String> routeCodes) {
        if (!"ROUTE".equals(mode) || routeCodes == null) {
            return;
        }
        for (String route : routeCodes) {
            if (route == null || route.isBlank()) {
                continue;
            }
            OpsUserRouteScope row = new OpsUserRouteScope();
            row.setUserId(userId);
            row.setRouteCode(route.trim());
            routeScopeMapper.insert(row);
        }
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
        return runWithMerchantLock(merchantId, () -> doSaveOpsConfig(merchantId, body));
    }

    private MerchantOpsConfigDto doSaveOpsConfig(String merchantId, MerchantOpsConfigDto body) {
        MerchantOpsConfig cfg = opsConfigMapper.findByIdForUpdate(merchantId).orElseGet(MerchantOpsConfig::new);
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
        permissionService.requireAnyPermission(operatorId, "ops:merchant:list", PERM_OPS_RBAC_ASSIGN);
        return roleTemplateMapper.findAllOrdered().stream()
                .map(t -> new MerchantRoleTemplateDto(
                        t.getTemplateKey(), t.getTemplateName(), t.getDescription(),
                        t.getPermissionHint(), t.getSortOrder()))
                .toList();
    }

    // ---- M3 device ops + policy ----

    @Transactional
    public PageResult<DeviceOpsEventDto> listDeviceOpsEvents(Long operatorId, String eventType,
                                                             int page, int size, boolean eventIdAsc) {
        return self.listDeviceOpsEvents(operatorId, eventType, null, null, page, size, eventIdAsc);
    }

    @Transactional
    public PageResult<DeviceOpsEventDto> listDeviceOpsEvents(
            Long operatorId,
            String eventType,
            String severity,
            String deviceId,
            int page,
            int size,
            boolean eventIdAsc) {
        permissionService.requireAnyPermission(operatorId, "ops:device-ops:list", "ops:device:list", "ops:sla");
        Set<String> allowed = merchantScopeService.allowedDeviceIds(operatorId);
        if (allowed != null && allowed.isEmpty()) {
            return new PageResult<>(List.of(), page, size, 0);
        }
        if (deviceId != null && !deviceId.isBlank()) {
            merchantScopeService.requireDeviceAccess(operatorId, deviceId.trim());
        }
        // 仅本地 mock：列表为空时补演示事件；生产应由真实心跳/扫描任务写事件，禁止读接口写库
        if (securityProperties.mockEnabled()) {
            ensureSyntheticOfflineEvents(allowed);
            ensureNoSalesEvents(allowed);
        }
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        var result = deviceOpsEventMapper.search(
                allowed, eventType, severity, deviceId,
                Instant.now().minusSeconds(86400L * 14), Instant.now(),
                p, s, eventIdAsc);
        Set<String> nameIds = result.getRecords().stream()
                .map(DeviceOpsEvent::getDeviceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, String> names = deviceInfoMapper.findByDeviceIdIn(nameIds).stream()
                .collect(Collectors.toMap(DeviceInfo::getDeviceId, DeviceInfo::getDeviceName, (a, b) -> a));
        List<DeviceOpsEventDto> items = result.getRecords().stream()
                .map(e -> new DeviceOpsEventDto(
                        e.getEventId(), e.getDeviceId(), names.get(e.getDeviceId()),
                        e.getEventType(), e.getSeverity(), e.getTitle(), e.getDetail(), e.getCreatedAt()))
                .toList();
        return new PageResult<>(items, p, s, result.getTotal());
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
        return runWithDevicePolicyLock(deviceId, () -> doUpdateDevicePolicy(operatorId, deviceId, body));
    }

    private DevicePolicyDto doUpdateDevicePolicy(Long operatorId, String deviceId, DevicePolicyDto body) {
        DeviceInfo d = deviceInfoMapper.findByIdForUpdate(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在"));
        d.setPriceLocked(body.priceLocked());
        d.setSkuEditForbidden(body.skuEditForbidden());

        // 禁售 ⇒ 营业锁机；二者与运维「锁机停售」共用 DeviceSalesLockService（DB + MQTT）
        boolean wantForbidden = body.saleForbidden();
        boolean wantLocked = body.salesLocked() || wantForbidden;
        boolean wasLocked = d.salesLockedEnabled();

        d.setSaleForbidden(wantForbidden);
        deviceInfoMapper.save(d);

        if (wantLocked != wasLocked) {
            String reason;
            if (wantForbidden) {
                reason = "policy:saleForbidden→lock";
            } else if (wantLocked) {
                reason = "policy:salesLocked=on";
            } else {
                reason = "policy:salesLocked=off";
            }
            salesLockService.applySalesLock(operatorId, d, wantLocked, reason, true);
            if (wantForbidden) {
                // 解锁逻辑会清禁售；此处锁机+禁售需保持禁售标记
                d.setSaleForbidden(true);
                deviceInfoMapper.save(d);
            }
        }

        auditService.record(operatorId, "DEVICE_POLICY", DEVICE, deviceId,
                "priceLocked=" + body.priceLocked()
                        + ";skuEdit=" + body.skuEditForbidden()
                        + ";saleForbidden=" + wantForbidden
                        + ";salesLocked=" + wantLocked);
        return toPolicy(deviceInfoMapper.findById(deviceId).orElse(d));
    }

    private <T> T runWithDevicePolicyLock(String deviceId, Supplier<T> action) {
        return runWithLock(DeviceSalesLockService.deviceSalesLockKey(deviceId), action);
    }

    // ---- M4 sales reports + phone verify ----

    @Transactional(readOnly = true)
    public List<SalesReportRowDto> salesReport(Long operatorId, String dim, String fromDate, String toDate) {
        return self.salesReport(operatorId, dim, fromDate, toDate, null);
    }

    @Transactional(readOnly = true)
    public List<SalesReportRowDto> salesReport(Long operatorId, String dim, String fromDate, String toDate,
                                               String deviceId) {
        permissionService.requireAnyPermission(operatorId, "ops:sales-report:list", "ops:analytics:view", "ops:finance:view");
        LocalDate from = fromDate == null || fromDate.isBlank()
                ? LocalDate.now(ZONE).minusDays(7) : LocalDate.parse(fromDate.trim());
        LocalDate to = toDate == null || toDate.isBlank()
                ? LocalDate.now(ZONE) : LocalDate.parse(toDate.trim());
        Instant start = from.atStartOfDay(ZONE).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(ZONE).toInstant();
        Set<String> deviceIds = merchantScopeService.allowedDeviceIds(operatorId);
        if (deviceId != null && !deviceId.isBlank()) {
            String did = deviceId.trim();
            if (deviceIds != null && !deviceIds.contains(did)) {
                return List.of();
            }
            deviceIds = Set.of(did);
        }
        String dimension = dim == null ? PRODUCT : dim.trim().toUpperCase();

        return switch (dimension) {
            case "CABINET", DEVICE -> aggregateByDevice(deviceIds, start, end);
            case "MERCHANT" -> aggregateByMerchant(deviceIds, start, end);
            case "MARGIN", PRODUCT, "SKU" -> aggregateByProduct(deviceIds, start, end);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dim 支持 PRODUCT/SKU/CABINET/MERCHANT/MARGIN");
        };
    }

    @Transactional(readOnly = true)
    public PageResult<SalesReportRowDto> salesReportPage(Long operatorId, String dim, String fromDate,
                                                         String toDate, String deviceId, int page, int size) {
        List<SalesReportRowDto> all = self.salesReport(operatorId, dim, fromDate, toDate, deviceId);
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        int from = p * s;
        if (from >= all.size()) {
            return new PageResult<>(List.of(), p, s, all.size());
        }
        int to = Math.min(from + s, all.size());
        return new PageResult<>(all.subList(from, to), p, s, all.size());
    }

    /** 商户可读子集：商品 / 货柜 / 毛利（不含跨商户 MERCHANT 维）。 */
    @Transactional(readOnly = true)
    public List<SalesReportRowDto> salesReportForDevices(Set<String> deviceIds, String dim,
                                                         String fromDate, String toDate) {
        LocalDate from = fromDate == null || fromDate.isBlank()
                ? LocalDate.now(ZONE).minusDays(7) : LocalDate.parse(fromDate.trim());
        LocalDate to = toDate == null || toDate.isBlank()
                ? LocalDate.now(ZONE) : LocalDate.parse(toDate.trim());
        Instant start = from.atStartOfDay(ZONE).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(ZONE).toInstant();
        Set<String> scoped = deviceIds == null ? Set.of() : deviceIds;
        String dimension = dim == null ? PRODUCT : dim.trim().toUpperCase();
        return switch (dimension) {
            case "CABINET", DEVICE -> aggregateByDevice(scoped, start, end);
            case "MARGIN", PRODUCT, "SKU" -> aggregateByProduct(scoped, start, end);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "商户报表 dim 支持 PRODUCT/CABINET/MARGIN");
        };
    }

    /** 短信/渠道验证成功后写入审计流水（无运营权限校验）。 */
    @Transactional
    public void auditPhoneVerify(Long userId, String phone, String channel) {
        if (phone == null || phone.isBlank()) {
            return;
        }
        PhoneVerifyLog log = new PhoneVerifyLog();
        log.setUserId(userId);
        log.setPhone(phone.trim());
        log.setChannel(channel == null || channel.isBlank() ? "SMS" : channel.trim().toUpperCase());
        log.setVerifiedAt(Instant.now());
        phoneVerifyLogMapper.insert(log);
    }

    public String salesReportCsv(List<SalesReportRowDto> rows) {
        StringBuilder sb = new StringBuilder("dimKey,dimLabel,orderCount,qty,revenueCents,cogsCents,marginCents\n");
        for (SalesReportRowDto r : rows) {
            sb.append(csv(r.dimKey())).append(',')
                    .append(csv(r.dimLabel())).append(',')
                    .append(r.orderCount()).append(',')
                    .append(r.qty()).append(',')
                    .append(r.revenueCents()).append(',')
                    .append(r.cogsCents()).append(',')
                    .append(r.marginCents()).append('\n');
        }
        return sb.toString();
    }

    private static String csv(String v) {
        String s = v == null ? "" : v.replace("\"", "\"\"");
        return "\"" + s + "\"";
    }

    @Transactional(readOnly = true)
    public PageResult<PhoneVerifyLogDto> listPhoneVerify(Long operatorId, String phone, String channel,
                                                         int page, int size) {
        permissionService.requireAnyPermission(operatorId, PERM_OPS_PHONE_VERIFY_LIST, "ops:risk:list", PERM_OPS_USER_LIST);
        var result = phoneVerifyLogMapper.search(phone, channel, null, null, page, Math.min(size, 100));
        Map<String, String> merchantNames = merchantNamesForLogs(result.getRecords());
        List<PhoneVerifyLogDto> items = result.getRecords().stream()
                .map(l -> toPhoneVerifyDto(l, merchantNames))
                .toList();
        return new PageResult<>(items, page, size, result.getTotal());
    }

    private Map<String, String> merchantNamesForLogs(List<PhoneVerifyLog> logs) {
        Set<String> ids = logs.stream()
                .map(PhoneVerifyLog::getMerchantId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return merchantMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Merchant::getMerchantId, Merchant::getMerchantName, (a, b) -> a));
    }

    private PhoneVerifyLogDto toPhoneVerifyDto(PhoneVerifyLog log, Map<String, String> merchantNames) {
        String merchantId = log.getMerchantId();
        String merchantName = merchantId != null && !merchantId.isBlank()
                ? merchantNames.get(merchantId) : null;
        return new PhoneVerifyLogDto(log.getLogId(), log.getUserId(), log.getPhone(),
                log.getChannel(), merchantId, merchantName, log.getVerifiedAt());
    }

    @Transactional
    public PhoneVerifyLogDto recordPhoneVerify(Long operatorId, PhoneVerifyLogDto body) {
        permissionService.requireAnyPermission(operatorId, PERM_OPS_PHONE_VERIFY_LIST, PERM_OPS_USER_LIST);
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
        return toPhoneVerifyDto(log, merchantNamesForLogs(List.of(log)));
    }

    @Transactional
    public PhoneVerifyLogDto updatePhoneVerify(Long operatorId, Long logId, PhoneVerifyLogDto body) {
        permissionService.requireAnyPermission(operatorId, PERM_OPS_PHONE_VERIFY_LIST, PERM_OPS_USER_LIST);
        return runWithPhoneVerifyLogLock(logId, () -> doUpdatePhoneVerify(logId, body));
    }

    private PhoneVerifyLogDto doUpdatePhoneVerify(Long logId, PhoneVerifyLogDto body) {
        PhoneVerifyLog log = phoneVerifyLogMapper.findByIdForUpdate(logId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "验证记录不存在"));
        if (body.phone() == null || body.phone().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "手机号不能为空");
        }
        log.setUserId(body.userId());
        log.setPhone(body.phone().trim());
        log.setChannel(body.channel() == null || body.channel().isBlank() ? log.getChannel() : body.channel().trim());
        log.setMerchantId(body.merchantId());
        phoneVerifyLogMapper.updateById(log);
        return toPhoneVerifyDto(log, merchantNamesForLogs(List.of(log)));
    }

    @Transactional
    public void deletePhoneVerify(Long operatorId, Long logId) {
        permissionService.requireAnyPermission(operatorId, PERM_OPS_PHONE_VERIFY_LIST, PERM_OPS_USER_LIST);
        runWithPhoneVerifyLogLock(logId, () -> {
            doDeletePhoneVerify(logId);
            return null;
        });
    }

    private void doDeletePhoneVerify(Long logId) {
        if (phoneVerifyLogMapper.findByIdForUpdate(logId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "验证记录不存在");
        }
        phoneVerifyLogMapper.deleteById(logId);
    }

    static String opsDeviceScopeLockKey(long userId) {
        return "ops:device-scope:" + userId;
    }

    static String phoneVerifyLogLockKey(long logId) {
        return "phone-verify:log:" + logId;
    }

    private <T> T runWithDeviceScopeLock(long userId, Supplier<T> action) {
        return runWithLock(opsDeviceScopeLockKey(userId), action);
    }

    private <T> T runWithMerchantLock(String merchantId, Supplier<T> action) {
        return runWithLock(MerchantService.merchantLockKey(merchantId), action);
    }

    private <T> T runWithPhoneVerifyLogLock(long logId, Supplier<T> action) {
        return runWithLock(phoneVerifyLogLockKey(logId), action);
    }

    private <T> T runWithLock(String lockKey, Supplier<T> action) {
        if (!distributedLockService.tryLock(lockKey, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "配置处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(lockKey);
        }
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
                ? deviceInfoMapper.selectList(Wrappers.<DeviceInfo>lambdaQuery()
                .orderByAsc(DeviceInfo::getDeviceId)
                .last(LIMIT_2000))
                : deviceInfoMapper.findByDeviceIdIn(allowed);
        for (DeviceInfo d : devices) {
            if (d.getOnlineStatus() != null && !"ONLINE".equalsIgnoreCase(d.getOnlineStatus())) {
                DeviceOpsEvent e = new DeviceOpsEvent();
                e.setDeviceId(d.getDeviceId());
                e.setEventType("OFFLINE");
                e.setSeverity("WARN");
                e.setTitle("设备离线");
                e.setDetail("在线状态：" + onlineStatusLabel(d.getOnlineStatus()));
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

    private void ensureNoSalesEvents(Set<String> allowed) {
        Instant since = Instant.now().minusSeconds(86400L * 7);
        Instant hourAgo = Instant.now().minusSeconds(3600);
        Long recentNoSales = deviceOpsEventMapper.selectCount(Wrappers.<DeviceOpsEvent>lambdaQuery()
                .eq(DeviceOpsEvent::getEventType, "NO_SALES")
                .ge(DeviceOpsEvent::getCreatedAt, hourAgo));
        if (recentNoSales != null && recentNoSales > 0) {
            return;
        }
        List<DeviceInfo> devices = allowed == null
                ? deviceInfoMapper.selectList(Wrappers.<DeviceInfo>lambdaQuery()
                .orderByAsc(DeviceInfo::getDeviceId)
                .last(LIMIT_2000))
                : deviceInfoMapper.findByDeviceIdIn(allowed);
        Set<String> sold = orderMapper.selectObjs(Wrappers.<CabinetOrder>query()
                        .select("DISTINCT device_id")
                        .ge("created_at", since))
                .stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .collect(Collectors.toSet());
        for (DeviceInfo d : devices) {
            if (!"DEPLOYED".equalsIgnoreCase(DeviceAssetService.normalizeLifecycle(d.getLifecycleStatus()))) {
                continue;
            }
            if (sold.contains(d.getDeviceId())) {
                continue;
            }
            DeviceOpsEvent e = new DeviceOpsEvent();
            e.setDeviceId(d.getDeviceId());
            e.setEventType("NO_SALES");
            e.setSeverity("WARN");
            e.setTitle("近7日无销售");
            e.setDetail("生命周期：已部署");
            e.setCreatedAt(Instant.now());
            deviceOpsEventMapper.insert(e);
        }
    }

    private List<SalesReportRowDto> aggregateByProduct(Set<String> deviceIds, Instant start, Instant end) {
        List<Object[]> rows;
        if (deviceIds == null) {
            rows = lineMapper.skuBreakdownBetween(start, end);
        } else if (deviceIds.isEmpty()) {
            rows = List.of();
        } else {
            rows = lineMapper.skuBreakdownByDevicesBetween(deviceIds, start, end);
        }
        List<SalesReportRowDto> out = new ArrayList<>();
        for (Object[] row : rows) {
            long qty = ((Number) row[2]).longValue();
            long revenue = ((Number) row[3]).longValue();
            long cogs = ((Number) row[4]).longValue();
            long orderCount = row.length > 5 && row[5] instanceof Number n ? n.longValue() : 0L;
            out.add(new SalesReportRowDto(
                    String.valueOf(row[0]),
                    String.valueOf(row[1]),
                    orderCount,
                    qty,
                    revenue,
                    cogs,
                    revenue - cogs
            ));
        }
        return out;
    }

    private List<SalesReportRowDto> aggregateByDevice(Set<String> deviceIds, Instant start, Instant end) {
        if (deviceIds != null && deviceIds.isEmpty()) {
            return List.of();
        }
        List<Object[]> rows = lineMapper.deviceBreakdownBetween(deviceIds, start, end);
        Map<String, String> names = deviceInfoMapper.findByDeviceIdIn(
                        rows.stream().map(r -> String.valueOf(r[0])).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(DeviceInfo::getDeviceId, DeviceInfo::getDeviceName, (a, b) -> a));
        return rows.stream()
                .map(r -> {
                    String did = String.valueOf(r[0]);
                    long qty = ((Number) r[1]).longValue();
                    long revenue = ((Number) r[2]).longValue();
                    long cogs = ((Number) r[3]).longValue();
                    long orderCount = ((Number) r[4]).longValue();
                    return new SalesReportRowDto(
                            did, names.getOrDefault(did, did),
                            orderCount, qty, revenue, cogs, revenue - cogs);
                })
                .sorted((a, b) -> Long.compare(b.revenueCents(), a.revenueCents()))
                .toList();
    }

    private List<SalesReportRowDto> aggregateByMerchant(Set<String> deviceIds, Instant start, Instant end) {
        List<DeviceInfo> devices = deviceIds == null
                ? deviceInfoMapper.selectList(Wrappers.<DeviceInfo>lambdaQuery()
                .orderByAsc(DeviceInfo::getDeviceId)
                .last(LIMIT_2000))
                : deviceInfoMapper.findByDeviceIdIn(deviceIds);
        Map<String, String> deviceMerchant = devices.stream()
                .collect(Collectors.toMap(DeviceInfo::getDeviceId,
                        d -> d.getMerchantId() == null ? "" : d.getMerchantId(), (a, b) -> a));
        List<Object[]> deviceRows = lineMapper.deviceBreakdownBetween(deviceIds, start, end);
        Map<String, Agg> map = new HashMap<>();
        for (Object[] r : deviceRows) {
            String did = String.valueOf(r[0]);
            if (deviceIds != null && !deviceIds.contains(did)) {
                continue;
            }
            String mid = deviceMerchant.getOrDefault(did, "");
            Agg a = map.computeIfAbsent(mid, k -> new Agg());
            a.orderCount += ((Number) r[4]).longValue();
            a.qty += ((Number) r[1]).longValue();
            a.revenue += ((Number) r[2]).longValue();
            a.cogs += ((Number) r[3]).longValue();
        }
        Set<String> merchantIds = map.keySet().stream()
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        Map<String, String> names = merchantIds.isEmpty()
                ? Map.of()
                : merchantMapper.findAllById(merchantIds).stream()
                .collect(Collectors.toMap(Merchant::getMerchantId, Merchant::getMerchantName, (a, b) -> a));
        return map.entrySet().stream()
                .map(e -> new SalesReportRowDto(
                        e.getKey(),
                        names.getOrDefault(e.getKey(), e.getKey().isBlank() ? "(未绑定)" : e.getKey()),
                        e.getValue().orderCount, e.getValue().qty, e.getValue().revenue,
                        e.getValue().cogs, e.getValue().revenue - e.getValue().cogs))
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

    private static String onlineStatusLabel(String status) {
        if (status == null || status.isBlank()) {
            return "未知";
        }
        return switch (status.toUpperCase()) {
            case "ONLINE" -> "在线";
            case "OFFLINE" -> "离线";
            default -> status;
        };
    }

    private static class Agg {
        long orderCount;
        long qty;
        long revenue;
        long cogs;
    }
}
