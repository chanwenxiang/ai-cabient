package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.Announcement;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AnnouncementMapper extends BaseTradeMapper<Announcement> {

    Announcement findByIdForUpdateRaw(@Param("announceId") Long announceId);

    default Optional<Announcement> findByIdForUpdate(Long announceId) {
        return Optional.ofNullable(findByIdForUpdateRaw(announceId));
    }

    default List<Announcement> findByStatusOrderByPublishAtDesc(String status) {
    return selectList(Wrappers.<Announcement>lambdaQuery().eq(Announcement::getStatus, status).orderByDesc(Announcement::getPublishAt));
    }

    default List<Announcement> findByTargetScopeAndStatusOrderByPublishAtDesc(String targetScope, String status) {
    return selectList(Wrappers.<Announcement>lambdaQuery().eq(Announcement::getTargetScope, targetScope).eq(Announcement::getStatus, status).orderByDesc(Announcement::getPublishAt));
    }

    /** page 为 0-based。 */
    default Page<Announcement> searchPage(String keyword, String status, String priority, int page, int size) {
        var q = Wrappers.<Announcement>lambdaQuery().orderByDesc(Announcement::getAnnounceId);
        if (status != null && !status.isBlank()) {
            q.eq(Announcement::getStatus, status.trim());
        }
        if (priority != null && !priority.isBlank()) {
            q.eq(Announcement::getPriority, priority.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            q.and(w -> w.like(Announcement::getTitle, kw).or().like(Announcement::getContent, kw));
        }
        return selectPage(new Page<>(page + 1L, size), q);
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
