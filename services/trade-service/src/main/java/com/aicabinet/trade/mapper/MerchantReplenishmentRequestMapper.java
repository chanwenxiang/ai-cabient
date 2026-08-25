package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.MerchantReplenishmentRequest;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MerchantReplenishmentRequestMapper extends BaseTradeMapper<MerchantReplenishmentRequest> {

    MerchantReplenishmentRequest _findByIdForUpdateRaw(@Param("requestId") Long requestId);

    default Optional<MerchantReplenishmentRequest> findByIdForUpdate(Long requestId) {
        return Optional.ofNullable(_findByIdForUpdateRaw(requestId));
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

}
