package com.aicabinet.trade.service;

import com.aicabinet.trade.client.JiangyiGatewayClient;
import com.aicabinet.trade.domain.JiangyiOrderVideo;
import com.aicabinet.trade.mapper.JiangyiOrderVideoMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

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
 *
 * <p>CB-029 追加：{@link #playUrls(long)} 把设备上报的原始地址换成短时效可播放地址
 * （桶私有，签名在 gateway 侧完成，trade 不持 OSS 凭据）。</p>
 */
@Service
public class JiangyiOrderVideoService {

    private final JiangyiOrderVideoMapper jiangyiOrderVideoMapper;
    private final JiangyiGatewayClient jiangyiGatewayClient;

    public JiangyiOrderVideoService(JiangyiOrderVideoMapper jiangyiOrderVideoMapper,
                                    JiangyiGatewayClient jiangyiGatewayClient) {
        this.jiangyiOrderVideoMapper = jiangyiOrderVideoMapper;
        this.jiangyiGatewayClient = jiangyiGatewayClient;
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

    /** 复核用播放视图（CB-029）：一行记录 + 逐片可播放地址。 */
    public record PlayUrlView(Long id, String orderNo, String deviceId, Integer serialNum,
                              Integer videoQuantity, Instant reportedAt, List<PlayUrlItem> items) {}

    /**
     * 单片的可播放地址。
     *
     * <ul>
     *   <li>{@code signed=true}：本桶对象，换成短时效预签名 URL；</li>
     *   <li>{@code signed=false, playable=true}：非本桶地址（设备上报外部 URL），原样给出；</li>
     *   <li>{@code playable=false}：该片生成/上传失败，或签名服务不可用（fail-closed，不给坏链接）。</li>
     * </ul>
     */
    public record PlayUrlItem(int index, String url, boolean signed, boolean playable, String reason) {}

    /**
     * 取某条视频上报记录的可播放地址（CB-029 读路径）。
     *
     * <p>🔴 桶是私有的、直传角色只有 PutObject（实测匿名 GET 403），所以设备上报的原始 URL
     * 直接播放会 403 —— 必须逐片换签名 URL。空串分片是文档明示的「该片失败」语义，如实回
     * {@code playable=false}，不静默丢弃（复核要看得见失败片）。</p>
     *
     * @throws IllegalArgumentException 记录不存在（404/400 由全局异常处理器落定为 400）
     */
    public PlayUrlView playUrls(long id) {
        JiangyiOrderVideo row = jiangyiOrderVideoMapper.selectById(id);
        if (row == null) {
            throw new IllegalArgumentException("视频记录不存在: " + id);
        }
        String raw = row.getVideoUrls();
        // '' / null 都是「单片失败」语义（协议 §4.2.14：[] = 视频生成或上传失败）→ 保留一个失败位
        List<String> refs = (raw == null || raw.isBlank())
                ? List.of("")
                : Arrays.stream(raw.split(",")).map(String::trim).toList();
        List<PlayUrlItem> items = new ArrayList<>(refs.size());
        for (int i = 0; i < refs.size(); i++) {
            items.add(playUrlItem(i + 1, refs.get(i)));
        }
        return new PlayUrlView(row.getId(), row.getOrderNo(), row.getDeviceId(), row.getSerialNum(),
                row.getVideoQuantity(), row.getReportedAt(), items);
    }

    private PlayUrlItem playUrlItem(int index, String ref) {
        if (ref == null || ref.isEmpty()) {
            return new PlayUrlItem(index, null, false, false, "该片生成或上传失败");
        }
        Optional<JiangyiGatewayClient.PresignTarget> target = jiangyiGatewayClient.presignGet(ref);
        if (target.isEmpty()) {
            return new PlayUrlItem(index, null, false, false, "播放地址签发失败");
        }
        if (target.get().external()) {
            return new PlayUrlItem(index, ref, false, true, null);
        }
        return new PlayUrlItem(index, target.get().url(), true, true, null);
    }
}
