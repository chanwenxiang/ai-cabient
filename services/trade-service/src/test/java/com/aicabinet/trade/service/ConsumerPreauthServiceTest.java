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
        verifyNoInteractions(systemConfigService, checkoutProperties);
    }

    @Test
    void deviceDepositOverCeiling_fallsBackToConfigDefault() {
        when(deviceRepository.findById("D-1"))
                .thenReturn(Optional.of(device(CommissionParamBounds.PREAUTH_CENTS_CEILING + 1L)));
        when(systemConfigService.getInt(anyString(), anyInt())).thenReturn(-1); // 未配置哨兵
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
}
