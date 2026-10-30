package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.DeviceFaultOrder;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;
import java.util.List;

@Mapper
public interface DeviceFaultOrderMapper extends BaseTradeMapper<DeviceFaultOrder> {

    /**
     * 故障单落库（CB-077）：一行一报，不去重——同一故障跨会话/跨重启重复出现正是诊断要看的
     * 事实（如摄像头打开失败 4 次跨重启 = 硬件级）。reportedAt 取服务端收到时刻。
     */
    default void insertFault(String deviceId, String identifier, String orderNo,
                             String errorCode, String errorMsg, Instant reportedAt) {
        DeviceFaultOrder row = new DeviceFaultOrder();
        row.setDeviceId(deviceId);
        row.setIdentifier(identifier);
        row.setOrderNo(orderNo);
        row.setErrorCode(errorCode);
        row.setErrorMsg(errorMsg == null ? "" : errorMsg);
        row.setReportedAt(reportedAt);
        insert(row);
    }

    /** 按设备查最近故障（后台/商户端设备详情「故障记录」卡：reported_at 降序）。 */
    default List<DeviceFaultOrder> findRecentByDevice(String deviceId, int limit) {
        return selectList(Wrappers.<DeviceFaultOrder>lambdaQuery()
                .eq(DeviceFaultOrder::getDeviceId, deviceId)
                .orderByDesc(DeviceFaultOrder::getReportedAt)
                .last("LIMIT " + Math.max(1, Math.min(limit, 100))));
    }
}
