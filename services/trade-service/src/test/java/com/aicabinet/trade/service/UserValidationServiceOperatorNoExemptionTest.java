package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.trade.config.CheckoutProperties;
import com.aicabinet.trade.domain.UserAccount;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.MerchantOpsConfigMapper;
import com.aicabinet.trade.mapper.UserAccountMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R14-D1：2535dcd2（2026-11-11）移除运营号段（userId ≥ 1 亿）三处资金豁免后的回归钉测。
 *
 * <p>🔴 这个类的价值是把「运营号段不再跳过」钉成契约——该豁免曾三次以不同形态回归
 * （免密就绪恒 true / 开门风险闸跳过 / 结算余额预检跳过，见提交 2535dcd2 message），
 * 没有钉测意味着下一次「给运营开绿灯」的改动仍会静默溜进来。
 *
 * <p>三条被钉住的豁免（均已删除，任何一条重新出现 ⇒ 本类变红）：
 * <ol>
 *   <li>{@code validateCanOpenDoor}：运营账号必须过风险闸与欠款闸；</li>
 *   <li>{@code isPasswordFreeReady}：运营账号不再恒 true，按真实签约状态判；</li>
 *   <li>{@code validateSufficientBalanceForCharge}：运营账号余额不足必须抛错。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class UserValidationServiceOperatorNoExemptionTest {

    private static final long OPERATOR_ID = CabinetConstants.OPERATOR_USER_ID_START;

    @Mock private UserInfoMapper userInfoRepository;
    @Mock private UserAccountMapper userAccountRepository;
    @Mock private RiskControlService riskControlService;
    @Mock private PayScoreService payScoreService;
    @Mock private ConsumerPreauthService consumerPreauthService;
    @Mock private CabinetOrderMapper orderRepository;
    @Mock private SystemConfigService systemConfigService;

    private UserValidationService service() {
        // 零公共桩：Mockito 严格桩模式下，公共桩对不触达它们的用例会抛
        // UnnecessaryStubbingException；且后注册的 anyLong() 桩会顶掉用例内更精确的
        // eq() 桩（本类初版实锤：欠款 1 笔被公共 0L 桩放行 ⇒ 404 而非 412）。
        return new UserValidationService(
                userInfoRepository,
                userAccountRepository,
                riskControlService,
                payScoreService,
                new CheckoutProperties(false, 0),
                consumerPreauthService,
                mock(DeviceInfoMapper.class),
                mock(MerchantOpsConfigMapper.class),
                orderRepository,
                systemConfigService);
    }

    @Test
    @DisplayName("🔴 豁免1（开门闸）：运营号段必须过风险闸 + 欠款闸——有欠款必须 412 拒开门")
    void operatorOpenDoor_hitsRiskAndDebtGates() {
        // 欠款闸命中：1 笔 PENDING ⇒ 旧豁免下直接 return（不查任何依赖），新契约必须 412
        when(systemConfigService.getBoolean("debt.block_open_on_pending", true)).thenReturn(true);
        when(orderRepository.countByUserIdAndStatus(anyLong(), any())).thenReturn(1L);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service().validateCanOpenDoor(OPERATOR_ID, "DEV-1", "WECHAT"));

        assertEquals(HttpStatus.PRECONDITION_FAILED, ex.getStatusCode(),
                "运营号段有欠款必须与消费者同口径拒开门");
        verify(riskControlService).validateCanOpenDoor(OPERATOR_ID, "DEV-1");
    }

    @Test
    @DisplayName("🔴 豁免2（免密就绪）：运营号段不再恒 true——按真实签约状态判")
    void operatorIsPasswordFreeReady_followsRealSignatureState() {
        when(userInfoRepository.findById(OPERATOR_ID)).thenReturn(Optional.of(new UserInfo()));
        when(payScoreService.isPasswordFreeReadyForChannel(any(UserInfo.class), any())).thenReturn(false);

        assertFalse(service().isPasswordFreeReady(OPERATOR_ID, "WECHAT"),
                "未签约的运营账号不得被号段豁免成「免密就绪」"
                        + "（旧豁免会让 freezeForOpen 走免密分支跳过预冻结，2026-11-11 实证）");
    }

    @Test
    @DisplayName("🔴 豁免3（结算预检）：运营号段余额不足必须抛 BalanceInsufficientException")
    void operatorInsufficientBalance_throwsAtPrecheck() {
        UserAccount account = new UserAccount();
        account.setUserId(OPERATOR_ID);
        account.setBalanceCents(0);
        account.setFrozenCents(0);
        when(userAccountRepository.findById(OPERATOR_ID)).thenReturn(Optional.of(account));

        assertThrows(BalanceInsufficientException.class,
                () -> service().validateSufficientBalanceForCharge(OPERATOR_ID, 100, 0),
                "运营号段欠款不得拖到扣款才兜底（否则结算落 PENDING 欠款闭环）");
    }

    @Test
    @DisplayName("回归：运营号段余额充足时预检放行（收紧不误伤正常运营单）")
    void operatorSufficientBalance_passes() {
        UserAccount account = new UserAccount();
        account.setUserId(OPERATOR_ID);
        account.setBalanceCents(10_000);
        account.setFrozenCents(0);
        when(userAccountRepository.findById(OPERATOR_ID)).thenReturn(Optional.of(account));

        assertDoesNotThrow(() -> service().validateSufficientBalanceForCharge(OPERATOR_ID, 100, 0));
    }
}
