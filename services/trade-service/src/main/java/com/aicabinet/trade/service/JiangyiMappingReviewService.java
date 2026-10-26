package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.JiangyiClassMapping;
import com.aicabinet.trade.domain.JiangyiDevice;
import com.aicabinet.trade.mapper.JiangyiClassMappingMapper;
import com.aicabinet.trade.mapper.JiangyiDeviceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 重训后映射重算 + 强制复核（V400，台账 H-27）。
 *
 * <p><b>为什么要有这个服务</b>：将邑 classId = classes.txt 按 textName 排序后的行下标，
 * 厂商每次重训都会插入/删除类目并把后面所有商品的编号整体平移（实测同一批可乐/雪碧在
 * 65 类模型里是 22/25，在 69 类里变成 26/29）。映射一旦跟着旧编号走，柜机把「雪碧」
 * 报成「可乐」的 classId，我方按可乐价扣款——这是错钱，不是显示问题。</p>
 *
 * <p><b>口径</b>：映射的事实锚是 {@code text_name}（厂商类目名），不是 {@code class_id}。
 * 重算只做「按识别名找回原来的 SKU，再把它挪到新编号」；模型里消失的类目一律停用并把
 * 编号腾空（SKU 挂接留档），新类目建停用行等人工挂 SKU。应用完成即等于「人工确认了这一版
 * classes」——写 {@code mapping_verified_version}。之后一旦模型下发确认（classes_version
 * 变了）或收到无法归属的模型回执（撤销戳），复核即失效：识别整单 fail-closed 转 DISPUTED，
 * 在售面下发空表。</p>
 *
 * <p>重算不会给任何已有行换 SKU，也不会自动启用既有行。唯一例外是<b>自动档对 NEW 行</b>的
 * 「条码唯一命中」挂接（{@code MODEL_AUTO_MATCH}）：厂商标准商品条码与我方唯一一个在售 SKU
 * 的条码逐字相等时直接挂上并启用，其余一律建停用空行等人工——「这个类目是哪款商品」在不满足
 * 唯一性时仍由人看差异表确认。</p>
 */
@Service
public class JiangyiMappingReviewService {

    private static final Logger log = LoggerFactory.getLogger(JiangyiMappingReviewService.class);

    public static final String ACTION_KEEP = "KEEP";
    public static final String ACTION_MOVE = "MOVE";
    public static final String ACTION_NEW = "NEW";
    public static final String ACTION_ORPHAN = "ORPHAN";
    public static final String ACTION_CONFLICT = "CONFLICT";

    private final JiangyiDeviceMapper deviceMapper;
    private final JiangyiClassMappingMapper mappingMapper;
    private final JiangyiModelSyncService modelSyncService;
    private final AdminAuditService adminAuditService;
    private final DistributedLockService distributedLockService;

    public JiangyiMappingReviewService(JiangyiDeviceMapper deviceMapper,
                                       JiangyiClassMappingMapper mappingMapper,
                                       JiangyiModelSyncService modelSyncService,
                                       AdminAuditService adminAuditService,
                                       DistributedLockService distributedLockService) {
        this.deviceMapper = deviceMapper;
        this.mappingMapper = mappingMapper;
        this.modelSyncService = modelSyncService;
        this.adminAuditService = adminAuditService;
        this.distributedLockService = distributedLockService;
    }

    // ---------- 复核态 ----------

    /**
     * 设备复核态；未登记设备按「未复核」返回（fail-closed，不在这里抛 404 打断识别链路）。
     *
     * <p>已复核 ⇔ classes_version 与 mapping_verified_version 均为非空且相等。历史设备
     * （厂商直推、我方无下发记录）classes_version 为空 ⇒ 未复核，须先用当前模型做一次
     * 「按识别名重算 + 确认」，由该动作认领版本。</p>
     */
    @Transactional(readOnly = true)
    public MappingStatus status(String deviceId) {
        JiangyiDevice device = deviceId == null ? null : deviceMapper.selectById(deviceId);
        if (device == null) {
            return new MappingStatus(false, null, null, null);
        }
        String classesVersion = blankToNull(device.getClassesVersion());
        String verifiedVersion = blankToNull(device.getMappingVerifiedVersion());
        boolean verified = classesVersion != null && classesVersion.equals(verifiedVersion);
        return new MappingStatus(verified, classesVersion, verifiedVersion, device.getMappingVerifiedAt());
    }

