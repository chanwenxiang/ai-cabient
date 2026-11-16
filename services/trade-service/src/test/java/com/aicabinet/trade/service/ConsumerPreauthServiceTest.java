package com.aicabinet.trade.service;

import com.aicabinet.trade.config.CheckoutProperties;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.mapper.ConsumerPreauthHoldMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.mapper.UserAccountMapper;
import com.aicabinet.trade.support.CommissionParamBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * MA-178：设备押金是开门预授权的**第二来源**，读侧原先只有
 * `Math.min(Integer.MAX_VALUE, deposit)` —— 没有业务上界，荒谬值会直接冻结消费者余额。
 * 现在与全局键 checkout.preauth_cents 共用 {@link CommissionParamBounds#PREAUTH_CENTS_CEILING}，
 * 超界的存量行降级回落配置默认（与全局键超限同款处置）。
 */
@ExtendWith(MockitoExtension.class)
class ConsumerPreauthServiceTest {

    @Mock private UserAccountMapper accountRepository;
    @Mock private ShoppingSessionMapper sessionRepository;
    @Mock private DeviceInfoMapper deviceRepository;
    @Mock private ConsumerPreauthHoldMapper holdRepository;
    @Mock private CheckoutProperties checkoutProperties;
    @Mock private BalanceLedgerService balanceLedgerService;
    @Mock private SystemConfigService systemConfigService;
    @Mock private DistributedLockService distributedLockService;

    private ConsumerPreauthService service;

    @BeforeEach
    void setUp() {
        service = new ConsumerPreauthService(accountRepository, sessionRepository, deviceRepository,
                holdRepository, checkoutProperties, balanceLedgerService, systemConfigService,
                distributedLockService);
    }

    @BeforeEach
    void stubConfigUnset() {
        // Mockito 对 int 返回类型默认给 0；MA-294 后 0=「关闭预授权」是显式语义 ⇒
        // 每个用例若没显式 stub getInt 会静默走关闭路径。类级默认 stub 为 -1（未配置哨兵）。
        org.mockito.Mockito.when(systemConfigService.getInt(
                org.mockito.ArgumentMatchers.eq(
                        com.aicabinet.trade.service.SystemConfigService.CHECKOUT_PREAUTH_CENTS),
                org.mockito.ArgumentMatchers.eq(-1))).thenReturn(-1);
    }

    private static DeviceInfo device(long depositCents) {
        DeviceInfo d = new DeviceInfo();
        d.setDeviceId("D-1");
        d.setDepositCents(depositCents);
        return d;
    }

    @Test
    void deviceDepositWithinCeiling_isUsedAsIs() {
        when(deviceRepository.findById("D-1")).thenReturn(Optional.of(device(12_000L)));

        assertEquals(12_000, service.resolvePreauthCents("D-1"));
        // MA-294 后 0 判据前移到方法最前 ⇒ getInt 一定先于设备分支被调用（新契约）；
        // 设备命中时仍不得触碰 checkoutProperties 默认值。
        verify(systemConfigService).getInt(
                com.aicabinet.trade.service.SystemConfigService.CHECKOUT_PREAUTH_CENTS, -1);
        verifyNoInteractions(checkoutProperties);
    }

    @Test
    void deviceDepositOverCeiling_fallsBackToConfigDefault() {
        when(deviceRepository.findById("D-1"))
                .thenReturn(Optional.of(device(CommissionParamBounds.PREAUTH_CENTS_CEILING + 1L)));
        // 未配置哨兵由 @BeforeEach stubConfigUnset 默认提供（-1），此处不再重复 stub
        when(checkoutProperties.resolvePreauthCents()).thenReturn(300);

        assertEquals(300, service.resolvePreauthCents("D-1"),
                "超上界的存量值不能被拿去冻结余额，必须回落配置默认");
    }

    @Test
    void deviceDepositExactlyAtCeiling_isAccepted() {
        when(deviceRepository.findById("D-1"))
                .thenReturn(Optional.of(device((long) CommissionParamBounds.PREAUTH_CENTS_CEILING)));

        assertEquals(CommissionParamBounds.PREAUTH_CENTS_CEILING,
                service.resolvePreauthCents("D-1"));
    }

    /** MA-294（拍板：0 压倒一切）：全局键 0=关闭预授权，必须压倒设备押金分支。 */
    @Test
    void globalZero_overridesDeviceDeposit() {
        org.mockito.Mockito.when(systemConfigService
                .getInt(com.aicabinet.trade.service.SystemConfigService.CHECKOUT_PREAUTH_CENTS, -1))
                .thenReturn(0);

        assertEquals(0, service.resolvePreauthCents("CAB-1"),
                "全局 0（关闭预授权）必须压倒设备押金");
        // 0 压倒一切 ⇒ 设备分支根本不该被走到（stub 会被判 UnnecessaryStubbing，
        // 改用 never() 验证强化语义）
        org.mockito.Mockito.verify(deviceRepository,
                org.mockito.Mockito.never()).findById(org.mockito.ArgumentMatchers.anyString());
    }
}
