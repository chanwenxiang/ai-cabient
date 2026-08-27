package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.MerchantReplenishmentRequest;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MerchantReplenishmentRequestMapper extends BaseTradeMapper<MerchantReplenishmentRequest> {

    MerchantReplenishmentRequest findByIdForUpdateRaw(@Param("requestId") Long requestId);

    default Optional<MerchantReplenishmentRequest> findByIdForUpdate(Long requestId) {
        return Optional.ofNullable(findByIdForUpdateRaw(requestId));
    }

    default List<MerchantReplenishmentRequest> findByDeviceIdInOrderBySubmittedAtDesc(Collection<String> deviceIds) {
    return selectList(Wrappers.<MerchantReplenishmentRequest>lambdaQuery().in(MerchantReplenishmentRequest::getDeviceId, deviceIds).orderByDesc(MerchantReplenishmentRequest::getSubmittedAt));
    }

    default List<MerchantReplenishmentRequest> findByStatusOrderBySubmittedAtAsc(String status) {
    return selectList(Wrappers.<MerchantReplenishmentRequest>lambdaQuery().eq(MerchantReplenishmentRequest::getStatus, status).orderByAsc(MerchantReplenishmentRequest::getSubmittedAt));
    }

    default List<MerchantReplenishmentRequest> findByStatusOrderBySubmittedAtDesc(String status) {
    return selectList(Wrappers.<MerchantReplenishmentRequest>lambdaQuery().eq(MerchantReplenishmentRequest::getStatus, status).orderByDesc(MerchantReplenishmentRequest::getSubmittedAt));
    }

    default List<MerchantReplenishmentRequest> findAllOrderBySubmittedAtDesc() {
    return selectList(Wrappers.<MerchantReplenishmentRequest>lambdaQuery().orderByDesc(MerchantReplenishmentRequest::getSubmittedAt));
    }

    default List<MerchantReplenishmentRequest> findByMerchantIdInOrderBySubmittedAtDesc(Collection<String> merchantIds) {
    return selectList(Wrappers.<MerchantReplenishmentRequest>lambdaQuery().in(MerchantReplenishmentRequest::getMerchantId, merchantIds).orderByDesc(MerchantReplenishmentRequest::getSubmittedAt));
    }

    /** page 为 0-based；status 传 ALL 或空表示不限。 */
    default Page<MerchantReplenishmentRequest> searchPage(String status, int page, int size) {
        var query = Wrappers.<MerchantReplenishmentRequest>lambdaQuery();
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status.trim())) {
            query.eq(MerchantReplenishmentRequest::getStatus, status.trim().toUpperCase());
        }
        if (status != null && "SUBMITTED".equalsIgnoreCase(status.trim())) {
            query.orderByAsc(MerchantReplenishmentRequest::getSubmittedAt);
        } else {
            query.orderByDesc(MerchantReplenishmentRequest::getSubmittedAt);
        }
        return selectPage(new Page<>(page + 1L, size), query);
    }

}
