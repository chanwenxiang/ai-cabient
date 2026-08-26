package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.PaymentReconciliation;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PaymentReconciliationMapper extends BaseTradeMapper<PaymentReconciliation> {

    default List<PaymentReconciliation> findByReconDateBetweenOrderByReconDateDesc(LocalDate from, LocalDate to) {
    return selectList(Wrappers.<PaymentReconciliation>lambdaQuery().between(PaymentReconciliation::getReconDate, from, to).orderByDesc(PaymentReconciliation::getReconDate));
    }

    default Optional<PaymentReconciliation> findByReconDateAndChannel(LocalDate reconDate, String channel) {
    return Optional.ofNullable(selectOne(Wrappers.<PaymentReconciliation>lambdaQuery().eq(PaymentReconciliation::getReconDate, reconDate).eq(PaymentReconciliation::getChannel, channel)));
    }

    default List<PaymentReconciliation> findTop10ByStatusOrderByCompletedAtDesc(String status) {
    return selectList(Wrappers.<PaymentReconciliation>lambdaQuery().eq(PaymentReconciliation::getStatus, status).orderByDesc(PaymentReconciliation::getCompletedAt).last("LIMIT 10"));
    }

    default long countByStatus(String status) {
    Long c = selectCount(Wrappers.<PaymentReconciliation>lambdaQuery().eq(PaymentReconciliation::getStatus, status));
    return c == null ? 0 : c;
    }

    /** page 为 0-based。 */
    default Page<PaymentReconciliation> searchPage(LocalDate from, LocalDate to, String channel,
                                                     String status, String keyword, int page, int size) {
        var q = Wrappers.<PaymentReconciliation>lambdaQuery()
                .between(PaymentReconciliation::getReconDate, from, to)
                .orderByDesc(PaymentReconciliation::getReconDate);
        if (channel != null && !channel.isBlank()) {
            q.eq(PaymentReconciliation::getChannel, channel.trim().toUpperCase());
        }
        if (status != null && !status.isBlank()) {
            q.eq(PaymentReconciliation::getStatus, status.trim().toUpperCase());
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            q.and(w -> w.apply("CAST(recon_id AS TEXT) LIKE {0}", "%" + kw + "%")
                    .or().apply("recon_date::text LIKE {0}", "%" + kw + "%")
                    .or().like(PaymentReconciliation::getChannel, kw));
        }
        return selectPage(new Page<>(page + 1L, size), q);
    }

}
