package com.aicabinet.trade.domain;

import com.aicabinet.trade.config.JsonStringTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 识别结果（会话维度）。
 *
 * <p>表自 {@code V1__init_schema.sql} 就存在，但长期无人写入（建表即孤儿）；本类补齐运行期写入者。
 * 语义与 {@code V222} 的 demo seed 一致：<b>一个会话一份识别结果</b>
 * （seed 用 {@code NOT EXISTS (… WHERE r.session_id = s.session_id)} 保证会话级唯一）。
 *
 * <p>字段约束（写库前需满足，否则是列错误而非业务错误）：
 * <ul>
 *   <li>{@code task_id}：主键，{@code VARCHAR(64)}，由调用方给定（端侧任务号；缺省为 {@code T-<sessionId>}）。</li>
 *   <li>{@code items}：{@code JSONB NOT NULL}，键为 {@code skuId/quantity/confidence}
 *       （与 Kafka 报文 {@code VisionRecognitionListener} 解析的键一致）。</li>
 *   <li>{@code model_version}：{@code VARCHAR(32)}，超长会被 PostgreSQL 拒收。</li>
 * </ul>
 */
@TableName(value = "recognition_result", autoResultMap = true)
@Getter
@Setter
public class RecognitionResult {

    @TableId(type = IdType.INPUT)
    private String taskId;

    private String sessionId;

    /** JSON 数组文本，形如 {@code [{"skuId":"SKU-1","quantity":2,"confidence":0.93}]}。 */
    @TableField(typeHandler = JsonStringTypeHandler.class)
    private String items;

    private Float overallConfidence;

    /** 融合模式；当前仅 VISION 一种取值（与 V222 demo seed 对齐）。 */
    private String fusionMode;

    private String modelVersion;

    private Boolean needReview;

    /** 留空即由列默认 {@code NOW()} 兜底。 */
    private Instant createdAt;
}
