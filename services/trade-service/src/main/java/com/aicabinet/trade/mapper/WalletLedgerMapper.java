package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.WalletLedger;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;
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

    /** 某主体流水（新在前，按 ledger_id 倒序）——补货员运营视图口径。 */
    default List<WalletLedger> listByOwnerOrderByLedgerIdDesc(String domain, String ownerId) {
        return selectList(Wrappers.<WalletLedger>lambdaQuery()
                .eq(WalletLedger::getDomain, domain)
                .eq(WalletLedger::getOwnerId, ownerId)
                .orderByDesc(WalletLedger::getLedgerId));
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
}
