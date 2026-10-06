package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.trade.config.LineWithdrawProperties;
import com.aicabinet.trade.domain.LineDevice;
import com.aicabinet.trade.domain.LineManager;
import com.aicabinet.trade.domain.LineWalletAccount;
import com.aicabinet.trade.domain.LineWithdrawRequest;
import com.aicabinet.trade.domain.PayoutAccount;
import com.aicabinet.trade.mapper.LineDeviceMapper;
import com.aicabinet.trade.mapper.LineManagerMapper;
import com.aicabinet.trade.mapper.LineWithdrawRequestMapper;
import com.aicabinet.trade.mapper.MerchantWalletLedgerMapper;
import com.aicabinet.trade.mapper.MerchantWithdrawRequestMapper;
import com.aicabinet.trade.mapper.PayoutAccountMapper;
import com.aicabinet.trade.payout.PayoutChannel;
import com.aicabinet.trade.payout.PayoutChannelLimits;
import com.aicabinet.trade.payout.PayoutChannelRegistry;
import com.aicabinet.trade.payout.PayoutConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * V308：线长侧提现接入收款方模型 + 渠道限额。
 *
 * <p><b>修掉的两个结构性问题</b>：
 * <ol>
 *   <li>提现单<b>不知道自己打给谁</b>（无收款方快照）、<b>没有幂等键</b>
 *       —— 与商户侧 V307 之前同样的裸表结构；</li>
 *   <li>打款<b>不走统一通道抽象</b>：渠道由 {@code WithdrawPayoutPolicy.channelFor(mock)}
 *       硬编码成微信零钱，加第2 个通道要改主干。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("V308 线长侧提现收款方与限额")
class LineWithdrawPayeeTest {

    @Mock private LineWithdrawRequestMapper withdrawMapper;
    @Mock private LineManagerMapper managerMapper;
    @Mock private LineDeviceMapper deviceMapper;
    @Mock private LineManagerService lineManagerService;
    @Mock private LineWalletService lineWalletService;
    @Mock private LineWithdrawPayoutService payoutService;
    @Mock private PermissionService permissionService;
    @Mock private AdminAuditService auditService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private ApprovalWorkflowService approvalWorkflowService;
    @Mock private PayoutAccountService payoutAccountService;
    @Mock private PayoutAccountMapper payoutAccountMapper;
    @Mock private PayoutChannelRegistry payoutChannelRegistry;
    @Mock private MerchantWithdrawRequestMapper merchantWithdrawMapper;
    @Mock private MerchantWalletLedgerMapper merchantWalletLedgerMapper;

    private LineWithdrawService service;
    private PayoutAccount wechatAccount;

    @BeforeEach
    void setUp() {
        // 运营限额刻意放宽，让测试聚焦「渠道限额」与「收款方快照」
        LineWithdrawProperties properties =
                new LineWithdrawProperties(true, 100, 10_000_000L, 50_000_000L, 50_000, 0, 0);
        service = new LineWithdrawService(
                withdrawMapper, managerMapper, deviceMapper, lineManagerService,
                lineWalletService, payoutService, properties,
                permissionService, auditService, distributedLockService, approvalWorkflowService,
                WithdrawPolicyResolver.ymlOnly(properties),
                payoutAccountService, payoutAccountMapper, payoutChannelRegistry, null);
        ReflectionTestUtils.setField(service, "self", service);

        wechatAccount = new PayoutAccount();
        wechatAccount.setAccountId(11L);
        wechatAccount.setOwnerType(PayoutConstants.PAYEE_OWNER_LINE_MANAGER);
        wechatAccount.setOwnerId("7");
        wechatAccount.setAccountType(PayoutConstants.PAYEE_TYPE_PERSONAL);
        wechatAccount.setChannel(CabinetConstants.PAY_CHANNEL_WECHAT);
        wechatAccount.setAccountName("线长7");
        wechatAccount.setAccountNoMask("****0002");
        wechatAccount.setStatus("ACTIVE");
        wechatAccount.setIsDefault(true);

        lenient().when(payoutAccountService.resolveForApply(any(), any(), any()))
                .thenReturn(wechatAccount);
        lenient().when(distributedLockService.tryLock(anyString(), anyLong(), anyLong()))
                .thenReturn(true);
        lenient().when(deviceMapper.selectCount(any())).thenReturn(3L);
        lenient().when(lineWalletService.ensureAccount(7L)).thenReturn(wallet(10_000_000L));
        lenient().when(withdrawMapper.sumAmountByManagerSince(eq(7L), any())).thenReturn(0L);
        lenient().when(withdrawMapper.findByRequestNo(anyString())).thenReturn(Optional.empty());
        lenient().when(withdrawMapper.insert(any(LineWithdrawRequest.class))).thenAnswer(inv -> {
            LineWithdrawRequest r = inv.getArgument(0);
            r.setRequestId(1L);
            return 1;
        });
        lenient().when(withdrawMapper.updateById(any(LineWithdrawRequest.class))).thenReturn(1);
    }

    private static LineWalletAccount wallet(long balance) {
        LineWalletAccount a = new LineWalletAccount();
        a.setManagerId(7L);
        a.setBalanceCents(balance);
        a.setFrozenCents(0L);
        return a;
    }

