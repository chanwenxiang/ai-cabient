package com.aicabinet.trade.domain;

import com.aicabinet.trade.config.JsonStringTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 将邑识别上报原样留档台账（CB-062，V396）。append-only：一到达一行，不覆写、不设删除任务。
 *
 * <p>🔴 {@code vendorAmountCents} 是厂商上报价合计，<b>只作差异证据，绝不参与结算</b>
 * （A 档拍板，见 {@code docs/JIANGYI_GATEWAY_DESIGN.md} §0）。{@code itemsJson} 是我方
 * 归一化后的结算行，与 {@code formsJson}（原文）并排才能回答「厂商报的 vs 我方扣的」。</p>
 */
@TableName(value = "jiangyi_recognition_report", autoResultMap = true)
@Getter
@Setter
public class JiangyiRecognitionReport {

    /** 处置码（与 V396 列注释同源；gateway 侧唯一写入方）。 */
    public static final String OUTCOME_FORWARDED = "FORWARDED";
    public static final String OUTCOME_MAPPING_MISS = "MAPPING_MISS";
    public static final String OUTCOME_EMPTY_FORMS = "EMPTY_FORMS";
    public static final String OUTCOME_BLANK_ORDERNO = "BLANK_ORDERNO";
    public static final String OUTCOME_FORWARD_FAILED = "FORWARD_FAILED";

    @TableId(type = IdType.AUTO)
    private Long reportId;

    private String deviceId;

    /** ≡ 我方 sessionId；空单号报文存 null。 */
    private String orderNo;

    private String transactionId;

    private String payType;

    @TableField(typeHandler = JsonStringTypeHandler.class)
    private String formsJson;

    @TableField(typeHandler = JsonStringTypeHandler.class)
    private String itemsJson;

    private Integer formCount;

    private Integer itemCount;

    private Long vendorAmountCents;

    private String outcome;

    private Instant reportedAt;
}
