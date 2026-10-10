package com.aicabinet.trade.dto;

import java.util.List;

/**
 * 订单购物视频回放清单（CB-030）。
 *
 * <p>面向商户端回放：把「旧边缘链路（会话单文件录像）」与「将邑视频台账（订单多片）」
 * 两条来源收敛成一个清单，前端一次取回后自行播放。消费者端不开放（见台账 CB-030）。</p>
 *
 * <ul>
 *   <li>{@code EDGE}：会话存在 {@code shopping_session.video_uri}，走既有字节流端点，
 *       {@code clips} 为空（保持旧链路零改动）；</li>
 *   <li>{@code JIANGYI}：命中将邑视频台账，{@code clips} 为该订单的可播放分片，
 *       {@code url} 为短时效预签名地址，可直接播放（桶私有，原始地址必然 403）；</li>
 *   <li>{@code NONE}：两条来源都没有可用视频。</li>
 * </ul>
 */
public record OrderVideoPlaylistDto(String source, List<Clip> clips) {

    /** 无来源标记。 */
    public static final String SOURCE_NONE = "NONE";
    /** 旧边缘链路（会话单文件录像）。 */
    public static final String SOURCE_EDGE = "EDGE";
    /** 将邑视频台账（订单多片）。 */
    public static final String SOURCE_JIANGYI = "JIANGYI";

    /**
     * 单个可播放分片。
     *
     * <p>🔴 协议 §4.2.14 的 {@code videoUrls} 是数组：「上/下摄像头地址同片上报」。所以同一
     * {@code serialNum} 下可能有多个 {@code channel}，前端仅凭 {@code serialNum+total} 会渲染出
     * 两个同名「第 N 段」而无法区分 —— 故把上报数组内的位置如实带出来，不做「上/下」之类的语义
     * 命名（协议未定义哪一路是上摄像头，臆测命名会误导复核）。</p>
     *
     * @param serialNum 片序号（1 起）
     * @param total     该次上报的总片数
     * @param channel   该片内摄像头通道序号（1 起，即上报数组下标 +1）
     * @param url       可播放地址；{@code playable=false} 时为 {@code null}
     * @param playable  是否可播放（该片生成/上传失败、或签名服务不可用 → false，不静默丢弃）
     * @param reason    不可播放的原因（可播放时为 {@code null}）
     */
    public record Clip(int serialNum, int total, int channel, String url, boolean playable, String reason) {}
}
