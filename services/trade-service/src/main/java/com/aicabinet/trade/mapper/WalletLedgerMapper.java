package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.WalletLedger;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 统一钱包流水 Mapper。所有读方法<b>一律按 domain 过滤</b>（复合业务键 domain+owner_id），
 * 防止跨域串账。写侧走 MP {@code insert}（ledger_id 单列 AUTO 主键，标准可用）。
 */
@Mapper
public interface WalletLedgerMapper extends BaseMapper<WalletLedger> {

    /** 幂等查重：同 (domain, ownerId, refType, refId) 的流水是否已存在。 */
    default Optional<WalletLedger> findByRef(String domain, String ownerId, String refType, String refId) {
        return Optional.ofNullable(selectOne(Wrappers.<WalletLedger>lambdaQuery()
                .eq(WalletLedger::getDomain, domain)
                .eq(WalletLedger::getOwnerId, ownerId)
                .eq(WalletLedger::getRefType, refType)
                .eq(WalletLedger::getRefId, refId)
                .last("LIMIT 1")));
    }

    /**
     * 某主体流水（新在前，按 ledger_id 倒序）——补货员运营视图口径。
     *
     * <p>P3-r19-14（2026-11-12）：原先无 LIMIT，与同族 {@link #listByOwnerOrderByCreatedAtDesc}
     * 的 200 上限不一致 ⇒ 单个补货员流水几千行时一次响应全拉，慢查询 + 大 payload。
     * 运营要看全量请走分页接口，这里只给「最近 200 条」的概览。
     */
    default List<WalletLedger> listByOwnerOrderByLedgerIdDesc(String domain, String ownerId) {
        return selectList(Wrappers.<WalletLedger>lambdaQuery()
                .eq(WalletLedger::getDomain, domain)
                .eq(WalletLedger::getOwnerId, ownerId)
                .orderByDesc(WalletLedger::getLedgerId)
                .last("LIMIT 200"));
    }

    /** 某主体流水（新在前，按 created_at 倒序，限量）——商户/线长视图口径。 */
    default List<WalletLedger> listByOwnerOrderByCreatedAtDesc(String domain, String ownerId, int limit) {
        int lim = Math.min(Math.max(limit, 1), 200);
        return selectList(Wrappers.<WalletLedger>lambdaQuery()
                .eq(WalletLedger::getDomain, domain)
                .eq(WalletLedger::getOwnerId, ownerId)
                .orderByDesc(WalletLedger::getCreatedAt)
                .last("LIMIT " + lim));
    }

    /**
     * 指定域、时间段内某类流水的总额（绝对值口径）。
     *
     * <p>给「平台提现手续费收入」这类<b>域内全量汇总</b>用（domain=MERCHANT，entryType=WITHDRAW_FEE）。
     * 时间窗 [from, to) 左闭右开且必须有上界；{@code to=null} 表示累计至今。
     * 🔴 域内全量口径，禁止拿来给单个 owner 算账。
     */
    default long sumAmountByEntryTypeBetween(String domain, String entryType, Instant from, Instant to) {
        if (entryType == null) {
            return 0L;
        }
        List<WalletLedger> rows = selectList(Wrappers.<WalletLedger>lambdaQuery()
                .eq(WalletLedger::getDomain, domain)
                .eq(WalletLedger::getEntryType, entryType)
                .ge(from != null, WalletLedger::getCreatedAt, from)
                .lt(to != null, WalletLedger::getCreatedAt, to));
        long sum = 0L;
        for (WalletLedger row : rows) {
            if (row.getAmountCents() != null) {
                sum += Math.abs(row.getAmountCents());
            }
        }
        return sum;
    }

    /** 自某时刻起累计（无上界）——「今日手续费收入」滚动口径。 */
    default long sumAmountByEntryTypeSince(String domain, String entryType, Instant since) {
        return sumAmountByEntryTypeBetween(domain, entryType, since, null);
    }

    /**
     * 某 ref 下命中给定流水类型的原始流水（{@code entry_type} + {@code amount_cents}），供调用方
     * 按<b>各类型自己的符号约定</b>算冻结占用额。
     *
     * <p>MA-262（提现二次解冻）：不能只返回一个合计——{@code amount_cents} 是**余额**方向
     * （负=出账），而冻结方向各不相同：{@code WITHDRAW_FREEZE} 负记但冻结 +，
     * {@code WITHDRAW_RELEASE} 正记且冻结 -，{@code WITHDRAW_PAID/FEE} 负记且冻结 -。
     * 真库实证（ref=1：FREEZE -1000 / PAID -994 / FEE -6，frozen_after 最终 0）：按
     * 「-Σamount」会算出仍占用 2000——恰好是「多释放吃别人冻结」的危险方向。
     *
     * <p>返回空列表 = 该 ref 没有任何相关流水（调用方据此判「不可判定」，别当成净额 0）。
     */
    default List<WalletLedger> listByRefAndEntryTypes(String domain, String ownerId, String refType,
                                                     String refId, Collection<String> entryTypes) {
        if (entryTypes == null || entryTypes.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<WalletLedger>lambdaQuery()
                .eq(WalletLedger::getDomain, domain)
                .eq(WalletLedger::getOwnerId, ownerId)
                .eq(WalletLedger::getRefType, refType)
                .eq(WalletLedger::getRefId, refId)
                .in(WalletLedger::getEntryType, entryTypes));
    }
}
