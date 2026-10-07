package com.aicabinet.trade.service;

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
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * V326（CB-014）：**消费者不再需要实名才能开门**。
 *
 * <p>🔴 这个类的价值不是「能开门」，而是**把「未实名也必须能开门」钉成契约** ——
 * 否则将来任何人都可能觉得「实名一下更安全」而把它加回来，而这正是 2026-10-07
 * 刚被用户拍板移除的东西。
 *
 * <p>依据：① 微信支付分协议 2.3（平台只返「能否使用」，不含身份信息）；
 * ② 五家同行消费者流程都是「扫码 → 开门 → 拿货 → 关门自动扣款」；
 * ③ 商家侧实名保留（提现合规），与消费者侧无关。
 */
@ExtendWith(MockitoExtension.class)
class UserValidationServiceVerifiedNotRequiredTest {

    @Mock private UserInfoMapper userInfoRepository;
    @Mock private UserAccountMapper userAccountRepository;
    @Mock private PayScoreService payScoreService;
    @Mock private ConsumerPreauthService consumerPreauthService;
    @Mock private CabinetOrderMapper orderRepository;
    @Mock private SystemConfigService systemConfigService;

    private UserValidationService service(boolean balanceOnly) {
        // 「无欠款 + 无在途订单」是所有用例的公共前提：mock 默认返回 0，
        // 这里显式 stub 是为了让**每个用例只关心「实名」这一个变量**，不因将来
        // enforceUnpaidDebtBlock 的默认值变化而莫名其妙变红或变绿。
        when(systemConfigService.getBoolean("debt.block_open_on_pending", true)).thenReturn(true);
        when(orderRepository.countByUserIdAndStatus(anyLong(), any())).thenReturn(0L);
        return new UserValidationService(
                userInfoRepository,
                userAccountRepository,
                mock(RiskControlService.class),   // 阶梯/黑名单不在本类断言范围
                payScoreService,
                new CheckoutProperties(balanceOnly, 0),
                consumerPreauthService,
                mock(DeviceInfoMapper.class),
                mock(MerchantOpsConfigMapper.class),
                orderRepository,
                systemConfigService);
    }

    private static UserInfo consumer(boolean verified) {
        UserInfo u = new UserInfo();
        u.setUserId(1001L);
        u.setVerified(verified);
        return u;
    }

    @Test
    @DisplayName("🔴 未实名 + 免密代扣就绪 ⇒ 放行（V326 新契约：实名不再是开门前置）")
    void unverifiedWithPasswordFree_isAllowed() {
        when(userInfoRepository.findById(1001L)).thenReturn(Optional.of(consumer(false)));
        when(payScoreService.isPasswordFreeReadyForChannel(any(UserInfo.class), any()))
                .thenReturn(true);

        assertDoesNotThrow(() -> service(false).validateCanOpenDoor(1001L, "DEV-1", "WECHAT"));
    }

    @Test
    @DisplayName("🔴 未实名 + 无免密 + 余额充足 ⇒ 仍放行（余额兜底不被实名挡）")
    void unverifiedWithBalance_isAllowed() {
        when(userInfoRepository.findById(1001L)).thenReturn(Optional.of(consumer(false)));
        when(payScoreService.isPasswordFreeReadyForChannel(any(UserInfo.class), any()))
                .thenReturn(false);
        // 走余额分支必须同时有账户，否则会撞 ACCOUNT_NOT_FOUND（404）而不是走到余额比较
        when(userAccountRepository.findById(1001L)).thenReturn(Optional.of(new UserAccount()));
        when(consumerPreauthService.resolvePreauthCents("DEV-1")).thenReturn(1000);
        when(consumerPreauthService.availableCents(any())).thenReturn(5000);

        assertDoesNotThrow(() -> service(false).validateCanOpenDoor(1001L, "DEV-1", "WECHAT"));
    }

    @Test
    @DisplayName("未实名 + 无免密 + 余额不足 ⇒ 仍报「余额不足」，**不是**「请先完成实名认证」")
    void unverifiedWithoutBalance_reportsBalanceNotRealName() {
        when(userInfoRepository.findById(1001L)).thenReturn(Optional.of(consumer(false)));
        when(payScoreService.isPasswordFreeReadyForChannel(any(UserInfo.class), any()))
                .thenReturn(false);
        when(userAccountRepository.findById(1001L)).thenReturn(Optional.of(new UserAccount()));
        when(consumerPreauthService.resolvePreauthCents("DEV-1")).thenReturn(1000);
        when(consumerPreauthService.availableCents(any())).thenReturn(0);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service(false).validateCanOpenDoor(1001L, "DEV-1", "WECHAT"));

        String body = String.valueOf(ex.getBody());
        assertTrue(body.contains("余额不足"), "应报余额不足，实际：" + body);
        assertTrue(!body.contains("实名"), "🔴 不应再出现实名相关的拒绝文案，实际：" + body);
    }

    @Test
    @DisplayName("已实名用户不受本次改动影响（回归：原行为仍可用）")
    void verifiedStillWorks() {
        when(userInfoRepository.findById(1001L)).thenReturn(Optional.of(consumer(true)));
        when(payScoreService.isPasswordFreeReadyForChannel(any(UserInfo.class), any()))
                .thenReturn(true);

        assertDoesNotThrow(() -> service(false).validateCanOpenDoor(1001L, "DEV-1", "WECHAT"));
    }

    @Test
    @DisplayName("未注册用户仍应 404（去实名不等于去身份校验）")
    void unknownUser_stillNotFound() {
        when(userInfoRepository.findById(anyLong())).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class,
                () -> service(false).validateCanOpenDoor(1001L, "DEV-1", "WECHAT"));
    }
}
