package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.RiskEvent;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@Mapper
public interface RiskEventMapper extends BaseTradeMapper<RiskEvent> {

    default Page<RiskEvent> findAllByOrderByCreatedAtDesc(Pageable pageable) {
    var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<RiskEvent>(
            pageable.getPageNumber() + 1L, pageable.getPageSize());
    var result = selectPage(mpPage, Wrappers.<RiskEvent>lambdaQuery().orderByDesc(RiskEvent::getCreatedAt));
    return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    default long countByUserIdAndEventTypeAndCreatedAtAfter(Long userId, String eventType, Instant since) {
    Long c = selectCount(Wrappers.<RiskEvent>lambdaQuery().eq(RiskEvent::getUserId, userId).eq(RiskEvent::getEventType, eventType).gt(RiskEvent::getCreatedAt, since));
    return c == null ? 0 : c;
    }

    default long countByUserIdAndCreatedAtAfter(Long userId, Instant since) {
    Long c = selectCount(Wrappers.<RiskEvent>lambdaQuery().eq(RiskEvent::getUserId, userId).gt(RiskEvent::getCreatedAt, since));
    return c == null ? 0 : c;
    }

}
