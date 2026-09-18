package com.aicabinet.common.dto;

public record UpsertDeviceRequest(
        /** 已废弃：编号由系统自动分配 12 位纯数字，传入将被拒绝。 */
        String deviceId,
        String deviceName,
        String deviceType,
        String merchantId,
        /**
         * 点位纬度。柜机「绑定商户即部署」时必须录入（无坐标 = 补货签到无法做地理围栏）；
         * 仅入库（INBOUND）阶段可先为空，后续通过编辑设备补录。
         */
        Double latitude,
        /** 点位经度，规则同 {@link #latitude}。 */
        Double longitude,
        /** 点位地址（可选，便于运营核对，不参与围栏计算）。 */
        String address
) {}
