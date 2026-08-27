package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.Merchant;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.Collection;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MerchantMapper extends BaseTradeMapper<Merchant> {

    Merchant findByIdForUpdateRaw(@Param("merchantId") String merchantId);

    default Optional<Merchant> findByIdForUpdate(String merchantId) {
        return Optional.ofNullable(findByIdForUpdateRaw(merchantId));
    }

    /** page 为 0-based；merchantIds 为 null 表示不限范围。 */
    default Page<Merchant> searchPage(Collection<String> merchantIds, String keyword, int page, int size) {
        var q = Wrappers.<Merchant>lambdaQuery().orderByAsc(Merchant::getMerchantId);
        if (merchantIds != null) {
            if (merchantIds.isEmpty()) {
                return new Page<>(page + 1L, size, 0);
            }
            q.in(Merchant::getMerchantId, merchantIds);
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            q.and(w -> w.like(Merchant::getMerchantId, kw).or().like(Merchant::getMerchantName, kw));
        }
        return selectPage(new Page<>(page + 1L, size), q);
    }
}
