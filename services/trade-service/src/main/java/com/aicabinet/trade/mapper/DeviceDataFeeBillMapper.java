package com.aicabinet.trade.mapper;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.trade.domain.DeviceDataFeeBill;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DeviceDataFeeBillMapper extends BaseTradeMapper<DeviceDataFeeBill> {

    /** R14-G2：行锁读取（markPaid/voidBill 读后写防并发竞态；SQL 在 DeviceDataFeeBillMapper.xml）。 */
    DeviceDataFeeBill findByIdForUpdateRaw(@Param("billId") Long billId);

    default java.util.Optional<DeviceDataFeeBill> findByIdForUpdate(Long billId) {
        return java.util.Optional.ofNullable(findByIdForUpdateRaw(billId));
    }

    default List<DeviceDataFeeBill> findByDeviceAndMonth(String deviceId, String billMonth) {
        return selectList(Wrappers.<DeviceDataFeeBill>lambdaQuery()
                .eq(DeviceDataFeeBill::getDeviceId, deviceId)
                .eq(DeviceDataFeeBill::getBillMonth, billMonth)
                .orderByAsc(DeviceDataFeeBill::getBillId));
    }

    default Page<DeviceDataFeeBill> searchPage(String billMonth, String status, String deviceId, int page, int size) {
        var q = Wrappers.<DeviceDataFeeBill>lambdaQuery()
                .eq(billMonth != null && !billMonth.isBlank(), DeviceDataFeeBill::getBillMonth, billMonth)
                .eq(status != null && !status.isBlank(), DeviceDataFeeBill::getStatus, status)
                .eq(deviceId != null && !deviceId.isBlank(), DeviceDataFeeBill::getDeviceId, deviceId)
                .orderByDesc(DeviceDataFeeBill::getBillMonth)
                .orderByDesc(DeviceDataFeeBill::getBillId);
        return selectPage(new Page<>(page, size), q);
    }

    default long countNonVoidByDeviceAndMonth(String deviceId, String billMonth) {
        return selectCount(Wrappers.<DeviceDataFeeBill>lambdaQuery()
                .eq(DeviceDataFeeBill::getDeviceId, deviceId)
                .eq(DeviceDataFeeBill::getBillMonth, billMonth)
                .ne(DeviceDataFeeBill::getStatus, CabinetConstants.FEE_BILL_STATUS_VOID));
    }
}
