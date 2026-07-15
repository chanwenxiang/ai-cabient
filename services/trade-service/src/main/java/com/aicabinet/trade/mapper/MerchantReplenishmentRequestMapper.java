package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.MerchantReplenishmentRequest;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MerchantReplenishmentRequestMapper extends BaseTradeMapper<MerchantReplenishmentRequest> {

    default List<MerchantReplenishmentRequest> findByDeviceIdInOrderBySubmittedAtDesc(Collection<String> deviceIds) {
    return selectList(Wrappers.<MerchantReplenishmentRequest>lambdaQuery().in(MerchantReplenishmentRequest::getDeviceId, deviceIds).orderByDesc(MerchantReplenishmentRequest::getSubmittedAt));
    }

    default List<MerchantReplenishmentRequest> findByStatusOrderBySubmittedAtAsc(String status) {
    return selectList(Wrappers.<MerchantReplenishmentRequest>lambdaQuery().eq(MerchantReplenishmentRequest::getStatus, status).orderByAsc(MerchantReplenishmentRequest::getSubmittedAt));
    }

    default List<MerchantReplenishmentRequest> findByMerchantIdInOrderBySubmittedAtDesc(Collection<String> merchantIds) {
    return selectList(Wrappers.<MerchantReplenishmentRequest>lambdaQuery().in(MerchantReplenishmentRequest::getMerchantId, merchantIds).orderByDesc(MerchantReplenishmentRequest::getSubmittedAt));
    }

}
