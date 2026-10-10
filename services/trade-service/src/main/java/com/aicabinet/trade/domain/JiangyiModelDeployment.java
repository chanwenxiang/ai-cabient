package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 将邑模型下发审计（CB-023，V336）：跨「将邑云→trade→gateway→设备」四跳的异步动作台账。
 * <p>status：SENT 已下发待回执 / CONFIRMED 设备 downloadModelNotify 确认 / FAILED 超时或校验失败。
 * classes_version = 我方对 classes.txt 内容计算的 sha256 前 12 位（设备不报版本）。</p>
 */
@TableName("jiangyi_model_deployment")
@Getter
@Setter
public class JiangyiModelDeployment {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String deviceId;

    /** 将邑 modelFile id（§4.4.4）。 */
    private String modelId;

    private String modelName;

    private String modelUrl;

    private String classesTextUrl;

    /** 下发时校验通过的机型快照（将邑原值 "76"/"88"）。 */
    private String industrialControlModel;

    private String classesVersion;

    private String status;

    private Instant sentAt;

    private Instant confirmedAt;

    private String failReason;
}
