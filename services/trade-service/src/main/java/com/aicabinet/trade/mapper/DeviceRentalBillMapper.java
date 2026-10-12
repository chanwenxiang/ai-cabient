package com.aicabinet.trade.mapper;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.trade.domain.DeviceRentalBill;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface DeviceRentalBillMapper extends BaseTradeMapper<DeviceRentalBill> {

    default long countNonVoidByDeviceAndMonth(String deviceId, String billMonth) {
        return selectCount(Wrappers.<DeviceRentalBill>lambdaQuery()
                .eq(DeviceRentalBill::getDeviceId, deviceId)
                .eq(DeviceRentalBill::getBillMonth, billMonth)
                .ne(DeviceRentalBill::getStatus, CabinetConstants.FEE_BILL_STATUS_VOID));
    }

    default Page<DeviceRentalBill> searchPage(String billMonth, String status, String deviceId, int page, int size) {
        var q = Wrappers.<DeviceRentalBill>lambdaQuery()
                .eq(billMonth != null && !billMonth.isBlank(), DeviceRentalBill::getBillMonth, billMonth)
                .eq(status != null && !status.isBlank(), DeviceRentalBill::getStatus, status)
                .eq(deviceId != null && !deviceId.isBlank(), DeviceRentalBill::getDeviceId, deviceId)
                .orderByDesc(DeviceRentalBill::getBillMonth)
                .orderByDesc(DeviceRentalBill::getBillId);
        return selectPage(new Page<>(page, size), q);
    }

    /** 已出账期早于或等于指定账期且未付清的账单（欠租判定用）。 */
    default List<DeviceRentalBill> findUnpaidUpTo(String billMonth) {
        return selectList(Wrappers.<DeviceRentalBill>lambdaQuery()
                .eq(DeviceRentalBill::getStatus, CabinetConstants.FEE_BILL_STATUS_UNPAID)
                .le(DeviceRentalBill::getBillMonth, billMonth)
                .orderByAsc(DeviceRentalBill::getBillMonth));
    }

    default List<DeviceRentalBill> findUnpaidByDevice(String deviceId) {
        return selectList(Wrappers.<DeviceRentalBill>lambdaQuery()
                .eq(DeviceRentalBill::getDeviceId, deviceId)
                .eq(DeviceRentalBill::getStatus, CabinetConstants.FEE_BILL_STATUS_UNPAID));
    }
}
