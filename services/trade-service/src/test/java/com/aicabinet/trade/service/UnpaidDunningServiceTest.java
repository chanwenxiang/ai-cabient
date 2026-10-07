package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.UnpaidDunningRecord;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.UnpaidDunningRecordMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V325：欠款催缴（CB-003 短信通道 / CB-011 惩罚阶梯）。
 *
 * <p>🔴 核心价值有两点，都不是「代码跑通」：
 * <ol>
 *   <li><b>阶梯判据按竞品取值</b>：累计 3 次 → 冻结免密先享（抄共享充电宝范式 CB-011），
 *       阈值抽成生产静态方法 {@code tierFor}，测试直接调它 ⇒ 不复制实现、不会假绿。</li>
 *   <li><b>催缴失败绝不能升级成欠款处理失败</b>：它跑在欠款链路里。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class UnpaidDunningServiceTest {

    @Mock private UnpaidDunningRecordMapper dunningRepository;
    @Mock private UserInfoMapper userInfoRepository;
    @Mock private NotificationService notificationService;

    private UnpaidDunningService service() {
        return new UnpaidDunningService(dunningRepository, userInfoRepository, notificationService);
    }

    private static UnpaidDunningRecord existing(long userId, int count, int tier) {
        UnpaidDunningRecord r = new UnpaidDunningRecord();
        r.setUserId(userId);
        r.setUnpaidCount(count);
        r.setTier(tier);
        return r;
    }

    // ── 阶梯判据（CB-011）────────────────────────────────────────────────

    @Test
    @DisplayName("阶梯判据按竞品取值：1 次仅提醒，2 次限制额度，≥3 次冻结免密先享")
    void tierFor_matchesCompetitorThresholds() {
        // CB-011 抄共享充电宝：累计 3 次进「谨慎名单」，此后要求双倍押金/冻结该平台免密能力
        assertEquals(UnpaidDunningService.TIER_REMIND, UnpaidDunningService.tierFor(1));
        assertEquals(UnpaidDunningService.TIER_RESTRICT, UnpaidDunningService.tierFor(2));
        assertEquals(UnpaidDunningService.TIER_RESTRICT_PREAUTH, UnpaidDunningService.tierFor(3));
        // 继续累加不应回落 —— 阶是单调的
        assertEquals(UnpaidDunningService.TIER_RESTRICT_PREAUTH, UnpaidDunningService.tierFor(10));
        assertEquals(0, UnpaidDunningService.tierFor(0));
    }

    @Test
    @DisplayName("阈值常量与判据口径一致（防止改了常量忘了改判据）")
    void thresholdsAlignWithTierFor() {
        assertEquals(UnpaidDunningService.TIER_RESTRICT,
                UnpaidDunningService.tierFor(UnpaidDunningService.THRESHOLD_RESTRICT));
        assertEquals(UnpaidDunningService.TIER_RESTRICT_PREAUTH,
                UnpaidDunningService.tierFor(UnpaidDunningService.THRESHOLD_RESTRICT_PREAUTH));
        // 下界检查：低于阈值不能误判为更高阶
        assertEquals(UnpaidDunningService.TIER_REMIND,
                UnpaidDunningService.tierFor(UnpaidDunningService.THRESHOLD_RESTRICT - 1));
    }

    // ── 阶梯递增 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("累计欠款递增：count+1 且 tier 按新值落库")
    void escalateIncrementsCountAndTier() {
        when(dunningRepository.findByIdForUpdate(1001L))
                .thenReturn(Optional.of(existing(1001L, 2, UnpaidDunningService.TIER_RESTRICT)));

        int tier = service().recordUnpaidAndEscalate(1001L);

        assertEquals(UnpaidDunningService.TIER_RESTRICT_PREAUTH, tier);
        ArgumentCaptor<UnpaidDunningRecord> cap = ArgumentCaptor.forClass(UnpaidDunningRecord.class);
        verify(dunningRepository).save(cap.capture());
        assertEquals(3, cap.getValue().getUnpaidCount());
        assertEquals(UnpaidDunningService.TIER_RESTRICT_PREAUTH, cap.getValue().getTier());
    }

    @Test
    @DisplayName("🔴 阶梯记不上绝不抛异常（它跑在欠款链路里，抛异常会把欠款升级成处理失败）")
    void escalate_neverPropagates() {
        when(dunningRepository.findByIdForUpdate(1001L))
                .thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(() -> service().recordUnpaidAndEscalate(1001L));
        verify(notificationService, never()).notifyConsumer(anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("userId 为 null ⇒ 不写库（无主订单不产生阶梯）")
    void escalate_nullUserIdIsNoop() {
        assertEquals(0, service().recordUnpaidAndEscalate(null));
        verify(dunningRepository, never()).save(any());
    }

    @Test
    @DisplayName("首次欠款 ⇒ 新建记录，count=1")
    void escalate_firstTimeCreatesRecord() {
        when(dunningRepository.findByIdForUpdate(2002L)).thenReturn(Optional.empty());

        int tier = service().recordUnpaidAndEscalate(2002L);

        assertEquals(UnpaidDunningService.TIER_REMIND, tier);
        ArgumentCaptor<UnpaidDunningRecord> cap = ArgumentCaptor.forClass(UnpaidDunningRecord.class);
        verify(dunningRepository).save(cap.capture());
        assertEquals(2002L, cap.getValue().getUserId());
        assertEquals(1, cap.getValue().getUnpaidCount());
    }

    @Test
    @DisplayName("达到冻结档 ⇒ 额外发限制生效通知")
    void escalate_notifiesWhenRestricted() {
        when(dunningRepository.findByIdForUpdate(1001L))
                .thenReturn(Optional.of(existing(1001L, 2, UnpaidDunningService.TIER_RESTRICT)));

        service().recordUnpaidAndEscalate(1001L);

        verify(notificationService).notifyConsumer(eq(1001L), eq("unpaid_order_blacklisted"),
                any(), eq("ORDER"), eq(null));
    }

    // ── 还清降级（自愈）─────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 欠款还清 ⇒ 阶梯归零（否则等于永久失信，违反 CB-011 的最小化限制原则）")
    void cleared_resetsTier() {
        when(dunningRepository.findByIdForUpdate(1001L))
                .thenReturn(Optional.of(existing(1001L, 3, UnpaidDunningService.TIER_RESTRICT_PREAUTH)));

        service().onUnpaidCleared(1001L);

        ArgumentCaptor<UnpaidDunningRecord> cap = ArgumentCaptor.forClass(UnpaidDunningRecord.class);
        verify(dunningRepository).save(cap.capture());
        assertEquals(0, cap.getValue().getUnpaidCount());
        assertEquals(0, cap.getValue().getTier());
    }

    @Test
    @DisplayName("isPreauthRestricted 反映阶梯：3 次以上才算限制免密")
    void preauthRestrictedReflectsTier() {
        UnpaidDunningService svc = service();
        when(dunningRepository.findByUserId(1001L)).thenReturn(Optional.empty());
        assertFalse(svc.isPreauthRestricted(1001L));

        when(dunningRepository.findByUserId(1002L))
                .thenReturn(Optional.of(existing(1002L, 3, UnpaidDunningService.TIER_RESTRICT_PREAUTH)));
        assertTrue(svc.isPreauthRestricted(1002L));
    }

    // ── 短信催缴（CB-003）──────────────────────────────────────────────

    @Test
    @DisplayName("催缴 ⇒ 走 unpaid_order_dunning 模板（channels 含 SMS 才会真发短信）")
    void remindBySms_usesDunningTemplate() {
        UserInfo user = new UserInfo();
        user.setUserId(1001L);
        user.setPhoneNumber("13800000000");
        when(userInfoRepository.findById(1001L)).thenReturn(Optional.of(user));
        when(dunningRepository.findByUserId(1001L)).thenReturn(Optional.empty());

        CabinetOrder order = new CabinetOrder();
        order.setOrderId("ORD-1");
        order.setUserId(1001L);
        order.setTotalAmountCents(1334);

        UnpaidDunningService.DunningResult r = service().remindBySms(order, false);

        assertTrue(r.smsSent());
        // 金额以「元」进模板，与站内信一致
        verify(notificationService).notifyConsumer(eq(1001L), eq("unpaid_order_dunning"),
                eq(Map.of("orderId", "ORD-1", "amount", "13.34")), eq("ORDER"), eq("ORD-1"));
    }

    @Test
    @DisplayName("24h 内已催过 ⇒ 不重复发（force=false），防短信轰炸")
    void remindBySms_throttled() {
        UserInfo user = new UserInfo();
        user.setUserId(1001L);
        user.setPhoneNumber("13800000000");
        UnpaidDunningRecord rec = existing(1001L, 1, UnpaidDunningService.TIER_REMIND);
        rec.setLastRemindedAt(java.time.Instant.now());
        when(userInfoRepository.findById(1001L)).thenReturn(Optional.of(user));
        when(dunningRepository.findByUserId(1001L)).thenReturn(Optional.of(rec));

        CabinetOrder order = new CabinetOrder();
        order.setOrderId("ORD-1");
        order.setUserId(1001L);
        order.setTotalAmountCents(1334);

        UnpaidDunningService.DunningResult r = service().remindBySms(order, false);

        assertFalse(r.smsSent());
        verify(notificationService, never()).notifyConsumer(anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("人工催缴 force=true ⇒ 跳过 24h 节流（即时生效）")
    void remindBySms_forceIgnoresThrottle() {
        UserInfo user = new UserInfo();
        user.setUserId(1001L);
        user.setPhoneNumber("13800000000");
        UnpaidDunningRecord rec = existing(1001L, 1, UnpaidDunningService.TIER_REMIND);
        rec.setLastRemindedAt(java.time.Instant.now());
        when(userInfoRepository.findById(1001L)).thenReturn(Optional.of(user));
        when(dunningRepository.findByUserId(1001L)).thenReturn(Optional.of(rec));

        CabinetOrder order = new CabinetOrder();
        order.setOrderId("ORD-1");
        order.setUserId(1001L);
        order.setTotalAmountCents(1334);

        assertTrue(service().remindBySms(order, true).smsSent());
    }

    @Test
    @DisplayName("🔴 无手机号 ⇒ 不发并给出可操作提示（登录需短信验证码，正常不该发生）")
    void remindBySms_noPhoneSkips() {
        UserInfo user = new UserInfo();
        user.setUserId(1001L);
        when(userInfoRepository.findById(1001L)).thenReturn(Optional.of(user));

        CabinetOrder order = new CabinetOrder();
        order.setOrderId("ORD-1");
        order.setUserId(1001L);
        order.setTotalAmountCents(1334);

        UnpaidDunningService.DunningResult r = service().remindBySms(order, false);

        assertFalse(r.smsSent());
        assertTrue(r.message().contains("手机号"));
        verify(notificationService, never()).notifyConsumer(anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("催缴发送异常 ⇒ 不外抛（欠款链路不能被短信渠道拖垮）")
    void remindBySms_neverPropagates() {
        UserInfo user = new UserInfo();
        user.setUserId(1001L);
        user.setPhoneNumber("13800000000");
        when(userInfoRepository.findById(1001L)).thenReturn(Optional.of(user));
        when(dunningRepository.findByUserId(1001L)).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("channel down")).when(notificationService)
                .notifyConsumer(anyLong(), any(), any(), any(), any());

        CabinetOrder order = new CabinetOrder();
        order.setOrderId("ORD-1");
        order.setUserId(1001L);
        order.setTotalAmountCents(1334);

        assertDoesNotThrow(() -> service().remindBySms(order, false));
    }
}
