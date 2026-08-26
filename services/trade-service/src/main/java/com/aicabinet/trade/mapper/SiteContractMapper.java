package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.SiteContract;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface SiteContractMapper extends BaseTradeMapper<SiteContract> {

    SiteContract _findByDeviceIdForUpdateRaw(@Param("deviceId") String deviceId);

    default Optional<SiteContract> findByDeviceIdForUpdate(String deviceId) {
        return Optional.ofNullable(_findByDeviceIdForUpdateRaw(deviceId));
    }

    SiteContract _findByIdForUpdateRaw(@Param("contractId") Long contractId);

    default Optional<SiteContract> findByIdForUpdate(Long contractId) {
        return Optional.ofNullable(_findByIdForUpdateRaw(contractId));
    }

    default List<SiteContract> findAllOrderByUpdatedDesc() {
        return selectList(Wrappers.<SiteContract>lambdaQuery()
                .orderByDesc(SiteContract::getUpdatedAt));
    }

    /** page 为 0-based。 */
    default Page<SiteContract> searchPage(int page, int size) {
        return selectPage(new Page<>(page + 1L, size),
                Wrappers.<SiteContract>lambdaQuery().orderByDesc(SiteContract::getUpdatedAt));
    }

    default Optional<SiteContract> findByDeviceId(String deviceId) {
        return Optional.ofNullable(selectOne(Wrappers.<SiteContract>lambdaQuery()
                .eq(SiteContract::getDeviceId, deviceId)));
    }
}
