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
 * 展示快照写入：订单/会话/报修的设备名与商户名在业务写入时固化。
 * 订单快照一经写入由 DB 触发器禁止再改；读路径不得再 join 现名覆盖历史。
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

    /**
     * 结算建单：强制从 device_info 写入订单展示快照（覆盖 session 预填）。
     * 之后只读快照列，禁止再调用本方法改已落库快照。
     */
    public void stampOrderSnapshot(CabinetOrder order) {
        if (order == null || order.getDeviceId() == null || order.getDeviceId().isBlank()) {
            return;
        }
        DeviceInfo device = deviceInfoMapper.selectById(order.getDeviceId());
        if (device == null) {
            return;
        }
        if (device.getDeviceName() != null && !device.getDeviceName().isBlank()) {
            order.setDeviceName(device.getDeviceName());
        }
        if (device.getMerchantId() != null && !device.getMerchantId().isBlank()) {
            order.setMerchantId(device.getMerchantId());
            String merchantName = resolveMerchantName(device.getMerchantId());
            if (merchantName != null && !merchantName.isBlank()) {
                order.setMerchantName(merchantName);
            }
        }
    }

    /**
     * 仅补空：用于历史单回填迁移。正常结算请用 {@link #stampOrderSnapshot}。
     * 读模型装配不得调用。
     */
    public void applyOrderSnapshot(CabinetOrder order) {
        if (order == null || order.getDeviceId() == null || order.getDeviceId().isBlank()) {
            return;
        }
        boolean needDeviceName = order.getDeviceName() == null || order.getDeviceName().isBlank();
        boolean needMerchantId = order.getMerchantId() == null || order.getMerchantId().isBlank();
        boolean needMerchantName = order.getMerchantName() == null || order.getMerchantName().isBlank();
        if (!needDeviceName && !needMerchantId && !needMerchantName) {
            return;
        }
        DeviceInfo device = deviceInfoMapper.selectById(order.getDeviceId());
        if (device == null) {
            return;
        }
        if (needDeviceName && device.getDeviceName() != null && !device.getDeviceName().isBlank()) {
            order.setDeviceName(device.getDeviceName());
        }
        if (needMerchantId && device.getMerchantId() != null && !device.getMerchantId().isBlank()) {
            order.setMerchantId(device.getMerchantId());
        }
        if (needMerchantName) {
            String mid = order.getMerchantId() != null ? order.getMerchantId() : device.getMerchantId();
            if (mid != null) {
                order.setMerchantName(resolveMerchantName(mid));
            }
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

    /** 优先订单快照，其次设备主数据（非订单列表路径慎用）。 */
    public String resolveDeviceNameForOrder(CabinetOrder order, String deviceId) {
        if (order != null && order.getDeviceName() != null && !order.getDeviceName().isBlank()) {
            return order.getDeviceName();
        }
        return resolveDeviceName(deviceId);
    }
}
