package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 将邑开门柜设备登记（CB-022，V332）。
 * <p>与既有 device_info 完全隔离：将邑设备在我方业务域仍是普通 deviceId（会话/订单/结算
 * 全走既有链路），本表只承载「将邑面」的属性——设备侧 SN、将邑签发的 identifier、
 * 模型版本、token 版本与 WS 在线时间。</p>
 *
 * <p>status 语义：UNBOUND 已登记未绑定租户（不可路由）；BOUND 已 setDomain 绑定
 * （可路由，gateway 仅对 BOUND 设备服务）；RETIRED 退役（拒绝一切上报）。</p>
 */
@TableName("jiangyi_device")
@Getter
@Setter
public class JiangyiDevice {

    /** 我方业务域柜机 ID（device_info.device_id 同域，由入驻时登记指定）。 */
    @TableId(value = "device_id", type = IdType.INPUT)
    private String deviceId;

    /** 设备 SN（工控机唯一识别码，如 2b26552554fb7bf9），UNIQUE。 */
    private String deviceSn;

    /** 将邑签发设备编码（如 CQYB11253），WS 路径标识，UNIQUE。 */
    private String identifier;

    private String modelName;

    /** 模型 classes 版本（二期模型同步回填）。 */
    private String classesVersion;

    /**
     * 工控机机型——存将邑原值（"76"/"88"），不解释语义（CB-023：PDF §4.4.4.5 示例中
     * rk3588/rk3576 两种主板该字段同为 "88"，映射关系文档自证不了）；模型下发校验用字符串相等。
     */
    private String industrialControlModel;

    /** 采集模式锁（CB-023）：非空 = 采集中，营业开门 409；NULL = 正常营业。 */
    private Instant gatherLockedAt;

    private String status;

    /** 每次经我方 /jiangyi/api/token 签发 token 时 +1，旧 token 随版本失效。 */
    private Long tokenVersion;

    private Instant tokenIssuedAt;

    private Instant lastWsOnlineAt;

    private Instant createdAt;

    private Instant updatedAt;
}
