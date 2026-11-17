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

    /**
     * 按「范围下推集合」取押金行（MA-316，2026-11-17）。
     *
     * <p>契约与 {@code DeviceRentalBillMapper#searchPage} 逐字一致：{@code null}=不限（全局账号），
     * 空集=一台都不给看（直接空表，绝不退化成「不加谓词」）。押金金额/事件是商户级敏感数据，
     * 域受限账号必须只见自己范围内柜机的行。</p>
     */
    default java.util.List<DeviceRentalDeposit> findScoped(java.util.Collection<String> deviceIds) {
        if (deviceIds != null && deviceIds.isEmpty()) {
            return java.util.List.of();
        }
        var q = Wrappers.<DeviceRentalDeposit>lambdaQuery();
        if (deviceIds != null) {
            q.in(DeviceRentalDeposit::getDeviceId, deviceIds);
        }
        q.orderByDesc(DeviceRentalDeposit::getDepositId);
        return selectList(q);
    }

    /** 原子推进押金余额（deltaCents 负=流出）；影响行数=0 说明余额不足或押金不存在。 */
    @Update("UPDATE device_rental_deposit SET balance_cents = balance_cents + #{deltaCents}, "
            + "updated_at = now() WHERE deposit_id = #{depositId} AND balance_cents + #{deltaCents} >= 0")
    int adjustBalance(Long depositId, long deltaCents);
}
