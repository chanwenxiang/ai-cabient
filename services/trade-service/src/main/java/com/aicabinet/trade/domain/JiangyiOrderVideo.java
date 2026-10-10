package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 将邑设备视频上报台账（CB-024，V338，V16 §4.2.14）。
 *
 * <p>设备经 STS 直传 OSS 后把视频地址报到 gateway，gateway 转发本服务落库。
 * 分片语义：视频超 1 分半自动分文件，按 (order_no, serial_num) 幂等 upsert——
 * 设备重试重报不产生重复行；video_urls 空串 = 该片生成/上传失败（文档明示 [] 语义）。</p>
 */
@TableName("jiangyi_order_video")
@Getter
@Setter
public class JiangyiOrderVideo {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 将邑设备上报的订单编号（原值存储，不 FK——文档未承诺与我方 order_no 同域）。 */
    private String orderNo;

    /** 上报设备（gateway 侧 token 解出，不信任 body）。 */
    private String deviceId;

    /** 视频序号（第几片，1 起）。 */
    private Integer serialNum;

    /** 视频总片数。 */
    private Integer videoQuantity;

    /** 视频 URL 逗号拼接（上/下摄像头同片）；空串=该片失败留痕。 */
    private String videoUrls;

    private Instant reportedAt;
}
