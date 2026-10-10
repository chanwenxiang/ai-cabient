package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.JiangyiOrderVideo;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;
import java.util.List;

@Mapper
public interface JiangyiOrderVideoMapper extends BaseTradeMapper<JiangyiOrderVideo> {

    /**
     * §4.2.14 幂等落库：uk(order_no, serial_num) 冲突即整行覆盖（设备重试重报以最后一次为准，
     * 语义安全——同一片视频地址只会在分片内变化）。返回是否新插入（false=更新已有行）。
     */
    default boolean upsertReport(String orderNo, String deviceId, Integer serialNum,
                                 Integer videoQuantity, List<String> videoUrls, Instant reportedAt) {
        String joined = String.join(",", videoUrls == null ? List.of() : videoUrls);
        JiangyiOrderVideo existing = selectOne(Wrappers.<JiangyiOrderVideo>lambdaQuery()
                .eq(JiangyiOrderVideo::getOrderNo, orderNo)
                .eq(JiangyiOrderVideo::getSerialNum, serialNum));
        if (existing == null) {
            JiangyiOrderVideo row = new JiangyiOrderVideo();
            row.setOrderNo(orderNo);
            row.setDeviceId(deviceId);
            row.setSerialNum(serialNum);
            row.setVideoQuantity(videoQuantity);
            row.setVideoUrls(joined);
            row.setReportedAt(reportedAt);
            return insert(row) > 0;
        }
        existing.setDeviceId(deviceId);
        existing.setVideoQuantity(videoQuantity);
        existing.setVideoUrls(joined);
        existing.setReportedAt(reportedAt);
        updateById(existing);
        // 契约：返回「是否新插入」；更新路径恒 false（调用方只用于日志/排障区分分支）
        return false;
    }

    /** 按订单查全部分片（admin 复核：serial_num 升序，失败片 video_urls 空串也在列）。 */
    default List<JiangyiOrderVideo> findByOrderNo(String orderNo) {
        return selectList(Wrappers.<JiangyiOrderVideo>lambdaQuery()
                .eq(JiangyiOrderVideo::getOrderNo, orderNo)
                .orderByAsc(JiangyiOrderVideo::getSerialNum));
    }

    /** 按设备查最近上报（admin 设备详情卡片：复核入口）。 */
    default List<JiangyiOrderVideo> findRecentByDevice(String deviceId, int limit) {
        return selectList(Wrappers.<JiangyiOrderVideo>lambdaQuery()
                .eq(JiangyiOrderVideo::getDeviceId, deviceId)
                .orderByDesc(JiangyiOrderVideo::getReportedAt)
                .last("LIMIT " + Math.max(1, Math.min(limit, 100))));
    }
}
