package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.JiangyiOrderVideo;
import com.aicabinet.trade.mapper.JiangyiOrderVideoMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 将邑订单视频复核（CB-024 §4.2.14）读写门面。
 *
 * <p>存在意义：{@code jiangyi_order_video} 同时被 {@code AdminJiangyiController}（运营复核）
 * 与 {@code JiangyiInternalController}（gateway 上报转发）使用，而「Controller 不得直连 Mapper」
 * 是本仓的架构门禁（{@code TradeArchitectureTest#controllersShouldNotDependOnMappers}）。
 * 2026-10-10 CB-024 落库时曾让两个 controller 直调 {@link JiangyiOrderVideoMapper}，
 * 触发该 ArchUnit 规则（会挡住推送），故在此收口。</p>
 *
 * <p>本类只做薄转发，不改语义：幂等 upsert（uk(order_no, serial_num) 冲突整行覆盖）
 * 与查询排序规则仍在 Mapper 的 default 方法里，避免口径两处维护。</p>
 */
@Service
public class JiangyiOrderVideoService {

    private final JiangyiOrderVideoMapper jiangyiOrderVideoMapper;

    public JiangyiOrderVideoService(JiangyiOrderVideoMapper jiangyiOrderVideoMapper) {
        this.jiangyiOrderVideoMapper = jiangyiOrderVideoMapper;
    }

    /**
     * §4.2.14 幂等落库：uk(order_no, serial_num) 冲突即整行覆盖（设备重试重报以最后一次为准）。
     * 返回是否新插入（false=更新已有行）。上报时间取服务端当前时刻。
     */
    public boolean upsertReport(String orderNo, String deviceId, Integer serialNum,
                                Integer videoQuantity, List<String> videoUrls) {
        return jiangyiOrderVideoMapper.upsertReport(
                orderNo, deviceId, serialNum, videoQuantity, videoUrls, Instant.now());
    }

    /** 按订单查全部分片（serial 升序；video_urls 空串=该片生成/上传失败）。 */
    public List<JiangyiOrderVideo> findByOrderNo(String orderNo) {
        return jiangyiOrderVideoMapper.findByOrderNo(orderNo);
    }

    /** 按设备查最近上报（设备详情卡片复核入口）。 */
    public List<JiangyiOrderVideo> findRecentByDevice(String deviceId, int limit) {
        return jiangyiOrderVideoMapper.findRecentByDevice(deviceId, limit);
    }
}