    /** 结算/在售面闸口：未复核一律 true（调用方据此 fail-closed）。 */
    @Transactional(readOnly = true)
    public boolean isVerified(String deviceId) {
        return status(deviceId).verified();
    }

    // ---------- 差异预览 ----------

    /** 只读差异：按 classes 的 textName 找现存映射，计算保留/平移/新增/失效/冲突，不写库。 */
    @Transactional(readOnly = true)
    public RebuildPlan plan(String deviceId, String modelName, String textUrl,
                            int quantity, int classIdBase) {
        JiangyiDevice device = requireBoundDevice(deviceId);
        JiangyiClassesParser.ParsedClasses parsed =
                modelSyncService.parseClassesForReview(textUrl, quantity, classIdBase);
        return computePlan(device, modelName, parsed, mappingMapper.listByDevice(deviceId));
    }

    static RebuildPlan computePlan(JiangyiDevice device, String modelName,
                                   JiangyiClassesParser.ParsedClasses parsed,
                                   List<JiangyiClassMapping> existing) {
        Map<String, List<JiangyiClassMapping>> byTextName = new LinkedHashMap<>();
        for (JiangyiClassMapping mapping : existing) {
            String text = stripToEmpty(mapping.getTextName());
            if (!text.isEmpty()) {
                byTextName.computeIfAbsent(text, k -> new ArrayList<>()).add(mapping);
            }
        }

        List<RebuildRow> rows = new ArrayList<>();
        Set<Long> matchedIds = new HashSet<>();
        Set<String> seenTextNames = new HashSet<>();
        int kept = 0;
        int moved = 0;
        int created = 0;
        int orphaned = 0;
        int conflicts = 0;

        for (JiangyiClassesParser.ParsedClassRow parsedRow : parsed.rows()) {
            String text = stripToEmpty(parsedRow.textName());
            if (!seenTextNames.add(text)) {
                rows.add(new RebuildRow(null, parsedRow.classId(), text, ACTION_CONFLICT, null,
                        null, null, null, "classes 内识别名重复：同一 textName 出现在多行"));
                conflicts++;
                continue;
            }
            List<JiangyiClassMapping> candidates = byTextName.getOrDefault(text, List.of());
            if (candidates.isEmpty()) {
                rows.add(new RebuildRow(null, parsedRow.classId(), text, ACTION_NEW, null,
                        null, "DISABLED", "MODEL_SYNC", "模型新增类目：先建停用行，挂 SKU 后人工启用"));
                created++;
                continue;
            }
            if (candidates.size() > 1) {
                for (JiangyiClassMapping mapping : candidates) {
                    matchedIds.add(mapping.getId());
                    rows.add(new RebuildRow(mapping.getId(), parsedRow.classId(), text, ACTION_CONFLICT,
                            mapping.getClassId(), mapping.getSkuId(), mapping.getStatus(),
                            mapping.getSource(),
                            "同一识别名已有 " + candidates.size() + " 行映射，请先在映射表合并/删除多余行"));
                }
                conflicts += candidates.size();
                continue;
            }
            JiangyiClassMapping mapping = candidates.get(0);
            matchedIds.add(mapping.getId());
            if (Objects.equals(mapping.getClassId(), parsedRow.classId())) {
                rows.add(new RebuildRow(mapping.getId(), parsedRow.classId(), text, ACTION_KEEP,
                        mapping.getClassId(), mapping.getSkuId(), mapping.getStatus(),
                        mapping.getSource(), null));
                kept++;
            } else {
                rows.add(new RebuildRow(mapping.getId(), parsedRow.classId(), text, ACTION_MOVE,
                        mapping.getClassId(), mapping.getSkuId(), mapping.getStatus(),
                        mapping.getSource(), "识别编号平移（SKU 跟随识别名）"));
                moved++;
            }
        }

        for (JiangyiClassMapping mapping : existing) {
            if (matchedIds.contains(mapping.getId())) {
                continue;
            }
            String text = stripToEmpty(mapping.getTextName());
            rows.add(new RebuildRow(mapping.getId(), null, text, ACTION_ORPHAN,
                    mapping.getClassId(), mapping.getSkuId(), "DISABLED", mapping.getSource(),
                    text.isEmpty() ? "无识别名，无法与模型对行" : "模型里已无该识别名：保留挂接留档，编号腾出"));
            orphaned++;
        }

        String deviceClassesVersion = blankToNull(device.getClassesVersion());
        boolean adoptVersion = deviceClassesVersion == null;
        boolean versionMismatch = deviceClassesVersion != null
                && !deviceClassesVersion.equals(parsed.classesVersion());
        String verifiedVersion = blankToNull(device.getMappingVerifiedVersion());
        boolean verified = deviceClassesVersion != null && deviceClassesVersion.equals(verifiedVersion);
        return new RebuildPlan(device.getDeviceId(), modelName, parsed.classesVersion(),
                deviceClassesVersion, adoptVersion, versionMismatch, verified,
                parsed.rows().size(),
                new Counts(kept, moved, created, orphaned, conflicts), List.copyOf(rows));
    }

