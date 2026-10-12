package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.DeviceRentalDeposit;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

import java.util.Optional;

@Mapper
public interface DeviceRentalDepositMapper extends BaseTradeMapper<DeviceRentalDeposit> {

    default Optional<DeviceRentalDeposit> findByDevice(String deviceId) {
        return selectList(Wrappers.<DeviceRentalDeposit>lambdaQuery()
                        .eq(DeviceRentalDeposit::getDeviceId, deviceId)
                        .orderByDesc(DeviceRentalDeposit::getDepositId))
                .stream().findFirst();
    }

    /** 原子推进押金余额（deltaCents 负=流出）；影响行数=0 说明余额不足或押金不存在。 */
    @Update("UPDATE device_rental_deposit SET balance_cents = balance_cents + #{deltaCents}, "
            + "updated_at = now() WHERE deposit_id = #{depositId} AND balance_cents + #{deltaCents} >= 0")
    int adjustBalance(Long depositId, long deltaCents);
}
