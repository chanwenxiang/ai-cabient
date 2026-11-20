package com.aicabinet.trade.service;

import com.aicabinet.trade.client.JiangyiGatherClient;
import com.aicabinet.trade.domain.JiangyiClassMapping;
import com.aicabinet.trade.domain.JiangyiDevice;
import com.aicabinet.trade.domain.JiangyiSkuJiangyiLink;
import com.aicabinet.trade.dto.JiangyiGatherDtos.LearnableProduct;
import com.aicabinet.trade.mapper.JiangyiClassMappingMapper;
import com.aicabinet.trade.mapper.JiangyiDeviceMapper;
import com.aicabinet.trade.mapper.JiangyiSkuJiangyiLinkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public JiangyiMappingAutoBindService(JiangyiDeviceMapper deviceMapper,
                                         JiangyiClassMappingMapper mappingMapper,
                                         JiangyiSkuJiangyiLinkMapper linkMapper,
                                         JiangyiGatherClient gatherClient,
                                         AdminAuditService auditService) {
        this.deviceMapper = deviceMapper;
        this.mappingMapper = mappingMapper;
        this.linkMapper = linkMapper;
        this.gatherClient = gatherClient;
        this.auditService = auditService;
    }

    /** 单柜结果；{@code status} 见本类 STATUS_* 常量。 */
    public record DeviceNote(String deviceId, String status, Integer classId, String reason) {}

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
     * @param textNameOverride 调用方已知的识别名（如 link 上已回填的 textName）；空则按
     *                         {@code productId} 去厂商可学习名单里取
     */
    @Transactional
    public Result autoBind(Long operatorId, String skuId, String productId, String textNameOverride) {
        String textName = normalize(textNameOverride);
        if (textName == null) {
            textName = resolveTextName(productId);
            if (textName != null) {
                // 顺手回填：识别名是 classes 的对位键，留在挂接行上后续复核/排查都要用
                backfillLinkTextName(productId, textName);
            }
        }
        if (textName == null) {
            return Result.noTextName("该商品在将邑侧还没有识别名（采集未成档），"
                    + "柜机暂时无法与识别类目对位；采集审核通过后再点一次挂接即可");
        }

        final String effectiveTextName = textName;
        List<DeviceNote> notes = new ArrayList<>();
        int bound = 0;
        int already = 0;
        int pendingManual = 0;
        for (JiangyiDevice device : deviceMapper.listBound()) {
            String deviceId = device.getDeviceId();
            List<JiangyiClassMapping> hits = mappingMapper.listByDevice(deviceId).stream()
                    // class_id < 0 是复核重算腾位用的负哨兵（V400）：失效行同样带 SKU，
                    // 不过滤就会被一次挂接重新点亮成旧编号的错映射。
                    .filter(m -> m.getClassId() != null && m.getClassId() >= 0)
                    .filter(m -> effectiveTextName.equals(normalize(m.getTextName())))
                    .toList();
            if (hits.isEmpty()) {
                // 该柜模型里没有这个识别名：通常还没同步新模型。逐柜记账会把几十台柜刷屏，
                // 只计「需要人工处理」的柜数，明细靠日志回溯。
                pendingManual++;
                log.info("jiangyi auto-bind: no such textName deviceId={} textName={}", deviceId, textName);
                continue;
            }
            if (hits.size() > 1) {
                pendingManual++;
                notes.add(new DeviceNote(deviceId, STATUS_SKIPPED, null,
                        "同一识别名对应 " + hits.size() + " 行映射（冲突），需人工合并"));
                continue;
            }
            JiangyiClassMapping row = hits.get(0);
            String currentSku = normalize(row.getSkuId());
            if (currentSku != null && !currentSku.equals(skuId)) {
                pendingManual++;
                notes.add(new DeviceNote(deviceId, STATUS_SKIPPED, row.getClassId(),
                        "该识别名已挂其它 SKU（" + currentSku + "），改收款对象须人工处理"));
                continue;
            }
            if (skuId.equals(currentSku) && "ACTIVE".equals(row.getStatus())) {
                already++;
                notes.add(new DeviceNote(deviceId, STATUS_ALREADY, row.getClassId(), "原本已生效"));
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
                    wasDisabled ? "原停用，已挂 SKU 并启用" : "已挂 SKU 并启用"));
            log.info("jiangyi auto-bind: bound deviceId={} skuId={} classId={} textName={} wasDisabled={}",
                    deviceId, skuId, row.getClassId(), textName, wasDisabled);
        }

        // 明细按「已生效优先」排，前端直接照序展示
        notes.sort(Comparator.comparing((DeviceNote n) -> STATUS_BOUND.equals(n.status()) ? 0 : 1)
                .thenComparing(DeviceNote::deviceId));
        String message = buildMessage(bound, already, pendingManual);
        auditService.appendLog(operatorId, "JIANGYI_SKU_LINK_AUTOBIND", "SKU_CATALOG", skuId,
                "productId=" + productId + ",textName=" + textName + ",bound=" + bound
                        + ",already=" + already + ",pendingManual=" + pendingManual);
        return new Result(textName, bound, already, List.copyOf(notes), pendingManual, message);
    }

    private static String buildMessage(int bound, int already, int pendingManual) {
        if (bound == 0 && already == 0) {
            return pendingManual == 0
                    ? "没有已接入的柜机需要挂接"
                    : "没有柜机自动挂上：" + pendingManual + " 台需人工处理"
                            + "（多数是该柜模型里还没有这个识别名，需先同步/复核新模型；"
                            + "冲突或已被其它 SKU 占用的见下方明细）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("已挂到 ").append(bound).append(" 台柜机");
        if (already > 0) {
            sb.append("，另有 ").append(already).append(" 台原本已生效");
        }
        if (pendingManual > 0) {
            sb.append("；").append(pendingManual).append(" 台需人工处理（明细见下）");
        }
        return sb.toString();
    }

    /**
     * 识别名来源与人工上架同口径：§4.4.2 {@code learnableProducts} 里该商品 id 的 textName。
     * 厂商读面不可用只降级成「本次不自动挂」，不能让挂接整体失败。
     */
    private String resolveTextName(String productId) {
        String pid = normalize(productId);
        if (pid == null) {
            return null;
        }
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