    // ---------- 应用（人工确认） ----------

    /**
     * 应用差异 = 本轮人工复核通过。写映射行 + 复核戳在<b>同一事务</b>：要么整套映射和版本戳
     * 一起生效，要么都不生效，不允许出现「编号改了但版本没认」的半套状态。
     *
     * @param expectedClassesVersion 预览时看到的 classesVersion（复核令牌）：与当前重新拉到的
     *                               内容不一致即拒绝——防止审的是 A 版、落的是刚被替换的 B 版
     */
    @Transactional
    public ApplyResult apply(String deviceId, String modelName, String textUrl,
                             int quantity, int classIdBase, String expectedClassesVersion,
                             Long operatorId) {
        String lockKey = "jiangyi:mapping-review:" + deviceId;
        if (!distributedLockService.tryLock(lockKey, 30, 0)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该设备正在被另一位运营复核，请稍后重试");
        }
        try {
            JiangyiDevice device = requireBoundDevice(deviceId);
            JiangyiClassesParser.ParsedClasses parsed =
                    modelSyncService.parseClassesForReview(textUrl, quantity, classIdBase);
            if (expectedClassesVersion == null
                    || !expectedClassesVersion.equals(parsed.classesVersion())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "classes 内容与审阅时不一致（文件可能已被重新上传），请重新拉取差异");
            }
            RebuildPlan plan = computePlan(device, modelName, parsed,
                    mappingMapper.listByDevice(deviceId));
            if (plan.versionMismatch()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "柜机当前模型版本 " + plan.deviceClassesVersion() + " 与本次审阅的 "
                                + plan.classesVersion() + " 不一致：请审柜机正在跑的那一版，或先下发/确认正确模型");
            }
            if (plan.counts().conflicts() > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "存在 " + plan.counts().conflicts() + " 行冲突（同一识别名对应多行），请先在映射表人工合并");
            }

            ApplyResult result = writePlan(deviceId, modelName, parsed, plan, Instant.now(), Map.of());
            Counts counts = result.counts();
            adminAuditService.appendLog(operatorId, "JIANGYI_MAPPING_REVIEW", "jiangyi_device", deviceId,
                    "classesVersion=" + result.classesVersion()
                            + " adoptVersion=" + result.adoptedVersion()
                            + " kept=" + counts.kept() + " moved=" + counts.moved()
                            + " created=" + counts.created() + " orphaned=" + counts.orphaned());
            return result;
        } finally {
            distributedLockService.unlock(lockKey);
        }
    }

    // ---------- 自动复核（重训回执后） ----------

    /**
     * 重训后自动复核：模型回执确认后调用，把「重训导致映射失效」这件事免掉人工。
     *
     * <p><b>为什么要有这条</b>：重训只要往类表里插了新类目，后面所有行号整体平移（H-27），
     * 旧映射全部编号错位 ⇒ 复核戳失效、柜子停售。可这些差异里除了 CONFLICT，没有一项需要
     * 人判断「该挂谁」——按 text_name 挪回去、把消失的行腾出编号、把新行建停用，都是确定动作。</p>
     *
     * <p>🔴 <b>拦截条件只剩 CONFLICT</b>（同识别名对应多行，机器无法判断该挂谁）。其余三类
     * 一律自动落位并盖戳，代价仅为「仍欠人工收尾」，由 {@link AutoResult#pendingManual()} 带回：</p>
     * <ul>
     *   <li><b>MOVE</b>（老商品、编号漂了）：按 text_name 挪到新编号，SKU 挂接不变 ⇒ 老商品
     *       立刻恢复可售，这就是「学习新模型对之前零影响」的实现方式。</li>
     *   <li><b>NEW</b>（模型新增类目）：默认建 {@code DISABLED} + {@code sku_id=null} 的空行，
     *       不挂 SKU、不启用 ⇒ 不会错扣款。**例外**：该识别名的厂商标准商品条码与我方唯一一个
     *       在售 SKU 的条码逐字相等（见 {@link JiangyiModelSyncService#uniqueBarcodeSkuMatches}）
     *       时直接挂上并启用（{@code MODEL_AUTO_MATCH}），这样「学完就能卖」不必等人工；
     *       其余（多条码/多识别名共用条码/我方多条码候选/停售 SKU）仍留空行等人工。</li>
     *   <li><b>ORPHAN</b>（类目在模型里消失，可能是改名）：把行腾到负哨兵并停用 ⇒ 腾出编号，
     *       避免厂商拿同一编号指代别的商品导致错扣款；该商品随之下架（fail-closed）。</li>
     * </ul>
     *
     * <p><b>不碰已有行</b>：不论自动还是人工，都不会给已有行换 SKU，也不会把停用的既有行重新
     * 启用——自动挂接只发生在本次新建的行上。这是「自动档不碰钱」的边界。</p>
     *
     * <p>幂等：已腾空的历史 ORPHAN 行（class_id 已是负哨兵）不算「新消失」。否则 10-23 停用
     * classId 30/31 留下的孤儿行会让之后每一次自动复核都被永久拦住。</p>
     *
     * <p>不写 {@code admin_audit_log}：该表 {@code operator_id NOT NULL + FK}（V6/V221），系统
     * 动作没有操作人，硬塞一个 id 会把「谁改的」搅浑。留痕靠本行日志 + {@code deployment.confirmed_at}
     * + {@code mapping_verified_at}。</p>
     *
     * @param expectedClassesVersion 下发时记下的 classes 内容指纹（{@code deployment.classes_version}）；
     *                               重新拉到的内容必须逐字相同，否则说明文件已被替换，不自动落位
     */
    @Transactional
    public AutoResult autoRebuild(String deviceId, String modelName, String textUrl,
                                  String expectedClassesVersion) {
        if (textUrl == null || textUrl.isBlank()) {
            return AutoResult.skipped("无 classes 地址（厂商直推的模型，我方无下发记录）");
        }
        if (expectedClassesVersion == null || expectedClassesVersion.isBlank()) {
            return AutoResult.skipped("缺下发时的 classes 指纹，无法确认文件未被替换");
        }
        String lockKey = "jiangyi:mapping-review:" + deviceId;
        if (!distributedLockService.tryLock(lockKey, 30, 0)) {
            return AutoResult.skipped("该设备有复核操作正在进行");
        }
        try {
            JiangyiDevice device = requireBoundDevice(deviceId);
            JiangyiClassesParser.ParsedClasses parsed;
            try {
                // quantity 传 0 跳过「行数==quantity」校验：deployment 不存 quantity，改用内容指纹
                // （expectedClassesVersion）把关，比行数更严——同一个 URL 内容一换就落不了。
                parsed = modelSyncService.parseClassesForReview(textUrl, 0, 0);
            } catch (RuntimeException e) {
                log.warn("jiangyi auto mapping rebuild parse failed deviceId={} textUrl={} err={}",
                        deviceId, textUrl, e.getMessage());
                return AutoResult.skipped("classes 拉取/解析失败：" + e.getMessage());
            }
            if (!expectedClassesVersion.equals(parsed.classesVersion())) {
                log.warn("jiangyi auto mapping rebuild blocked: classes drift deviceId={} expected={} actual={}",
                        deviceId, expectedClassesVersion, parsed.classesVersion());
                return AutoResult.skipped("classes 内容与下发时不一致（文件已被替换）");
            }
            List<JiangyiClassMapping> existing = mappingMapper.listByDevice(deviceId);
            RebuildPlan plan = computePlan(device, modelName, parsed, existing);
            if (plan.versionMismatch()) {
                return AutoResult.skipped("柜机当前模型版本 " + plan.deviceClassesVersion()
                        + " 与本次 classes " + plan.classesVersion() + " 不一致");
            }
            int freshOrphans = (int) plan.rows().stream()
                    .filter(r -> ACTION_ORPHAN.equals(r.action()))
                    .filter(r -> r.oldClassId() != null && r.oldClassId() >= 0)
                    .count();
            Counts counts = plan.counts();
            if (counts.conflicts() > 0) {
                log.warn("jiangyi auto mapping rebuild left to manual deviceId={} classesVersion={} "
                                + "created={} freshOrphans={} conflicts={}",
                        deviceId, parsed.classesVersion(), counts.created(), freshOrphans, counts.conflicts());
                return new AutoResult(false, "同识别名对应多行映射（冲突），机器无法判断该挂谁，需人工复核",
                        parsed.classesVersion(), counts, freshOrphans, 0);
            }
            Map<String, String> autoHang = resolveAutoHang(textUrl, plan, existing);
            ApplyResult applied = writePlan(deviceId, modelName, parsed, plan, Instant.now(), autoHang);
            log.info("jiangyi auto mapping rebuild applied deviceId={} classesVersion={} counts={} "
                            + "autoHung={} pendingManualOrphans={}",
                    deviceId, applied.classesVersion(), applied.counts(),
                    autoHang.size(), freshOrphans);
            return new AutoResult(true, null, applied.classesVersion(), applied.counts(),
                    freshOrphans, autoHang.size());
        } finally {
            distributedLockService.unlock(lockKey);
        }
    }

    /**
     * 自动挂接候选（只给 NEW 行）：条码唯一命中交给 {@link JiangyiModelSyncService#uniqueBarcodeSkuMatches}
     * （只读），这里再补一条「该 SKU 在本设备还没有任何挂接」——包括已停用/已腾号的留档行，
     * 否则同一个商品会被挂到两个 classId 上，回溯时分不清哪个才是结算口径。
     *
     * <p>厂商接口不可达只降级成「不自动挂」，不能让自动复核整体失败——省不掉人工 ≠ 复核失败。</p>
     */
    private Map<String, String> resolveAutoHang(String textUrl, RebuildPlan plan,
                                                List<JiangyiClassMapping> existing) {
        List<String> newTextNames = plan.rows().stream()
                .filter(r -> ACTION_NEW.equals(r.action()))
                .map(RebuildRow::textName)
                .toList();
        if (newTextNames.isEmpty()) {
            return Map.of();
        }
        Map<String, String> matched;
        try {
            matched = modelSyncService.uniqueBarcodeSkuMatches(textUrl, newTextNames);
        } catch (RuntimeException e) {
            log.warn("jiangyi auto-hang match failed, new rows stay manual err={}", e.getMessage());
            return Map.of();
        }
        Set<String> hungSkuIds = new HashSet<>();
        for (JiangyiClassMapping m : existing) {
            if (m.getSkuId() != null && !m.getSkuId().isBlank()) {
                hungSkuIds.add(m.getSkuId());
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : matched.entrySet()) {
            if (hungSkuIds.contains(e.getValue())) {
                log.info("jiangyi auto-hang skipped: sku already hung on this device textName={} skuId={}",
                        e.getKey(), e.getValue());
                continue;
            }
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /**
     * 差异落库（两阶段哨兵）+ 认领/确认版本 + 盖复核戳。人工复核与自动复核共用，
     * 调用方负责校验与审计（自动路没有操作人，不写审计表）。
     *
     * @param autoHang textName → SKU（仅自动档用；人工档传空表，绝不替人挂接）
     */
    private ApplyResult writePlan(String deviceId, String modelName,
                                  JiangyiClassesParser.ParsedClasses parsed, RebuildPlan plan,
                                  Instant now, Map<String, String> autoHang) {
        // 阶段一：把要挪位/失效的行先落到 -id 负哨兵，腾出 UNIQUE(device_id, class_id) 的目标位。
        // 直接把 26 改成 29 而 29 还被占着会撞唯一键；交换/连环平移只有两阶段能一次过。
        for (RebuildRow row : plan.rows()) {
            if (ACTION_MOVE.equals(row.action())) {
                mappingMapper.parkForRelocation(row.id(), parkedClassId(row.id()), now);
            } else if (ACTION_ORPHAN.equals(row.action())) {
                mappingMapper.parkAsOrphan(row.id(), parkedClassId(row.id()), now);
            }
        }
        // 阶段二：落位 + 新建行。
        for (RebuildRow row : plan.rows()) {
            if (ACTION_MOVE.equals(row.action())) {
                mappingMapper.relocateClassId(row.id(), row.classId(), now);
            } else if (ACTION_NEW.equals(row.action())) {
                String autoSkuId = autoHang.get(row.textName());
                JiangyiClassMapping fresh = new JiangyiClassMapping();
                fresh.setDeviceId(deviceId);
                fresh.setClassId(row.classId());
                fresh.setModelName(blankToNull(modelName));
                fresh.setTextName(row.textName());
                fresh.setSkuId(autoSkuId);
                fresh.setStatus(autoSkuId == null ? "DISABLED" : "ACTIVE");
                fresh.setSource(autoSkuId == null ? "MODEL_SYNC" : "MODEL_AUTO_MATCH");
                fresh.setCreatedAt(now);
                fresh.setUpdatedAt(now);
                mappingMapper.insert(fresh);
            }
        }
        if (plan.adoptVersion()) {
            int adopted = deviceMapper.adoptClassesVersionIfAbsent(
                    deviceId, parsed.classesVersion(), now);
            if (adopted == 0) {
                // 并发：别人刚好回填了版本——只接受与我方审阅版本相同，否则拒绝（事务回滚）
                JiangyiDevice latest = requireBoundDevice(deviceId);
                if (!parsed.classesVersion().equals(latest.getClassesVersion())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "柜机模型版本刚被更新为 " + latest.getClassesVersion()
                                    + "，与本次审阅版本不一致，请重新拉取差异");
                }
            }
        }
        deviceMapper.stampMappingVerified(deviceId, parsed.classesVersion(), now);
        log.info("jiangyi mapping applied deviceId={} classesVersion={} adopt={} counts={}",
                deviceId, parsed.classesVersion(), plan.adoptVersion(), plan.counts());
        return new ApplyResult(parsed.classesVersion(), plan.adoptVersion(), plan.counts(), now);
    }

    // ---------- 内部 ----------

    private JiangyiDevice requireBoundDevice(String deviceId) {
        JiangyiDevice device = deviceId == null ? null : deviceMapper.selectById(deviceId);
        if (device == null || !"BOUND".equals(device.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "将邑设备未绑定：" + deviceId);
        }
        return device;
    }

    /** 临时负编号（= -行 id，行 id 全局唯一）；负编号不参与任何解析，只是腾位哨兵。 */
    private static int parkedClassId(long rowId) {
        return Math.negateExact(Math.toIntExact(rowId));
    }

    private static String stripToEmpty(String value) {
        return value == null ? "" : value.strip();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    // ---------- 视图 ----------

    public record MappingStatus(boolean verified, String classesVersion,
                                String verifiedVersion, Instant verifiedAt) {}

    public record Counts(int kept, int moved, int created, int orphaned, int conflicts) {}

    /**
     * 一行差异。action：KEEP 保留 / MOVE 编号平移 / NEW 模型新增 / ORPHAN 模型已无此识别名 /
     * CONFLICT 需人工先处理。orphan 行的 classId=null（已腾出编号），oldClassId 是原编号。
     */
    public record RebuildRow(Long id, Integer classId, String textName, String action,
                             Integer oldClassId, String skuId, String status, String source,
                             String reason) {}

    public record RebuildPlan(String deviceId, String modelName, String classesVersion,
                              String deviceClassesVersion, boolean adoptVersion, boolean versionMismatch,
                              boolean verified, int rowCount, Counts counts, List<RebuildRow> rows) {}

    public record ApplyResult(String classesVersion, boolean adoptedVersion, Counts counts,
                              Instant verifiedAt) {}

    /**
     * 自动复核结果：{@code applied=false} 时 {@code reason} 说明为什么留给人工；其余字段仅诊断用。
     * {@code autoHung} = 本次自动挂上并启用的新增类目数（来源 {@code MODEL_AUTO_MATCH}）。
     */
    public record AutoResult(boolean applied, String reason, String classesVersion,
                             Counts counts, int freshOrphans, int autoHung) {

        /** 仍欠人工的工作量（新增类目待挂 SKU − 已自动挂 + 消失类目待确认改名）。 */
        public int pendingManual() {
            if (counts == null) {
                return 0;
            }
            return Math.max(0, counts.created() - autoHung) + freshOrphans;
        }

        static AutoResult skipped(String reason) {
            return new AutoResult(false, reason, null, null, 0, 0);
        }
    }
}
