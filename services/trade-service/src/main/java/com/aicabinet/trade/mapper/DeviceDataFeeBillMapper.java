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

    /**
     * 账单分页：设备范围**下推**（`null`=不限，空集=无可见柜机）。
     * 旧签名只有单值 deviceId，不传即全平台账单（R23X C43 同族）。
     */
    default Page<DeviceDataFeeBill> searchPage(String billMonth, String status,
                                               java.util.Collection<String> deviceIds, int page, int size) {
        if (deviceIds != null && deviceIds.isEmpty()) {
            return new Page<>(page, size);
        }
        var q = Wrappers.<DeviceDataFeeBill>lambdaQuery()
                .eq(billMonth != null && !billMonth.isBlank(), DeviceDataFeeBill::getBillMonth, billMonth)
                .eq(status != null && !status.isBlank(), DeviceDataFeeBill::getStatus, status)
                .orderByDesc(DeviceDataFeeBill::getBillMonth)
                .orderByDesc(DeviceDataFeeBill::getBillId);
        if (deviceIds != null) {
            q.in(DeviceDataFeeBill::getDeviceId, deviceIds);
        }
        return selectPage(new Page<>(page, size), q);
    }

    default long countNonVoidByDeviceAndMonth(String deviceId, String billMonth) {
        return selectCount(Wrappers.<DeviceDataFeeBill>lambdaQuery()
                .eq(DeviceDataFeeBill::getDeviceId, deviceId)
                .eq(DeviceDataFeeBill::getBillMonth, billMonth)
                .ne(DeviceDataFeeBill::getStatus, CabinetConstants.FEE_BILL_STATUS_VOID));
    }
}
