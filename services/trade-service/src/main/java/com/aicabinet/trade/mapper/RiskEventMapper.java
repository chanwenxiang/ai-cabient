package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.RiskEvent;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@Mapper
public interface RiskEventMapper extends BaseTradeMapper<RiskEvent> {

    RiskEvent findByIdForUpdateRaw(@Param("eventId") Long eventId);

    default Optional<RiskEvent> findByIdForUpdate(Long eventId) {
        return Optional.ofNullable(findByIdForUpdateRaw(eventId));
    }

    default Page<RiskEvent> findAllByOrderByCreatedAtDesc(Pageable pageable) {
    var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<RiskEvent>(
            pageable.getPageNumber() + 1L, pageable.getPageSize());
    var result = selectPage(mpPage, Wrappers.<RiskEvent>lambdaQuery().orderByDesc(RiskEvent::getCreatedAt));
    return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    /**
     * 按设备范围导出（`null`=不限，空集=空页）。导出面的范围判据必须与列表同源，见
     * {@code OpsCsvExportService#exportRiskEventsCsv}。
     */
    default Page<RiskEvent> findAllByOrderByCreatedAtDesc(
            java.util.Collection<String> deviceIds, Pageable pageable) {
        var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<RiskEvent>(
                pageable.getPageNumber() + 1L, pageable.getPageSize());
        if (deviceIds != null && deviceIds.isEmpty()) {
            return new org.springframework.data.domain.PageImpl<>(
                    java.util.List.<RiskEvent>of(), pageable, 0);
        }
        var query = Wrappers.<RiskEvent>lambdaQuery().orderByDesc(RiskEvent::getCreatedAt);
        if (deviceIds != null) {
            query.in(RiskEvent::getDeviceId, deviceIds);
        }
        var result = selectPage(mpPage, query);
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
