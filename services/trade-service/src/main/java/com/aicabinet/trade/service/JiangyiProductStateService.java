package com.aicabinet.trade.service;

import com.aicabinet.trade.client.JiangyiRestockClient;
import com.aicabinet.trade.domain.JiangyiSkuJiangyiLink;
import com.aicabinet.trade.dto.JiangyiRestockDtos.ProductCreateCommand;
import com.aicabinet.trade.dto.JiangyiRestockDtos.ProductState;
import com.aicabinet.trade.mapper.JiangyiSkuJiangyiLinkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 将邑第七章商品采集状态：查询 / 建档 / 提交采集 / 批量刷新，并把快照落到挂接行。
 *
 * <p>对外键一律用 <b>barCode</b>：§7.11 的 products 支持 productId/barCode 二选一，而
 * `sku_jiangyi_link.jiangyi_product_id` 一列历史混装 thirdPid 与 productId 两个命名空间
 * （台账 §12B），拿它当对外键会把 CREATED 行解错。因此本类<b>只读不写</b>该列。</p>
 *
 * <p>§7.5 有厂商侧写副作用（首次查询已建档未完成采集的商品会建立采集任务），
 * 只允许由运营的显式动作调用；轮询一律走 {@link #refresh(List)}（§7.10）。</p>
 */
@Service
public class JiangyiProductStateService {

    private static final Logger log = LoggerFactory.getLogger(JiangyiProductStateService.class);

    /** §7.10 单次最多 100 个条码。 */
    private static final int BATCH_LIMIT = 100;

    private final JiangyiRestockClient client;
    private final JiangyiSkuJiangyiLinkMapper linkMapper;

    public JiangyiProductStateService(JiangyiRestockClient client,
                                      JiangyiSkuJiangyiLinkMapper linkMapper) {
        this.client = client;
        this.linkMapper = linkMapper;
    }

    /** §7.5 查询单个条码状态（显式动作，有写副作用）。 */
    public Optional<JiangyiSkuJiangyiLink> check(String barCode) {
        return persist(client.lookup(barCode), barCode);
    }

    /** §7.6 新建商品；同条码重复调用幂等，直接返回当前状态。 */
    public Optional<JiangyiSkuJiangyiLink> create(ProductCreateCommand command) {
        return persist(client.create(command), command.barCode());
    }

    /** §7.9 提交手机采集（返回 REVIEWING）并落快照。 */
    public Optional<JiangyiSkuJiangyiLink> submitPhoneCapture(long productId, List<String> imageUrls) {
        ProductState state = client.submitPhoneCapture(productId, imageUrls);
        return persist(state, state.barCode());
    }

    /**
     * §7.10 批量刷新状态（无副作用，轮询用这个）。
     *
     * @return 实际落库的行数（查不到挂接行的条码会跳过并记 warn）
     */
    public int refresh(List<String> barCodes) {
        if (barCodes == null || barCodes.isEmpty()) {
            return 0;
        }
        int saved = 0;
        for (int from = 0; from < barCodes.size(); from += BATCH_LIMIT) {
            List<String> chunk = barCodes.subList(from, Math.min(from + BATCH_LIMIT, barCodes.size()));
            for (ProductState state : client.batchStatus(chunk)) {
                if (persist(state, state.barCode()).isPresent()) {
                    saved++;
                }
            }
        }
        return saved;
    }

    /** 待轮询的挂接行（审核中的两态）。 */
    public List<JiangyiSkuJiangyiLink> pollable(int limit) {
        return linkMapper.listPollable(limit);
    }

    /** 按条码取快照。 */
    public Optional<JiangyiSkuJiangyiLink> find(String barCode) {
        return linkMapper.byBarCode(barCode);
    }

    /**
     * 把厂商状态写回挂接行。匹配顺序：barCode → productId（只读比对，不回写该列）。
     * 找不到挂接行时只记 warn：可能是「尚未挂接的厂商商品」，不臆造行。
     */
    private Optional<JiangyiSkuJiangyiLink> persist(ProductState state, String fallbackBarCode) {
        String barCode = firstNonBlank(state.barCode(), fallbackBarCode);
        Optional<JiangyiSkuJiangyiLink> link = barCode == null
                ? Optional.empty() : linkMapper.byBarCode(barCode);
        if (link.isEmpty() && state.productId() != null) {
            link = linkMapper.byProduct(String.valueOf(state.productId()));
        }
        if (link.isEmpty()) {
            log.warn("jiangyi product state has no link row barCode={} productId={} state={} — skipped",
                    barCode, state.productId(), state.state());
            return Optional.empty();
        }
        JiangyiSkuJiangyiLink row = link.get();
        row.setJiangyiState(state.state().name());
        row.setNextAction(state.nextAction());
        row.setRejectCause(state.rejectCause());
        row.setStateCheckedAt(Instant.now());
        if (barCode != null) {
            row.setBarCode(barCode);
        }
        row.setUpdatedAt(Instant.now());
        linkMapper.updateById(row);
        // 🔴 updateById 默认跳过 null 字段（V446 注释：reject_cause 只在 state=REJECTED 时有值）——
        // 审核通过/状态前进后厂商不再返回驳回原因，旧文本会永久残留（2026-11-10 实测：冰红茶
        // CABINET_PENDING 行仍带上一轮的「重新拍摄」）。显式清列走 mapper 的 UPDATE。
        if (state.rejectCause() == null) {
            linkMapper.clearRejectCause(row.getId());
        }
        return Optional.of(row);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b == null || b.isBlank() ? null : b;
    }

    /** 供上层批量判断（如运营列表过滤）。 */
    public List<String> barCodesOf(List<JiangyiSkuJiangyiLink> links) {
        List<String> out = new ArrayList<>();
        for (JiangyiSkuJiangyiLink link : links) {
            if (link.getBarCode() != null && !link.getBarCode().isBlank()) {
                out.add(link.getBarCode());
            }
        }
        return out;
    }
}
