package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.Announcement;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AnnouncementMapper extends BaseTradeMapper<Announcement> {

    Announcement _findByIdForUpdateRaw(@Param("announceId") Long announceId);

    default Optional<Announcement> findByIdForUpdate(Long announceId) {
        return Optional.ofNullable(_findByIdForUpdateRaw(announceId));
    }

    default List<Announcement> findByStatusOrderByPublishAtDesc(String status) {
    return selectList(Wrappers.<Announcement>lambdaQuery().eq(Announcement::getStatus, status).orderByDesc(Announcement::getPublishAt));
    }

    default List<Announcement> findByTargetScopeAndStatusOrderByPublishAtDesc(String targetScope, String status) {
    return selectList(Wrappers.<Announcement>lambdaQuery().eq(Announcement::getTargetScope, targetScope).eq(Announcement::getStatus, status).orderByDesc(Announcement::getPublishAt));
    }

    /** Published announcements for audience scope or ALL, excluding expired. */
    default List<Announcement> findPublishedForAudience(String audience, Instant now) {
        return selectList(Wrappers.<Announcement>lambdaQuery()
                .eq(Announcement::getStatus, "PUBLISHED")
                .and(w -> w.eq(Announcement::getTargetScope, audience).or().eq(Announcement::getTargetScope, "ALL"))
                .and(w -> w.isNull(Announcement::getExpireAt).or().gt(Announcement::getExpireAt, now))
                .orderByDesc(Announcement::getPublishAt));
    }

}
