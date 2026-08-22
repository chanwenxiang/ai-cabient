package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.OtaRelease;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OtaReleaseMapper extends BaseTradeMapper<OtaRelease> {

    OtaRelease _findByIdForUpdateRaw(@Param("releaseId") Long releaseId);

    default Optional<OtaRelease> findByIdForUpdate(Long releaseId) {
        return Optional.ofNullable(_findByIdForUpdateRaw(releaseId));
    }

    default List<OtaRelease> findByStatusOrderByPublishedAtDesc(String status) {
    return selectList(Wrappers.<OtaRelease>lambdaQuery().eq(OtaRelease::getStatus, status).orderByDesc(OtaRelease::getPublishedAt));
    }

    default Optional<OtaRelease> findFirstByChannelAndStatusOrderByPublishedAtDesc(String channel, String status) {
    return Optional.ofNullable(selectOne(Wrappers.<OtaRelease>lambdaQuery().eq(OtaRelease::getChannel, channel).eq(OtaRelease::getStatus, status).orderByDesc(OtaRelease::getPublishedAt).last("LIMIT 1")));
    }

}
