package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 将邑学习触发凭据（CB-023，V336）：finishNotifyId 是服务端持有的一次性凭据——
 * 将邑要求回调 url 无鉴权（§4.4.3 原文），防伪造锚点必须服务端持久化。
 * <p>回调命中 PENDING 才处理（并反查将邑侧交叉验证），其余静默丢弃。</p>
 */
@TableName("jiangyi_training_ticket")
@Getter
@Setter
public class JiangyiTrainingTicket {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String deviceId;

    private String skuId;

    private String jiangyiProductId;

    /** 一次性凭据（UUID），UNIQUE。 */
    private String finishNotifyId;

    private String modelName;

    private String status;

    private Instant createdAt;

    private Instant finishedAt;
}
