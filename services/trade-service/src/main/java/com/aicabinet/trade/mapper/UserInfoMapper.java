package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.UserInfo;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@Mapper
public interface UserInfoMapper extends BaseTradeMapper<UserInfo> {

    UserInfo findByIdForUpdateRaw(@Param("userId") Long userId);

    default Optional<UserInfo> findByIdForUpdate(Long userId) {
        return Optional.ofNullable(findByIdForUpdateRaw(userId));
    }

    default Optional<UserInfo> findByPhoneNumber(String phoneNumber) {
    return Optional.ofNullable(selectOne(Wrappers.<UserInfo>lambdaQuery().eq(UserInfo::getPhoneNumber, phoneNumber)));
    }

    default Optional<UserInfo> findByWxOpenId(String wxOpenId) {
    return Optional.ofNullable(selectOne(Wrappers.<UserInfo>lambdaQuery().eq(UserInfo::getWxOpenId, wxOpenId)));
    }

    default Optional<UserInfo> findByAlipayUserId(String alipayUserId) {
        return Optional.ofNullable(selectOne(Wrappers.<UserInfo>lambdaQuery().eq(UserInfo::getAlipayUserId, alipayUserId)));
    }

    default Optional<UserInfo> findByAlipayAgreementId(String alipayAgreementId) {
        return Optional.ofNullable(selectOne(Wrappers.<UserInfo>lambdaQuery()
                .eq(UserInfo::getAlipayAgreementId, alipayAgreementId)));
    }

    default Page<UserInfo> findAllByOrderByUserIdDesc(Pageable pageable) {
    var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<UserInfo>(
            pageable.getPageNumber() + 1L, pageable.getPageSize());
    var result = selectPage(mpPage, Wrappers.<UserInfo>lambdaQuery().orderByDesc(UserInfo::getUserId));
    return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    default Page<UserInfo> findByPhoneNumberContainingOrderByUserIdDesc(String phoneNumber, Pageable pageable) {
    var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<UserInfo>(
            pageable.getPageNumber() + 1L, pageable.getPageSize());
    var result = selectPage(mpPage, Wrappers.<UserInfo>lambdaQuery().like(UserInfo::getPhoneNumber, phoneNumber).orderByDesc(UserInfo::getUserId));
    return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    default Page<UserInfo> searchForAdmin(@Param("userId") Long userId, @Param("phone") String phone, @Param("name") String name, @Param("verified") Boolean verified, @Param("minUserId") Long minUserId, @Param("maxUserId") Long maxUserId, Pageable pageable) {
        var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<UserInfo>(
                pageable.getPageNumber() + 1L, pageable.getPageSize());
        var q = Wrappers.<UserInfo>lambdaQuery()
                .eq(userId != null, UserInfo::getUserId, userId)
                .like(phone != null && !phone.isEmpty(), UserInfo::getPhoneNumber, phone)
                .like(name != null && !name.isEmpty(), UserInfo::getName, name)
                .eq(verified != null, UserInfo::isVerified, verified)
                .ge(minUserId != null, UserInfo::getUserId, minUserId)
                .le(maxUserId != null, UserInfo::getUserId, maxUserId)
                .orderByDesc(UserInfo::getUserId);
        var result = selectPage(mpPage, q);
        return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    default Page<UserInfo> findByUserIdGreaterThanEqualOrderByUserIdDesc(Long userId, Pageable pageable) {
    var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<UserInfo>(
            pageable.getPageNumber() + 1L, pageable.getPageSize());
    var result = selectPage(mpPage, Wrappers.<UserInfo>lambdaQuery().ge(UserInfo::getUserId, userId).orderByDesc(UserInfo::getUserId));
    return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    default Page<UserInfo> findByUserIdGreaterThanEqualAndPhoneNumberContainingOrderByUserIdDesc( Long userId, String phoneNumber, Pageable pageable) {
    var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<UserInfo>(
            pageable.getPageNumber() + 1L, pageable.getPageSize());
    var result = selectPage(mpPage, Wrappers.<UserInfo>lambdaQuery().ge(UserInfo::getUserId, userId).like(UserInfo::getPhoneNumber, phoneNumber).orderByDesc(UserInfo::getUserId));
    return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    /**
     * 运营账号列表：优先 accountType=OPERATOR；
     * accountType 为空的历史数据仍按 userId ≥ operatorStart 兜底（A-7 过渡）。
     */
    default Page<UserInfo> findOperatorsOrderByUserIdDesc(String accountType, long operatorUserIdStart,
                                                          String phoneNumber, Pageable pageable) {
        var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<UserInfo>(
                pageable.getPageNumber() + 1L, pageable.getPageSize());
        var q = Wrappers.<UserInfo>lambdaQuery()
                .and(w -> w.eq(UserInfo::getAccountType, accountType)
                        .or(w2 -> w2.isNull(UserInfo::getAccountType)
                                .ge(UserInfo::getUserId, operatorUserIdStart)));
        if (phoneNumber != null && !phoneNumber.isBlank()) {
            q.like(UserInfo::getPhoneNumber, phoneNumber.trim());
        }
        q.orderByDesc(UserInfo::getUserId);
        var result = selectPage(mpPage, q);
        return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    default List<UserInfo> findByUserIdIn(List<Long> userIds) {
    return selectList(Wrappers.<UserInfo>lambdaQuery().in(UserInfo::getUserId, userIds));
    }

        Long nextOperatorUserId();

        Long nextConsumerUserId(@Param("operatorStart") long operatorStart);


}
