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

    default Optional<UserInfo> findByPhoneNumber(String phoneNumber) {
    return Optional.ofNullable(selectOne(Wrappers.<UserInfo>lambdaQuery().eq(UserInfo::getPhoneNumber, phoneNumber)));
    }

    default Optional<UserInfo> findByWxOpenId(String wxOpenId) {
    return Optional.ofNullable(selectOne(Wrappers.<UserInfo>lambdaQuery().eq(UserInfo::getWxOpenId, wxOpenId)));
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

    default Page<UserInfo> searchForAdmin(@Param("phone") String phone, @Param("name") String name, @Param("verified") Boolean verified, @Param("minUserId") Long minUserId, @Param("maxUserId") Long maxUserId, Pageable pageable) {
        var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<UserInfo>(
                pageable.getPageNumber() + 1L, pageable.getPageSize());
        var q = Wrappers.<UserInfo>lambdaQuery()
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

    default List<UserInfo> findByUserIdIn(List<Long> userIds) {
    return selectList(Wrappers.<UserInfo>lambdaQuery().in(UserInfo::getUserId, userIds));
    }

        Long nextOperatorUserId();

        Long nextConsumerUserId(@Param("operatorStart") long operatorStart);


}
