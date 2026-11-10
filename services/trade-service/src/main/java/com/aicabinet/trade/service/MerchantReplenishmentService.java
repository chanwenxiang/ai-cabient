package com.aicabinet.trade.service;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.domain.*;
import com.aicabinet.trade.dto.OrderVideoPlaylistDto;
import com.aicabinet.trade.mapper.*;
import com.aicabinet.trade.support.ApiMessages;
import com.aicabinet.trade.support.DisplayIds;
import com.aicabinet.trade.support.MerchantPortalGuard;
import com.aicabinet.trade.support.SkuDisplayNames;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class MerchantReplenishmentService {
    private static final String MERCHANT_REPLENISHMENT_REQUEST = "merchant:replenishment:request";
    private static final String MERCHANT_REPLENISHMENT_VIEW = "merchant:replenishment:view";
    private static final String REPLENISHMENT_TASK = "REPLENISHMENT_TASK";
    private static final String DEVICEID = "deviceId=";
    // AU-090 保守默认上限（取值口径待 DC-18 拍板；调整只动这两处，判定见自发起的行级/聚合）
    static final int MAX_REQUESTED_QTY_PER_LINE = 1000;
    static final int MAX_REQUESTED_QTY_PER_SKU = 5000;
    private static final String LITERAL = "补货任务不存在";


    private final PermissionService permissionService;
    private final MerchantFeaturePackService merchantFeaturePackService;
    private final MerchantPortalGuard merchantPortalGuard;
    private final ReplenishmentService replenishmentService;
    private final OpsService opsService;
    private final WarehouseService warehouseService;
    private final AdminAuditService auditService;
    private final DeviceInfoMapper deviceRepository;
    private final SkuCatalogMapper skuCatalogRepository;
    private final ReplenishmentRouteMapper routeRepository;
    private final ReplenishmentTaskMapper taskRepository;
    private final FileAttachmentService fileAttachmentService;
    private final DistributedLockService distributedLockService;
    private final ShoppingSessionMapper shoppingSessionRepository;
    private final SystemConfigService systemConfigService;
    private final MerchantScopeService merchantScopeService;
    private final JiangyiOrderVideoService jiangyiOrderVideoService;

    public MerchantReplenishmentService(PermissionService permissionService,
                                        MerchantFeaturePackService merchantFeaturePackService,
                                        MerchantPortalGuard merchantPortalGuard,
                                        ReplenishmentService replenishmentService,
                                        OpsService opsService,
                                        WarehouseService warehouseService,
                                        AdminAuditService auditService,
                                        DeviceInfoMapper deviceRepository,
                                        SkuCatalogMapper skuCatalogRepository,
                                        ReplenishmentRouteMapper routeRepository,
                                        ReplenishmentTaskMapper taskRepository,
                                        FileAttachmentService fileAttachmentService,
                                        DistributedLockService distributedLockService,
                                        ShoppingSessionMapper shoppingSessionRepository,
                                        SystemConfigService systemConfigService,
                                        MerchantScopeService merchantScopeService,
                                        JiangyiOrderVideoService jiangyiOrderVideoService) {
        this.permissionService = permissionService;
        this.merchantFeaturePackService = merchantFeaturePackService;
        this.merchantPortalGuard = merchantPortalGuard;
        this.replenishmentService = replenishmentService;
        this.opsService = opsService;
        this.warehouseService = warehouseService;
        this.auditService = auditService;
        this.deviceRepository = deviceRepository;
        this.skuCatalogRepository = skuCatalogRepository;
        this.routeRepository = routeRepository;
        this.taskRepository = taskRepository;
        this.fileAttachmentService = fileAttachmentService;
        this.distributedLockService = distributedLockService;
        this.shoppingSessionRepository = shoppingSessionRepository;
        this.systemConfigService = systemConfigService;
        this.merchantScopeService = merchantScopeService;
        this.jiangyiOrderVideoService = jiangyiOrderVideoService;
    }

    @Transactional(readOnly = true)
    public List<ReplenishmentSuggestDto> listSuggestions(Long userId, String deviceId) {
        permissionService.requirePermission(userId, MERCHANT_REPLENISHMENT_VIEW);
        merchantPortalGuard.requireAccess(userId);
        if (deviceId == null || deviceId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备 ID 不能为空");
        }
        merchantFeaturePackService.requireDevicePack(userId, deviceId.trim(), MerchantFeaturePacks.FIELD);
        return replenishmentService.suggestForDevice(deviceId.trim());
    }

    /**
     * A 方案「手动生成备货单」预览（2026-10-29）：不依赖阈值、可提前生成——逐柜按
     * 「补满」口径（{@code WarehouseService.prepRowsForDevice}：上限−实际−在途，
     * 货道无缺口时回落低库存/ROP 建议）给出事前规划视图，与首页备货清单（进行中任务
     * 出库行的事后反推，{@code replenishmentPrepList}）互补。
     *
     * <p>范围=本人 FIELD 包设备（与备货清单同一条范围判据）。无商品的柜不返回。
     * 确认生成由前端逐柜调 {@link #selfInitiateTask}（幂等键/出库/明细复用既有链路，
     * 不加备货单实体——A 方案轻量口径，B 方案草稿实体未立项）。</p>
     *
     * <p>两页分工（2026-10-30 用户拍板）：备货单页只列有缺口的行（默认），
     * 手动生成页要柜内全量在架商品（{@code includeZero=true}，建议量 0 也保留）。</p>
     */
    @Transactional(readOnly = true)
    public List<MerchantPrepPreviewGroupDto> prepPreview(Long userId) {
        return prepPreview(userId, false);
    }

    /** @param includeZero true=柜内全部在架商品（建议量 0 也保留）；false=只列建议量 &gt; 0 的行 */
    @Transactional(readOnly = true)
    public List<MerchantPrepPreviewGroupDto> prepPreview(Long userId, boolean includeZero) {
        permissionService.requirePermission(userId, MERCHANT_REPLENISHMENT_VIEW);
        merchantPortalGuard.requireAccess(userId);
        Set<String> allowed = merchantFeaturePackService.allowedDeviceIdsForPack(
                userId, MerchantFeaturePacks.FIELD);
        if (allowed == null || allowed.isEmpty()) {
            return List.of();
        }
        List<MerchantPrepPreviewGroupDto> out = new ArrayList<>();
        // R2-B1 残留：前端「改量重提」的拦截判据要「本柜最近一张任务还在不在进行中」。
        // 一次批量查（设备范围 + 每柜最新都下推 SQL），不逐柜查、也不取上限窗口再内存过滤。
        Map<String, String> latestTaskStatus = taskRepository.findLatestStatusByDeviceIds(allowed);
        for (String deviceId : allowed) {
            // 单柜失败（如台账异常）不拖垮整张预览：跳过该柜，交由确认环节的逐柜提交给出明确报错
            List<ReplenishmentSuggestDto> suggestions;
            try {
                suggestions = warehouseService.prepRowsForDevice(deviceId);
            } catch (ResponseStatusException ex) {
                continue;
            }
            if (!includeZero) {
                suggestions = suggestions.stream()
                        .filter(s -> s.suggestQty() > 0)
                        .toList();
            }
            if (suggestions.isEmpty()) {
                continue;
            }
            Map<String, SkuCatalog> catalogBySku = skuCatalogRepository.selectList(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers
                            .<SkuCatalog>lambdaQuery()
                            .in(SkuCatalog::getSkuId,
                                    suggestions.stream().map(ReplenishmentSuggestDto::skuId).toList()))
                    .stream()
                    .collect(Collectors.toMap(SkuCatalog::getSkuId, s -> s, (a, b) -> a));
            List<MerchantPrepPreviewItemDto> items = suggestions.stream()
                    .map(s -> {
                        SkuCatalog catalog = catalogBySku.get(s.skuId());
                        return new MerchantPrepPreviewItemDto(
                                s.skuId(),
                                catalog == null ? s.skuId() : catalog.getSkuName(),
                                catalog == null ? "支" : catalog.getUnit(),
                                catalog == null ? 1 : Math.max(1, catalog.getPackQty()),
                                s.currentQty(),
                                s.capacity(),
                                s.inTransitQty(),
                                s.suggestQty());
                    })
                    .sorted(Comparator.comparingInt(MerchantPrepPreviewItemDto::suggestQty).reversed()
                            .thenComparing(MerchantPrepPreviewItemDto::skuId))
                    .toList();
            DeviceInfo device = deviceRepository.findById(deviceId).orElse(null);
            out.add(new MerchantPrepPreviewGroupDto(
                    deviceId,
                    device == null ? deviceId : device.getDeviceName(),
                    latestTaskStatus.get(deviceId),
                    items));
        }
        out.sort(Comparator.comparing(MerchantPrepPreviewGroupDto::deviceId));
        return out;
    }

    /** 补货员今日运营执行情况：分配给本人的任务完成统计（对标友智慧「运营执行情况」）。 */
    @Transactional(readOnly = true)
    public MerchantReplenishmentEfficiencyDto myEfficiency(Long userId) {
        permissionService.requirePermission(userId, MERCHANT_REPLENISHMENT_VIEW);
        merchantPortalGuard.requireAccess(userId);
        Instant since = LocalDate.now(ZoneId.of("Asia/Shanghai"))
                .atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        List<ReplenishmentTask> tasks = taskRepository.findByAssigneeUserIdAndCreatedAtSince(userId, since);
        int completed = 0;
        int inProgress = 0;
        int pending = 0;
        for (ReplenishmentTask task : tasks) {
            if ("COMPLETED".equals(task.getStatus())) {
                completed++;
            } else if ("IN_PROGRESS".equals(task.getStatus())) {
                inProgress++;
            } else if ("PENDING".equals(task.getStatus())) {
                pending++;
            }
        }
        int assigned = tasks.size();
        double rate = assigned == 0 ? 0 : Math.round(completed * 10000.0 / assigned) / 100.0;
        return new MerchantReplenishmentEfficiencyDto(assigned, completed, inProgress, pending, rate);
    }

    @Transactional
    public ReplenishmentTaskDto checkInTask(Long userId, Long taskId, ReplenishmentCheckInRequest body) {
        ReplenishmentTask task = requireScopedTask(userId, taskId);
        ReplenishmentTaskDto result = replenishmentService.checkInTask(userId, taskId, body);
        auditService.appendLog(userId, "MERCHANT_REPLENISHMENT_CHECK_IN", REPLENISHMENT_TASK,
                String.valueOf(taskId), DEVICEID + task.getDeviceId());
        return result;
    }

    @Transactional
    public List<ReplenishmentTaskLineDto> getTaskLines(Long userId, Long taskId) {
        permissionService.requirePermission(userId, MERCHANT_REPLENISHMENT_VIEW);
        merchantPortalGuard.requireAccess(userId);
        ReplenishmentTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, LITERAL));
        merchantFeaturePackService.requireDevicePack(userId, task.getDeviceId(), MerchantFeaturePacks.FIELD);
        return replenishmentService.listTaskLines(taskId);
    }

    /**
     * D28-A5（CB-075）：录入上限的只读视图——开门柜「上限/在架/他单已领未上柜/可补量」四项一次下发，
     * 与提交校验同一个出处（{@code ReplenishmentService#doorCabinetHeadroomBySku}）。
     * 权限门与 {@link #getTaskLines} 同一条：查看权 + 商户准入 + FIELD 包（明细能看就能看上限）。
     */
    public List<ReplenishmentHeadroomDto> taskHeadroom(Long userId, Long taskId) {
        permissionService.requirePermission(userId, MERCHANT_REPLENISHMENT_VIEW);
        merchantPortalGuard.requireAccess(userId);
        ReplenishmentTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, LITERAL));
        merchantFeaturePackService.requireDevicePack(userId, task.getDeviceId(), MerchantFeaturePacks.FIELD);
        return replenishmentService.taskHeadroom(task);
    }

    @Transactional
    public List<ReplenishmentTaskLineDto> confirmTaskLines(Long userId, Long taskId,
                                                           SubmitReplenishmentLinesRequest body) {
        ReplenishmentTask task = requireScopedTask(userId, taskId);
        List<ReplenishmentTaskLineDto> result = replenishmentService.submitTaskLines(userId, taskId, body);
        auditService.appendLog(userId, "MERCHANT_REPLENISHMENT_CONFIRM_LINES", REPLENISHMENT_TASK,
                String.valueOf(taskId), DEVICEID + task.getDeviceId() + ",lines=" + result.size());
        return result;
    }

    @Transactional
    public ReplenishmentTaskDto completeTask(Long userId, Long taskId) {
        return completeTask(userId, taskId, null);
    }

    /** CB-050 收尾：可选定位写入完成审计（证明到场，与盘点凭证定位同语义）。 */
    @Transactional
    public ReplenishmentTaskDto completeTask(Long userId, Long taskId,
                                             com.aicabinet.common.dto.MerchantReplenishmentCompleteRequest location) {
        ReplenishmentTask task = requireScopedTask(userId, taskId);
        assertMerchantFieldCompletionGates(taskId);
        ReplenishmentTaskDto result = replenishmentService.completeTask(userId, taskId);
        String locationDetail =
                location != null && location.latitude() != null && location.longitude() != null
                        ? String.format("；定位 %.6f,%.6f", location.latitude(), location.longitude())
                        : "";
        auditService.appendLog(userId, "MERCHANT_REPLENISHMENT_COMPLETE", REPLENISHMENT_TASK,
                String.valueOf(taskId), DEVICEID + task.getDeviceId() + locationDetail);
        return result;
    }

    /**
     * 商户/补货员取消任务：判据完全复用运营侧
     * {@link ReplenishmentService#cancelEmptyTask}（无已上架明细才可取消，已领未上柜的货回仓）。
     * 作用域与 {@code completeTask} 同款（FIELD 包设备范围），不额外加「必须本人负责」的门。
     */
    @Transactional
    public ReplenishmentTaskDto cancelEmptyTask(Long userId, Long taskId) {
        ReplenishmentTask task = requireScopedTask(userId, taskId);
        ReplenishmentTaskDto result = replenishmentService.cancelEmptyTask(userId, taskId);
        auditService.appendLog(userId, "MERCHANT_REPLENISHMENT_CANCEL", REPLENISHMENT_TASK,
                String.valueOf(taskId), DEVICEID + task.getDeviceId());
        return result;
    }

    /**
     * 商户现场完成：补货开门 / 现场凭证是否必填由系统参数控制（默认均 true）。
     * 运营后台 complete 不走本入口。
     */
    private void assertMerchantFieldCompletionGates(Long taskId) {
        boolean requireDoor = systemConfigService.getBoolean(
                SystemConfigService.REPLENISHMENT_COMPLETE_REQUIRE_DOOR, true);
        if (requireDoor && !shoppingSessionRepository.existsByReplenishmentTaskId(taskId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    ApiMessages.REPLENISHMENT_COMPLETE_DOOR_REQUIRED);
        }
        boolean requireEvidence = systemConfigService.getBoolean(
                SystemConfigService.REPLENISHMENT_COMPLETE_REQUIRE_EVIDENCE, true);
        if (requireEvidence && fileAttachmentService.countReplenishmentEvidence(taskId) < 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    ApiMessages.REPLENISHMENT_COMPLETE_EVIDENCE_REQUIRED);
        }
    }

    /**
     * 扫码/手输柜机归属校验：须在当前账号 FIELD 包设备范围内。
     */
    @Transactional(readOnly = true)
    public void assertDeviceInFieldScope(Long userId, String deviceId) {
        permissionService.requirePermission(userId, MERCHANT_REPLENISHMENT_VIEW);
        merchantPortalGuard.requireAccess(userId);
        if (deviceId == null || deviceId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备 ID 不能为空");
        }
        String id = deviceId.trim();
        if (deviceRepository.findById(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.DEVICE_NOT_FOUND);
        }
        try {
            merchantFeaturePackService.requireDevicePack(userId, id, MerchantFeaturePacks.FIELD);
        } catch (ResponseStatusException ex) {
            int code = ex.getStatusCode().value();
            if (code == HttpStatus.FORBIDDEN.value() || code == HttpStatus.NOT_FOUND.value()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        ApiMessages.REPLENISHMENT_DEVICE_OUT_OF_SCOPE);
            }
            throw ex;
        }
    }

    /**
     * 商户端同步开门状态：以服务端补货会话为准（本地缓存仅作乐观展示）。
     */
    @Transactional(readOnly = true)
    public MerchantReplenishmentDoorSessionDto resolveDoorSession(Long userId, Long taskId) {
        permissionService.requirePermission(userId, MERCHANT_REPLENISHMENT_VIEW);
        merchantPortalGuard.requireAccess(userId);
        ReplenishmentTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, LITERAL));
        merchantFeaturePackService.requireDevicePack(userId, task.getDeviceId(), MerchantFeaturePacks.FIELD);
        return shoppingSessionRepository.findLatestByReplenishmentTaskId(taskId)
                .map(session -> new MerchantReplenishmentDoorSessionDto(
                        true,
                        session.getSessionId(),
                        session.getState() != null ? session.getState().name() : null))
                .orElseGet(() -> new MerchantReplenishmentDoorSessionDto(false, null, null));
    }

    /**
     * 商户/补货员现场开门：须已签到，绑定补货任务，不走消费者结算。
     */
    @Transactional
    public SessionDto openDoorForTask(Long userId, Long taskId) {
        ReplenishmentTask task = requireScopedTask(userId, taskId);
        SessionDto session = opsService.openDoorForRestockAsUser(userId, task.getDeviceId(), taskId);
        auditService.appendLog(userId, "MERCHANT_REPLENISHMENT_OPEN_DOOR", REPLENISHMENT_TASK,
                String.valueOf(taskId),
                DEVICEID + task.getDeviceId() + ",sessionId=" + session.sessionId());
        return session;
    }

    /** 签到/开门/确认上架/完成：与前端一致，需补货操作权（request） */
    private ReplenishmentTask requireScopedTask(Long userId, Long taskId) {
        permissionService.requirePermission(userId, MERCHANT_REPLENISHMENT_REQUEST);
        merchantPortalGuard.requireAccess(userId);
        ReplenishmentTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, LITERAL));
        merchantFeaturePackService.requireDevicePack(userId, task.getDeviceId(), MerchantFeaturePacks.FIELD);
        return task;
    }

    /**
     * 补货员自主发起补货（无审批）：判据 = 补货发起权限 + 柜机在本人数据范围内 + **柜机所属商户开通 FIELD 包**
     * + 柜机已归仓且该仓存在（CB-068 A 档，2026-10-27；不再要求「我是该分仓负责人」）。
     * 通过后直接从该分仓扣账生成出库 + 补货任务，直达补货员任务列表
     * （SATELLITE_WAREHOUSE.md 口径：日常补柜不申请要货）。
     */
    @Transactional
    public ReplenishmentTaskDto selfInitiateTask(Long userId, MerchantSelfReplenishRequest body) {
        permissionService.requirePermission(userId, MERCHANT_REPLENISHMENT_REQUEST);
        merchantPortalGuard.requireAccess(userId);
        if (body == null || body.deviceId() == null || body.deviceId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备 ID 不能为空");
        }
        if (body.lines() == null || body.lines().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请至少选择一种商品");
        }
        String deviceId = body.deviceId().trim();
        return runWithDeviceLock(deviceId, () -> doSelfInitiateTask(userId, body, deviceId));
    }

    private ReplenishmentTaskDto doSelfInitiateTask(Long userId, MerchantSelfReplenishRequest body, String deviceId) {
        DeviceInfo device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.DEVICE_NOT_FOUND));
        // CB-070：四类拒绝各说各话，别再让商户「猜是哪种成因」
        if (!merchantScopeService.canAccessDevice(userId, deviceId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "柜机 " + deviceId + " 不在你的数据范围内，请联系管理员为该账号分配商户或货柜");
        }
        // D28-A9（2026-10-29 用户拍板「取更严」）：发起腿补上 FIELD 包判据，与其余入口同一条链。
        // 改前本方法是全链路**唯一**只判裸数据范围的入口（建议/明细/上限/签到/开门/预览都走
        // requireDevicePack(FIELD)）⇒「没开包的商户名下柜机能扣分仓库存生成出库，却看不到补货建议」：
        // 会动库存的写腿比只读的读腿还松。
        merchantFeaturePackService.requireDevicePack(userId, deviceId, MerchantFeaturePacks.FIELD);
        String homeWarehouseId = device.getHomeWarehouseId();
        if (homeWarehouseId == null || homeWarehouseId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "柜机 " + deviceId + " 未绑定所属分仓，请先在「柜机管理」配置所属仓库后再发起补货");
        }
        // CB-068 A 档（用户 2026-10-27 拍板）：自主发起的仓级判据从「我是该仓 manager_user_id」放宽为
        // 「柜机归该仓 + 柜机在本人数据范围内」——行业口径是「补货角色 × 名下机器」，单人负责人是
        // 主责/默认派单人，不是权限闸。资损面一律未放宽：设备行锁、clientRequestNo 幂等、
        // 单行/单 SKU 数量上限、出库按仓内台账实扣，照旧。
        warehouseService.requireWarehouseExists(homeWarehouseId.trim());

        // 接口层审计（2026-10-20）H2 + V401 收口（2026-10-23）：
        // 正式幂等契约——客户端提交意图生成 clientRequestNo，超时重试复用同键，
        // 服务端按键幂等返回原任务（V401 唯一索引兜并发）。
        // 90s 短窗去重（notes 兜底）仅对**未带键**的旧客户端生效：
        // 带键请求若仍走短窗，90s 内的第二次合法补货会被误判为重试（窗口判据无法区分意图）。
        String clientRequestNo =
                body.clientRequestNo() == null ? "" : body.clientRequestNo().trim();
        if (clientRequestNo.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "clientRequestNo 过长（最多 64 字符）");
        }
        if (!clientRequestNo.isEmpty()) {
            Optional<ReplenishmentTask> replay = taskRepository.findByClientRequestNo(clientRequestNo);
            if (replay.isPresent()) {
                ReplenishmentTask original = replay.get();
                if (!deviceId.equals(original.getDeviceId())) {
                    // 键被其他设备占用：拒绝而非返回他人任务（既防误出库也防越权读）
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "clientRequestNo 已被其他设备的提交占用");
                }
                // R2-B1：回放只看 deviceId ⇒ 任务被**取消**后用同一键重提，会被回放成
                // 「已生成（任务 xxx）」而实际什么都没建（前端整页假成功、商户以为已备货）。
                // 取消态必须拒，让前端换新键真开一次。
                // 注意 COMPLETED 仍按 V401 幂等契约回放原任务（见 MerchantSelfReplenishTest
                // .selfInitiate_replaysSameClientRequestNo —— 超时重试要能拿回真实存在的任务）。
                if ("CANCELLED".equals(original.getStatus())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "上次提交的补货任务已取消，请重新提交生成新任务");
                }
                return replenishmentService.getTaskDto(original.getTaskId());
            }
        } else {
            // 旧客户端兼容：同设备+同用户+self-initiated notes 在窗口期内已有 PENDING 任务
            // ⇒ 视为超时重试，幂等返回原任务。runWithDeviceLock 已按设备串行化，查询无并发竞态。
            String selfNotes = "self-initiated by user " + userId;
            List<ReplenishmentTask> recentSelf = taskRepository.findByDeviceIdAndNotesAndCreatedAtSince(
                    deviceId, selfNotes, Instant.now().minusSeconds(90));
            ReplenishmentTask duplicate = recentSelf.stream()
                    .filter(t -> "PENDING".equals(t.getStatus()))
                    .findFirst()
                    .orElse(null);
            if (duplicate != null) {
                return replenishmentService.getTaskDto(duplicate.getTaskId());
            }
        }

        Map<String, Long> totals = new LinkedHashMap<>();
        for (MerchantSelfReplenishLine line : body.lines()) {
            if (line == null || line.skuId() == null || line.skuId().isBlank()) {
                continue;
            }
            int qty = line.qty() != null ? line.qty() : 0;
            if (qty <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "补货数量必须大于 0");
            }
            // AU-090：单行数量上界（此前仅判 >0，无上限）
            if (qty > MAX_REQUESTED_QTY_PER_LINE) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "单品要货数量超过上限（最多 " + MAX_REQUESTED_QTY_PER_LINE + "）");
            }
            skuCatalogRepository.findById(line.skuId().trim())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.SKU_NOT_FOUND));
            // AU-090：同 SKU 聚合用 long 累加并夹上限——Integer::sum 溢出为负量会直贯出库行/seed 行
            String skuId = line.skuId().trim();
            long sum = totals.getOrDefault(skuId, 0L) + qty;
            if (sum > MAX_REQUESTED_QTY_PER_SKU) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "同一商品补货总量超过上限（最多 " + MAX_REQUESTED_QTY_PER_SKU + "）");
            }
            totals.put(skuId, sum);
        }
        if (totals.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请至少选择一种有效商品");
        }
        Map<String, Integer> skuQty = new LinkedHashMap<>();
        totals.forEach((skuId, total) -> skuQty.put(skuId, total.intValue()));

        LocalDate planned = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        ReplenishmentRoute route = new ReplenishmentRoute();
        route.setRouteName(DisplayIds.selfReplenRouteName(planned, device.getDeviceName()));
        route.setPlannedDate(planned);
        route.setStatus("PLANNED");
        route = routeRepository.save(route);

        ReplenishmentTask task = new ReplenishmentTask();
        task.setRouteId(route.getRouteId());
        task.setDeviceId(deviceId);
        task.setStatus("PENDING");
        task.setNotes("self-initiated by user " + userId);
        // V401：键为空时不写入（NULL 不受唯一索引约束，旧客户端行为不变）
        task.setClientRequestNo(clientRequestNo.isEmpty() ? null : clientRequestNo);
        task = taskRepository.save(task);

        // 出库与任务解耦：分仓无可用库存时仍生成补货任务（RESTORM 行兜底），避免同事务 rollback-only
        Long outboundId = warehouseService.tryCreateOutboundFromLines(
                route.getRouteId(), deviceId, userId, skuQty, null, "merchant self replenish");
        if (outboundId != null) {
            task.setOutboundId(outboundId);
            taskRepository.save(task);
            // 草稿出库行即可生成现场补货明细，无需等发运
            replenishmentService.generateLinesFromOutbound(outboundId);
        } else {
            replenishmentService.seedDraftRestockLines(task.getTaskId(), deviceId, skuQty);
        }

        auditService.appendLog(userId, "MERCHANT_SELF_REPLENISH", REPLENISHMENT_TASK,
                String.valueOf(task.getTaskId()),
                "device=" + deviceId + (outboundId != null ? ",outbound=" + outboundId : ",outbound=none"));
        return replenishmentService.getTaskDto(task.getTaskId());
    }

    static String replenishmentDeviceLockKey(String deviceId) {
        return "merchant:replen:device:" + deviceId;
    }

    private <T> T runWithDeviceLock(String deviceId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(replenishmentDeviceLockKey(deviceId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该设备补货处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(replenishmentDeviceLockKey(deviceId));
        }
    }

    private static String displayUserName(UserInfo user) {
        if (user == null) return null;
        if (user.getName() != null && !user.getName().isBlank()) return user.getName().trim();
        if (user.getPhoneNumber() != null && !user.getPhoneNumber().isBlank()) return user.getPhoneNumber().trim();
        return user.getUserId() != null ? String.valueOf(user.getUserId()) : null;
    }

    @Transactional
    public FileAttachmentDto uploadTaskEvidence(Long userId, Long taskId, org.springframework.web.multipart.MultipartFile file) {
        permissionService.requirePermission(userId, MERCHANT_REPLENISHMENT_REQUEST);
        merchantPortalGuard.requireAccess(userId);
        ReplenishmentTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, LITERAL));
        merchantFeaturePackService.requireDevicePack(userId, task.getDeviceId(), MerchantFeaturePacks.FIELD);
        return fileAttachmentService.uploadReplenishmentEvidence(userId, taskId, file);
    }

    @Transactional(readOnly = true)
    public List<FileAttachmentDto> listTaskEvidence(Long userId, Long taskId) {
        permissionService.requirePermission(userId, MERCHANT_REPLENISHMENT_VIEW);
        merchantPortalGuard.requireAccess(userId);
        ReplenishmentTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, LITERAL));
        merchantFeaturePackService.requireDevicePack(userId, task.getDeviceId(), MerchantFeaturePacks.FIELD);
        return fileAttachmentService.listReplenishmentEvidence(taskId);
    }

    /**
     * 商户自查本柜补货作业录像（CB-078）：只读、按任务、逐片短时效签名。
     *
     * <p>闸与 {@link #listTaskEvidence} 同一套。权限点<b>刻意复用</b>补货页既有的
     * {@code merchant:replenishment:view} 而<b>不复用</b>运营侧 {@code ops:device:video}：
     * 商户与运营是两个授权主体，给商户开录像不等于给商户开运营后台权限。</p>
     *
     * <p>🔴 不出对象键：清单里的 url 是服务端逐片签发的预签名地址（DC-11 受控读路径，同 CB-029/030），
     * 设备上报的原始地址一律不外发。无台账行回 {@code source=NONE}、空清单，不是 404。</p>
     */
    @Transactional(readOnly = true)
    public OrderVideoPlaylistDto listTaskVideos(Long userId, Long taskId) {
        permissionService.requirePermission(userId, MERCHANT_REPLENISHMENT_VIEW);
        merchantPortalGuard.requireAccess(userId);
        ReplenishmentTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, LITERAL));
        merchantFeaturePackService.requireDevicePack(userId, task.getDeviceId(), MerchantFeaturePacks.FIELD);
        return JiangyiOrderVideoService.playlist(jiangyiOrderVideoService.playByTask(taskId));
    }

    @Transactional(readOnly = true)
    public void streamTaskEvidence(Long userId, Long taskId, Long fileId, jakarta.servlet.http.HttpServletResponse response)
            throws java.io.IOException {
        permissionService.requirePermission(userId, MERCHANT_REPLENISHMENT_VIEW);
        merchantPortalGuard.requireAccess(userId);
        ReplenishmentTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, LITERAL));
        merchantFeaturePackService.requireDevicePack(userId, task.getDeviceId(), MerchantFeaturePacks.FIELD);
        FileAttachment row = fileAttachmentService.requireReplenishmentEvidence(taskId, fileId);
        fileAttachmentService.stream(row, response);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value.trim())) {
            return null;
        }
        return value.trim();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
