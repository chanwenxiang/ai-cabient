package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 设备侧故障单（CB-077，V416）：固件自动上报的故障入库（如将邑
 * {@code POST /jiangyi/api/order/uploadFaultOrder}，errorMsg=摄像头打开失败）。
 *
 * <p>与消费者手动报修 {@code device_fault_report} 分表不混用：本表是设备自报遥测
 * （无 user_id/issue_type），deviceId 为厂商侧标识不 FK（未注册设备的故障也要留痕，
 * fail-open，与 jiangyi_order_video 同约定）。只作展示/诊断，不参与结算/资金路径。</p>
 */
@TableName("device_fault_order")
@Getter
@Setter
public class DeviceFaultOrder {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 上报设备（gateway 侧 token 解出，不信任 body）。 */
    private String deviceId;

    /** 厂商标识（如 CQYB10720），可能为空。 */
    private String identifier;

    /** 关联单号（我方 sessionId）；固件故障单不一定挂会话，可空。 */
    private String orderNo;

    /** 固件错误码（可空——真机实测 errorCode=null）。 */
    private String errorCode;

    /** 固件原文错误描述（截断后入库）。 */
    private String errorMsg;

    /** 来源协议（JIANGYI_FIRMWARE=将邑 uploadFaultOrder）。 */
    private String source;

    /** 设备侧上报时间（取服务端收到时刻）。 */
    private Instant reportedAt;
}