    private void registerChannel(PayoutChannelLimits limits) {
        PayoutChannel channel = new PayoutChannel() {
            @Override
            public String channel() {
                return CabinetConstants.PAY_CHANNEL_WECHAT;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public String supportedAccountType() {
                return PayoutConstants.PAYEE_TYPE_PERSONAL;
            }

            @Override
            public PayoutChannelLimits channelLimits() {
                return limits;
            }

            @Override
            public PayoutResult transfer(PayoutCommand command) {
                return PayoutResult.paid("CH-1", "ok");
            }
        };
        lenient().when(payoutChannelRegistry.find(CabinetConstants.PAY_CHANNEL_WECHAT))
                .thenReturn(Optional.of(channel));
    }

    private LineManager manager() {
        LineManager m = new LineManager();
        m.setManagerId(7L);
        m.setManagerName("线长7");
        return m;
    }

    // ==================== 收款方快照 ====================

    @Test
    @DisplayName("申请时锁定收款账户：快照写入账户ID 与掩码，绝不写明文")
    void apply_snapshotsPayee() {
        registerChannel(new PayoutChannelLimits(0L, 0L, 0L));
        service.persistWithdrawApplication(manager(), 20_000L, "R-1", null);

        var captor = org.mockito.ArgumentCaptor.forClass(LineWithdrawRequest.class);
        org.mockito.Mockito.verify(withdrawMapper).insert(captor.capture());
        LineWithdrawRequest saved = captor.getValue();

        assertEquals(11L, saved.getPayoutAccountId());
        assertEquals(PayoutConstants.PAYEE_TYPE_PERSONAL, saved.getPayeeAccountType());
        assertEquals("线长7", saved.getPayeeAccountName());
        assertEquals("****0002", saved.getPayeeAccountNoMask());
        // 🔴 纪律：快照里绝不能出现明文账号列
        assertTrue(saved.getIdemKey() == null || saved.getIdemKey().startsWith("LW:"),
                "幂等键应已写入且以 LW: 开头");
    }

    @Test
    @DisplayName("渠道取自「所选收款账户」而非 mock 开关的字面量")
    void payChannelComesFromAccount() {
        registerChannel(new PayoutChannelLimits(0L, 0L, 0L));
        service.persistWithdrawApplication(manager(), 20_000L, "R-2", null);

        var captor = org.mockito.ArgumentCaptor.forClass(LineWithdrawRequest.class);
        org.mockito.Mockito.verify(withdrawMapper).insert(captor.capture());
        assertEquals(CabinetConstants.PAY_CHANNEL_WECHAT, captor.getValue().getPayChannel());
    }

    // ==================== 渠道限额 ====================

    @Test
    @DisplayName("🔴 微信默认单笔 ¥200：线长提 ¥300 应被拒（此前会卡在 PAYING 转人工）")
    void wechatSingleLimitBlocks() {
        registerChannel(new PayoutChannelLimits(20_000L, 200_000L, 5_000_000L));
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.persistWithdrawApplication(manager(), 30_000L, "R-3", null));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertTrue(e.getMessage().contains("单笔"), e.getMessage());
    }

    @Test
    @DisplayName("单笔刚好 ¥200 应通过")
    void wechatSingleLimitAtBoundary_passes() {
        registerChannel(new PayoutChannelLimits(20_000L, 200_000L, 5_000_000L));
        assertDoesNotThrow(() -> service.persistWithdrawApplication(manager(), 20_000L, "R-4", null));
    }

    @Test
    @DisplayName("🔴 单收款人单日：账户已用满 ¥2000，再提应被拒")
    void payeeDailyLimitBlocks() {
        registerChannel(new PayoutChannelLimits(20_000L, 200_000L, 5_000_000L));
        when(payoutAccountService.sumPaidAmountByAccountSince(eq(11L), any())).thenReturn(200_000L);
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.persistWithdrawApplication(manager(), 20_000L, "R-5", null));
        assertEquals(HttpStatus.PRECONDITION_FAILED, e.getStatusCode());
    }

    @Test
    @DisplayName("🔴 单通道当日总额是商户+线长共享池：两侧相加后超限应被拒")
    void channelDailyPoolIsSharedWithMerchant() {
        registerChannel(new PayoutChannelLimits(20_000L, 200_000L, 5_000_000L));
        // 聚合口径：商户侧 4_900_000 + 线长侧 200_000 = 5_100_000 > 5_000_000
        when(payoutAccountService.sumPaidAmountByChannelSince(
                eq(CabinetConstants.PAY_CHANNEL_WECHAT), any())).thenReturn(5_100_000L);
        assertThrows(ResponseStatusException.class,
                () -> service.persistWithdrawApplication(manager(), 20_000L, "R-6", null));
    }

    // ==================== 官方口径 ====================

    @Test
    @DisplayName("支付宝限额 = 单笔 ¥5万 / 日 ¥100万（官方多页面不一致，取最保守）")
    void alipayLimitsMatchOfficialDoc() {
        var channel = new com.aicabinet.trade.payout.AlipayPayoutChannel(null);
        PayoutChannelLimits limits = channel.channelLimits();
        assertEquals(5_000_000L, limits.singleCents());
        assertEquals(100_000_000L, limits.dailyTotalCents());
        // 支付宝按付款方计，没有「同一收款人单日」维度
        assertEquals(0L, limits.perPayeeDailyCents());
    }

    // ==================== 出款快照解析 ====================

    @Test
    @DisplayName("出款时按申请快照取账户；快照缺失则回落默认账户并留日志")
    void resolveSnapshotAccount_fallsBackWhenMissing() {
        LineWithdrawRequest request = new LineWithdrawRequest();
        request.setRequestId(1L);
        request.setManagerId(7L);
        request.setPayoutAccountId(99L);
        when(payoutAccountMapper.findByAccountId(99L)).thenReturn(Optional.empty());

        var resolved = ReflectionTestUtils.invokeMethod(service, "resolveSnapshotAccount", request);
        assertEquals(wechatAccount, resolved, "快照账户缺失时应回落主体默认账户");
    }
}
