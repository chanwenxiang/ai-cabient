package com.aicabinet.trade.support;

import com.aicabinet.trade.domain.DeviceInfo;

/**
 * 柜机「点位坐标是否可用」的**唯一判据**。
 *
 * <p>同一个语义在这个仓库里曾经被写成 6 份独立的 {@code getLatitude() != null
 * && getLongitude() != null}：选址/路线优化、设备地图过滤、签到 fail-closed 闸、
 * 任务 DTO 回填……每多一份就多一次漂移机会。本类把它们收敛到一处。
 *
 * <p>语义刻意从严：**经纬度都非空才算有坐标**；设备本身为 null 也算没有。
 * 调用方不要自行改写这个判断，否则门禁 {@code check:replenishment-checkin-contract}
 * 与运行期行为会分叉。
 */
public final class DeviceLocationSupport {

    private DeviceLocationSupport() {
    }

    /**
     * @param device 设备实体，允许为 null
     * @return true 仅当设备非 null 且经纬度**都**已录入
     */
    public static boolean hasCoords(DeviceInfo device) {
        return device != null
                && device.getLatitude() != null
                && device.getLongitude() != null;
    }
}
