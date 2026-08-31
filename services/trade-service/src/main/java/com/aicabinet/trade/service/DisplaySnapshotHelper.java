package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.RepairTicket;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import org.springframework.stereotype.Component;

/**
 * 列表展示冗余字段写入（设备名/商户名），避免后台列表 N+1 查主数据。
 */
@Component
public class DisplaySnapshotHelper {

    private final DeviceInfoMapper deviceInfoMapper;
    private final MerchantMapper merchantMapper;

    public DisplaySnapshotHelper(DeviceInfoMapper deviceInfoMapper, MerchantMapper merchantMapper) {
        this.deviceInfoMapper = deviceInfoMapper;
        this.merchantMapper = merchantMapper;
    }

    public void applySessionDeviceName(ShoppingSession session) {
        if (session == null || session.getDeviceId() == null || session.getDeviceId().isBlank()) {
            return;
        }
        if (session.getDeviceName() != null && !session.getDeviceName().isBlank()) {
            return;
        }
        DeviceInfo device = deviceInfoMapper.selectById(session.getDeviceId());
        if (device != null) {
            session.setDeviceName(device.getDeviceName());
        }
    }

    public void applyOrderSnapshot(CabinetOrder order) {
        if (order == null || order.getDeviceId() == null || order.getDeviceId().isBlank()) {
            return;
        }
        DeviceInfo device = deviceInfoMapper.selectById(order.getDeviceId());
        if (device == null) {
            return;
        }
        if (order.getDeviceName() == null || order.getDeviceName().isBlank()) {
            order.setDeviceName(device.getDeviceName());
        }
        if (order.getMerchantId() == null || order.getMerchantId().isBlank()) {
            order.setMerchantId(device.getMerchantId());
        }
        if ((order.getMerchantName() == null || order.getMerchantName().isBlank())
                && device.getMerchantId() != null) {
            order.setMerchantName(resolveMerchantName(device.getMerchantId()));
        }
    }

    public void applyRepairSnapshot(RepairTicket ticket) {
        if (ticket == null || ticket.getDeviceId() == null || ticket.getDeviceId().isBlank()) {
            return;
        }
        DeviceInfo device = deviceInfoMapper.selectById(ticket.getDeviceId());
        if (device == null) {
            return;
        }
        ticket.setDeviceName(device.getDeviceName());
        ticket.setMerchantId(device.getMerchantId());
        if (device.getMerchantId() != null) {
            ticket.setMerchantName(resolveMerchantName(device.getMerchantId()));
        }
    }

    public String resolveMerchantName(String merchantId) {
        if (merchantId == null || merchantId.isBlank()) {
            return null;
        }
        Merchant merchant = merchantMapper.selectById(merchantId);
        return merchant == null ? null : merchant.getMerchantName();
    }

    public String resolveDeviceName(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return null;
        }
        DeviceInfo device = deviceInfoMapper.selectById(deviceId);
        return device == null ? null : device.getDeviceName();
    }

    /** 优先订单快照，其次设备主数据。 */
    public String resolveDeviceNameForOrder(CabinetOrder order, String deviceId) {
        if (order != null && order.getDeviceName() != null && !order.getDeviceName().isBlank()) {
            return order.getDeviceName();
        }
        return resolveDeviceName(deviceId);
    }
}
