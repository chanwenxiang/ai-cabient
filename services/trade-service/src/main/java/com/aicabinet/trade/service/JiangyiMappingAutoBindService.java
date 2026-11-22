package com.aicabinet.trade.service;

import com.aicabinet.trade.client.JiangyiGatherClient;
import com.aicabinet.trade.domain.JiangyiClassMapping;
import com.aicabinet.trade.domain.JiangyiDevice;
import com.aicabinet.trade.domain.JiangyiSkuJiangyiLink;
import com.aicabinet.trade.dto.JiangyiGatherDtos.JiangyiStdSku;
import com.aicabinet.trade.dto.JiangyiGatherDtos.LearnableProduct;
import com.aicabinet.trade.mapper.JiangyiClassMappingMapper;
import com.aicabinet.trade.mapper.JiangyiDeviceMapper;
import com.aicabinet.trade.mapper.JiangyiSkuJiangyiLinkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 挂接即生效：商品页把 SKU 挂到将邑商品之后，顺手把各柜机的「识别编号 → SKU」映射行补齐。
 *
 * <p><b>为什么需要它</b>：商品页只写 {@code sku_jiangyi_link}（SKU ↔ 将邑商品），但设备手上要有
 * thirdPid 才能走 thirdPid 直命中。thirdPid 并非设备自己算出——<b>固件里写
 * {@code ReplenishGoodsInfos} 的唯一调用点就是 floor-goods 响应解析</b>（见
 * {@link JiangyiFloorGoodsService} 头注释的反编译实证）；而 floor-goods 的行集是「本设备 ACTIVE
 * 且已挂 SKU 的映射行」，thirdPid 再从这条行 → SKU → 挂接反算。少了这一行，柜机拿到的补货表里
 * 没有这个编号 ⇒ 上报 thirdPid=0 ⇒ thirdPid 路径没输入，退回 classId 兜底也查不到 ⇒ 转人工。
 * 于是运营只能手工去「识别映射」页逐行挂 SKU —— 那正是本类要省掉的动作。</p>
 *
 * <p><b>判据与人工作业完全一致</b>（照抄 {@link JiangyiShelfWriter#shelveOne}，不另长一套）：
 * 按识别名 textName 在柜机映射里找<b>唯一</b>一行，只挂 SKU 与状态，不动 classId/textName，
 * 因此复核戳继续有效。以下情况一律跳过并写清原因，绝不替运营猜：</p>
 * <ul>
 *   <li>该柜模型里没有这个识别名（旧模型/未同步）；</li>
 *   <li>同一识别名对应多行（冲突，机器无法判断该挂谁）；</li>
 *   <li>该识别名已挂<b>另一个</b> SKU（已成交过的类目不许静默改收款对象）。</li>
 * </ul>
 *
 * <p><b>与「自动档不碰钱」边界的关系</b>：{@link JiangyiMappingReviewService#autoRebuild} 不碰
 * 既有行，因为那是没有操作人的系统动作。本类由运营在商品页显式点「挂接」触发，与
 * {@code shelveOne}（人工上架）同类：有操作人、有审计、有明确意图，故允许给既有行走
 * {@code setSkuId + ACTIVE}。所有改动都记 {@code JIANGYI_SKU_LINK_AUTOBIND} 审计。</p>
 */
@Service
public class JiangyiMappingAutoBindService {

    private static final Logger log = LoggerFactory.getLogger(JiangyiMappingAutoBindService.class);

    private static final String STATUS_BOUND = "BOUND";
    private static final String STATUS_ALREADY = "ALREADY";
    private static final String STATUS_SKIPPED = "SKIPPED";

    private final JiangyiDeviceMapper deviceMapper;
    private final JiangyiClassMappingMapper mappingMapper;
    private final JiangyiSkuJiangyiLinkMapper linkMapper;
    private final JiangyiGatherClient gatherClient;
    private final AdminAuditService auditService;
    private final JiangyiMappingReviewService mappingReviewService;
    private final TransactionTemplate txTemplate;

    public JiangyiMappingAutoBindService(JiangyiDeviceMapper deviceMapper,
                                         JiangyiClassMappingMapper mappingMapper,
                                         JiangyiSkuJiangyiLinkMapper linkMapper,
                                         JiangyiGatherClient gatherClient,
                                         AdminAuditService auditService,
                                         JiangyiMappingReviewService mappingReviewService,
                                         TransactionTemplate txTemplate) {
        this.deviceMapper = deviceMapper;
        this.mappingMapper = mappingMapper;
        this.linkMapper = linkMapper;
        this.gatherClient = gatherClient;
        this.auditService = auditService;
        this.mappingReviewService = mappingReviewService;
        this.txTemplate = txTemplate;
    }

    /**
     * 单柜结果；{@code status} 见本类 STATUS_* 常量。
     *
     * <p>{@code mappingVerified} 仅用于<b>展示</b>——挂上映射行 ≠ 能结算，结算侧还有 V400
     * 复核闸（{@link JiangyiMappingReviewService#isVerified}）。与 {@link JiangyiShelfWriter}
     * 同一判据：未复核柜就算这一行挂上，识别也会被整单转 DISPUTED。运营在弹窗逐柜明细
     * 看到「待复核」才知道还要去识别映射页确认一次；挂接判定与资金逻辑一概不受影响。</p>
     */
    public record DeviceNote(String deviceId, String status, Integer classId, String reason,
                             boolean mappingVerified) {}

    /**
     * @param textName 本次依据的识别名（null/空 = 该商品在将邑侧还没有识别名，一行都没动）
     * @param pendingManual 需要人工去识别映射页处理的柜机数（冲突/已挂别的 SKU/缺该识别名）
     */
    public record Result(String textName, int bound, int already, List<DeviceNote> notes,
                         int pendingManual, String message) {

        static Result noTextName(String message) {
            return new Result(null, 0, 0, List.of(), 0, message);
        }

        public boolean touched() {
            return bound > 0 || already > 0;
        }
    }

    /**
     * 按识别名给各柜机补挂映射（幂等）。
     *
     * <p>🔴 P3-4（2026-11-21 用户复核）：厂商 HTTP 查询不得在事务内进行——原实现整方法
     * {@code @Transactional}，{@code resolveTextName} 内部走 stdSku/getList / learnableProducts
     * 公网调用，厂商慢时数据库连接被一直占着。现在先在事务外取识别名（纯读 + HTTP），
     * 仅把随后的 DB 写入（回填 + 逐柜挂接 + 审计）放进 {@link TransactionTemplate} 短事务。</p>
     *
     * @param textNameOverride 调用方已知的识别名（如 link 上已回填的 textName）；空则按
     *                         {@code productId} 去厂商可学习名单里取
     */
    public Result autoBind(Long operatorId, String skuId, String productId, String textNameOverride) {
        String textName = normalize(textNameOverride);
        if (textName == null) {
            // 厂商查询在事务外：慢响应只占本线程，不占数据库连接
            textName = resolveTextName(productId);
        }
        if (textName == null) {
            // 🔴 这条路径静默返回不写日志，运营侧只能靠 message 知道「一行都没动」——
            // 所以必须把「下一步做什么」写全：能换挂同款另一条、能手工挂、也能找厂商加类目。
            return Result.noTextName("这条厂商商品在标准库里没有识别类目名（未训练进柜机模型），"
                    + "柜机认不出来。同款在厂商库若有多条，换识别名有值的那条挂；"
                    + "确实没有则需厂商把该商品加进模型重新训练（也可先到「识别映射」页按编号手工挂 SKU）");
        }

        // 短事务只包 DB 写；循环体抽成独立方法（lambda 内不能用 continue）
        final boolean shouldBackfill = normalize(textNameOverride) == null;
        final String resolvedTextName = textName;
        return txTemplate.execute(tx -> doAutoBind(operatorId, skuId, productId, resolvedTextName,
                shouldBackfill));
    }

    private Result doAutoBind(Long operatorId, String skuId, String productId, String textName,
                              boolean backfill) {
        if (backfill) {
            backfillLinkTextName(productId, textName);
        }
        final String effectiveTextName = textName;
        // 逐柜复核态缓存：同一柜在 notes 里只出现一次，但保持一次调用一处取值（与 ShelfWriter 同判据）
        java.util.Map<String, Boolean> verifiedByDevice = new java.util.HashMap<>();
        List<DeviceNote> notes = new ArrayList<>();
        List<String> missingDeviceIds = new ArrayList<>();
        int bound = 0;
        int already = 0;
        int pendingManual = 0;
        for (JiangyiDevice device : deviceMapper.listBound()) {
            String deviceId = device.getDeviceId();
            // 复核态只读、仅用于展示：查库失败按「未复核」显示，绝不当成通过（与结算侧 fail-closed 一致）
            boolean mappingVerified;
            try {
                mappingVerified = mappingReviewService.isVerified(deviceId);
            } catch (RuntimeException e) {
                log.warn("jiangyi auto-bind: mapping status unavailable deviceId={} err={}",
                        deviceId, e.getMessage());
                mappingVerified = false;
            }
            verifiedByDevice.put(deviceId, mappingVerified);
            List<JiangyiClassMapping> hits = mappingMapper.listByDevice(deviceId).stream()
                    // class_id < 0 是复核重算腾位用的负哨兵（V400）：失效行同样带 SKU，
                    // 不过滤就会被一次挂接重新点亮成旧编号的错映射。
                    .filter(m -> m.getClassId() != null && m.getClassId() >= 0)
                    .filter(m -> effectiveTextName.equals(normalize(m.getTextName())))
                    .toList();
            if (hits.isEmpty()) {
                // 该柜模型里没有这个识别名：要么还没同步新模型，要么（更常见）挂错了同款里的另一条——
                // 厂商库同款常有多条且识别名各不相同，只有一条落在模型里。逐柜刷屏没意义，
                // 收齐后汇总成一条明细，并把识别名写进去——运营看到那串拼音才对得上自己挂的是哪条。
                pendingManual++;
                missingDeviceIds.add(deviceId);
                log.info("jiangyi auto-bind: no such textName deviceId={} textName={}", deviceId, textName);
                continue;
            }
            if (hits.size() > 1) {
                pendingManual++;
                notes.add(new DeviceNote(deviceId, STATUS_SKIPPED, null,
                        "同一识别名对应 " + hits.size() + " 行映射（冲突），需人工合并",
                        verifiedByDevice.getOrDefault(deviceId, true)));
                continue;
            }
            JiangyiClassMapping row = hits.get(0);
            String currentSku = normalize(row.getSkuId());
            if (currentSku != null && !currentSku.equals(skuId)) {
                pendingManual++;
                notes.add(new DeviceNote(deviceId, STATUS_SKIPPED, row.getClassId(),
                        "该识别名已挂其它 SKU（" + currentSku + "），改收款对象须人工处理",
                        verifiedByDevice.getOrDefault(deviceId, true)));
                continue;
            }
            if (skuId.equals(currentSku) && "ACTIVE".equals(row.getStatus())) {
                already++;
                notes.add(new DeviceNote(deviceId, STATUS_ALREADY, row.getClassId(), "原本已生效",
                        verifiedByDevice.getOrDefault(deviceId, true)));
                continue;
            }
            // 到这里只剩两种：空行（复核预生成的 DISABLED 行）或同 SKU 的停用行。
            // 两者都由运营这次显式挂接触发，写 SKU + 启用；classId/textName 原样不动 ⇒ 复核戳不失效。
            boolean wasDisabled = "DISABLED".equals(row.getStatus());
            row.setSkuId(skuId);
            row.setStatus("ACTIVE");
            row.setUpdatedAt(Instant.now());
            mappingMapper.upsert(row);
            bound++;
            notes.add(new DeviceNote(deviceId, STATUS_BOUND, row.getClassId(),
                    wasDisabled ? "原停用，已挂 SKU 并启用" : "已挂 SKU 并启用",
                    verifiedByDevice.getOrDefault(deviceId, true)));
            log.info("jiangyi auto-bind: bound deviceId={} skuId={} classId={} textName={} wasDisabled={}",
                    deviceId, skuId, row.getClassId(), textName, wasDisabled);
        }

        if (!missingDeviceIds.isEmpty()) {
            notes.add(new DeviceNote(missingDeviceIds.get(0), STATUS_SKIPPED, null,
                    "识别名 " + textName + " 不在这台柜的模型里"
                            + (missingDeviceIds.size() > 1 ? "（共 " + missingDeviceIds.size() + " 台同因）" : "")
                            + "；同款在厂商库若有多条，请改挂识别名落在模型里的那一条", true));
        }

        // 明细按「已生效优先」排，前端直接照序展示
        notes.sort(Comparator.comparing((DeviceNote n) -> STATUS_BOUND.equals(n.status()) ? 0 : 1)
                .thenComparing(DeviceNote::deviceId));
        String message = buildMessage(bound, already, pendingManual, textName, missingDeviceIds.size(),
                notes);
        auditService.appendLog(operatorId, "JIANGYI_SKU_LINK_AUTOBIND", "SKU_CATALOG", skuId,
                "productId=" + productId + ",textName=" + textName + ",bound=" + bound
                        + ",already=" + already + ",pendingManual=" + pendingManual);
        return new Result(textName, bound, already, List.copyOf(notes), pendingManual, message);
    }

    /**
     * 汇总文案。
     *
     * <p>🔴 「已挂到 X 台」必须把 {@code already} 一起算进去：运营手工先挂过一行（第 29 行就是这种）
     * 再来点挂接，旧口径会输出「已挂到 0 台柜机，另有 1 台原本已生效」——读起来像没挂上，于是被
     * 反复重点。台数 = 本次新挂 + 原本已生效，并直接带上识别编号：运营要认的就是「哪台柜的哪个号」。</p>
     */
    private static String buildMessage(int bound, int already, int pendingManual,
                                       String textName, int missingDevices,
                                       List<DeviceNote> notes) {
        if (bound == 0 && already == 0) {
            if (pendingManual == 0) {
                return "没有已接入的柜机需要挂接";
            }
            if (missingDevices > 0 && missingDevices == pendingManual) {
                // 全部柜都「没这个识别名」是最容易误判成「系统坏了」的一种：必须点明是模型里没有，
                // 并给出两条真实出路（换挂同款另一条 / 让厂商加类目），否则运营只会反复重点。
                return "没有柜机能挂上：识别名 " + textName + " 不在这 " + missingDevices
                        + " 台柜的模型里。柜机只认模型训练过的类目——同款在厂商库若有多条（识别名各不同），"
                        + "请改挂识别名落在模型里的那一条；若确实没有，需厂商把该商品加进模型重新训练";
            }
            return "没有柜机自动挂上：" + pendingManual + " 台需人工处理（明细见下）";
        }
        List<Integer> classIds = notes.stream()
                .filter(n -> STATUS_BOUND.equals(n.status()) || STATUS_ALREADY.equals(n.status()))
                .map(DeviceNote::classId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        StringBuilder sb = new StringBuilder();
        sb.append("已挂到 ").append(bound + already).append(" 台柜机");
        if (!classIds.isEmpty()) {
            // 单个编号说「，编号 29」，多个说「（编号 5、26）」，都是给运营照着认的口径
            sb.append(classIds.size() == 1 ? "，编号 " : "（编号 ");
            sb.append(String.join("、", classIds.stream().map(String::valueOf).toList()));
            if (classIds.size() > 1) {
                sb.append("）");
            }
        }
        if (already > 0) {
            sb.append(bound > 0 ? "，其中 " + already + " 台原本已生效" : "，原本已生效");
        }
        if (pendingManual > 0) {
            sb.append("；").append(pendingManual).append(" 台需人工处理（明细见下）");
        }
        return sb.toString();
    }

    /**
     * 识别名来源：<b>厂商标准商品库</b>（§4.1.2 {@code stdSku/getList}）里 {@code id == productId}
     * 那条的 {@code textName}。
     *
     * <p>🔴 <b>2026-11-20 生产实锤，修掉本类首版取错数据源的缺陷</b>：首版照抄 §4.4.2
     * {@code learnableProducts}（{@code getPageData + gatherStatus=training}）。那份名单只含
     * 「走过扫码采集流程」的条目，而运营在商品页挂的多是人工建档/标准库既有商品——
     * 于是生产上每次挂接都返回「还没有识别名」，一行都挂不上，看起来像厂商数据缺失。
     * 实测真相：{@code stdSku/getList} 回包<b>本来就有 textName</b>，只是早先没解析
     * （见 {@code JiangyiStdSku#textName}）；同一接口按 {@code barCode} 精确查还能唯一命中。</p>
     *
     * <p>取数顺序：① 按挂接行条码精确查（厂商侧唯一，最可信）；② 按挂接行名称模糊查后
     * 用 id 精确命中（条码为空或与厂商不一致时的兜底）；③ 才退回可学习名单（采集刚成档、
     * 尚未进标准库的过渡态）。任一源不可用只降级成「本次不自动挂」，不能让挂接整体失败。</p>
     */
    private String resolveTextName(String productId) {
        String pid = normalize(productId);
        if (pid == null) {
            return null;
        }
        JiangyiSkuJiangyiLink link = linkMapper.byProduct(pid).orElse(null);
        String barCode = link == null ? null : normalize(link.getBarCode());
        String name = link == null ? null : normalize(link.getJiangyiName());

        String byBarCode = lookupStdSkuTextName(pid, null, barCode);
        if (byBarCode != null) {
            return byBarCode;
        }
        String byName = lookupStdSkuTextName(pid, name, null);
        if (byName != null) {
            return byName;
        }
        return fromLearnableProducts(pid);
    }

    /**
     * 标准商品库按条码/名称查，再用 {@code id} 精确命中。
     *
     * <p>⚠️ 两个参数不能都空：{@code getList} 无查询词时返回全库分页前若干条，会拿错行。
     * 命中后用 id 比对而不是只取第一条——同条码在厂商库里可能有多条（同款不同包装）。</p>
     */
    private String lookupStdSkuTextName(String pid, String name, String barCode) {
        if (normalize(name) == null && normalize(barCode) == null) {
            return null;
        }
        try {
            return gatherClient.stdSkuList(name, barCode).stream()
                    .filter(s -> pid.equals(String.valueOf(s.id())))
                    .map(JiangyiStdSku::textName)
                    .map(JiangyiMappingAutoBindService::normalize)
                    .filter(t -> t != null)
                    .findFirst()
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("jiangyi auto-bind: stdSkuList unavailable productId={} barCode={} err={}",
                    pid, barCode, e.getMessage());
            return null;
        }
    }

    /** 兜底：采集流程刚成档、还没落到标准商品库时的过渡态。 */
    private String fromLearnableProducts(String pid) {
        try {
            return gatherClient.learnableProducts().stream()
                    .filter(p -> pid.equals(String.valueOf(p.id())))
                    .map(LearnableProduct::textName)
                    .map(JiangyiMappingAutoBindService::normalize)
                    .filter(t -> t != null)
                    .findFirst()
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("jiangyi auto-bind: learnableProducts unavailable, skip auto bind productId={} err={}",
                    pid, e.getMessage());
            return null;
        }
    }

    /** 只回填识别名，不动挂接关系（挂接关系的写入仍由 SkuJiangyiLinkService 负责）。 */
    private void backfillLinkTextName(String productId, String textName) {
        linkMapper.byProduct(productId).ifPresent(link -> {
            if (textName.equals(normalize(link.getJiangyiTextName()))) {
                return;
            }
            link.setJiangyiTextName(textName);
            link.setUpdatedAt(Instant.now());
            linkMapper.updateById(link);
            log.info("jiangyi auto-bind: textName backfilled productId={} textName={}",
                    productId, textName);
        });
    }

    /** 现成的挂接行（供调用方决定用哪个识别名，避免重复查厂商）。 */
    public String linkTextName(String productId) {
        return linkMapper.byProduct(productId)
                .map(JiangyiSkuJiangyiLink::getJiangyiTextName)
                .map(JiangyiMappingAutoBindService::normalize)
                .orElse(null);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
