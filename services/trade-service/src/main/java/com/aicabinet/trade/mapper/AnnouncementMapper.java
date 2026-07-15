package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.Announcement;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AnnouncementMapper extends BaseTradeMapper<Announcement> {

    default List<Announcement> findByStatusOrderByPublishAtDesc(String status) {
    return selectList(Wrappers.<Announcement>lambdaQuery().eq(Announcement::getStatus, status).orderByDesc(Announcement::getPublishAt));
    }

    default List<Announcement> findByTargetScopeAndStatusOrderByPublishAtDesc(String targetScope, String status) {
    return selectList(Wrappers.<Announcement>lambdaQuery().eq(Announcement::getTargetScope, targetScope).eq(Announcement::getStatus, status).orderByDesc(Announcement::getPublishAt));
    }

}
