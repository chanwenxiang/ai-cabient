package com.aicabinet.trade.service;

import com.aicabinet.common.dto.CouponDto;
import com.aicabinet.common.dto.MemberLevelRuleDto;
import com.aicabinet.common.dto.MemberPointsSummaryDto;
import com.aicabinet.common.dto.PointsRedeemItemDto;
import com.aicabinet.trade.domain.CouponDefinition;
import com.aicabinet.trade.domain.Member;
import com.aicabinet.trade.domain.MemberPointsLog;
import com.aicabinet.trade.domain.PointsRedeemItem;
import com.aicabinet.trade.mapper.CouponDefinitionMapper;
import com.aicabinet.trade.mapper.MemberMapper;
import com.aicabinet.trade.mapper.MemberPointsLogMapper;
import com.aicabinet.trade.mapper.PointsRedeemItemMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PointsRedeemServiceTest {

    @Mock private MemberService memberService;
    @Mock private MemberMapper memberRepository;
    @Mock private MemberPointsLogMapper pointsLogRepository;
    @Mock private PointsRedeemItemMapper redeemItemRepository;
    @Mock private CouponService couponService;
    @Mock private CouponDefinitionMapper couponDefinitionRepository;
    @Mock private DistributedLockService distributedLockService;

    @InjectMocks private PointsRedeemService service;

    private void stubRedeemLocks(long userId, long itemId) {
        lenient().when(distributedLockService.tryLock(
                eq(MemberService.memberUserLockKey(userId)), eq(60L), eq(5L))).thenReturn(true);
        lenient().when(distributedLockService.tryLock(
                eq("redeem:item:" + itemId + ":user:" + userId), eq(30L), eq(3L))).thenReturn(true);
    }

    private static Member member(int available, int total, int used) {
        Member m = new Member();
        m.setMemberId(10L);
        m.setUserId(100L);
        m.setMemberLevel("NORMAL");
        m.setAvailablePoints(available);
        m.setTotalPoints(total);
        m.setUsedPoints(used);
        m.setExpiredPoints(0);
        m.setTotalSpent(BigDecimal.ZERO);
        return m;
    }

    private static PointsRedeemItem item(int stockTotal, int redeemed, int pointsCost) {
        PointsRedeemItem item = new PointsRedeemItem();
        item.setItemId(1L);
        item.setTitle("满 20 减 5 券");
        item.setPointsCost(pointsCost);
        item.setCouponDefId(2L);
        item.setStockTotal(stockTotal);
        item.setRedeemedCount(redeemed);
        item.setStatus("ACTIVE");
        return item;
    }

    @Test
    void redeem_shouldDeductPointsIssueCouponAndIncrementStock() {
        Member member = member(500, 500, 0);
        PointsRedeemItem item = item(10, 0, 100);
        CouponDto coupon = new CouponDto(9L, "满 20 减 5 券", "AMOUNT_OFF", 500, 0,
                "UNUSED", null, null, null, "CODE1");
        stubRedeemLocks(100L, 1L);
        when(memberService.getMemberByUserId(100L)).thenReturn(Optional.of(member));
        when(memberRepository.findByUserIdForUpdate(100L)).thenReturn(Optional.of(member));
        when(redeemItemRepository.findById(1L)).thenReturn(Optional.of(item));
        when(redeemItemRepository.tryClaimStock(1L)).thenReturn(1);
        when(couponService.issueToUser(100L, 2L)).thenReturn(coupon);

        CouponDto result = service.redeem(100L, 1L);

        assertEquals(9L, result.couponId());
        assertEquals(400, member.getAvailablePoints());
        assertEquals(100, member.getUsedPoints());
        verify(memberRepository).save(member);
        verify(redeemItemRepository).tryClaimStock(1L);
        verify(redeemItemRepository, never()).save(item);
        ArgumentCaptor<MemberPointsLog> logCaptor = ArgumentCaptor.forClass(MemberPointsLog.class);
        verify(pointsLogRepository).save(logCaptor.capture());
        assertEquals(-100, logCaptor.getValue().getPoints());
        assertEquals("USE", logCaptor.getValue().getPointsType());
        assertEquals("REDEEM", logCaptor.getValue().getSourceType());
    }

    @Test
    void redeem_shouldRejectInsufficientPoints() {
        Member member = member(50, 50, 0);
        PointsRedeemItem item = item(10, 0, 100);
        stubRedeemLocks(100L, 1L);
        when(memberService.getMemberByUserId(100L)).thenReturn(Optional.of(member));
        when(memberRepository.findByUserIdForUpdate(100L)).thenReturn(Optional.of(member));
        when(redeemItemRepository.findById(1L)).thenReturn(Optional.of(item));

        assertThrows(ResponseStatusException.class, () -> service.redeem(100L, 1L));
        verify(couponService, never()).issueToUser(anyLong(), anyLong());
        verify(redeemItemRepository, never()).tryClaimStock(anyLong());
    }

    @Test
    void redeem_shouldRejectSoldOut() {
        Member member = member(500, 500, 0);
        PointsRedeemItem item = item(10, 10, 100);
        stubRedeemLocks(100L, 1L);
        when(memberService.getMemberByUserId(100L)).thenReturn(Optional.of(member));
        when(memberRepository.findByUserIdForUpdate(100L)).thenReturn(Optional.of(member));
        when(redeemItemRepository.findById(1L)).thenReturn(Optional.of(item));
        when(redeemItemRepository.tryClaimStock(1L)).thenReturn(0);

        assertThrows(ResponseStatusException.class, () -> service.redeem(100L, 1L));
        verify(couponService, never()).issueToUser(anyLong(), anyLong());
    }

    @Test
    void summary_shouldExposeBalancesAndRate() {
        Member member = member(320, 680, 160);
        MemberLevelRuleDto rule = new MemberLevelRuleDto(
                1L, "NORMAL", "普通会员", BigDecimal.ZERO, new BigDecimal("1000"),
                0, 100, BigDecimal.ONE, BigDecimal.ZERO, 1, "ACTIVE");
        when(memberService.getMemberByUserId(100L)).thenReturn(Optional.of(member));
        when(memberService.levelRulesActive()).thenReturn(List.of(rule));

        MemberPointsSummaryDto summary = service.summary(100L);

        assertEquals(320, summary.availablePoints());
        assertEquals(680, summary.totalPoints());
        assertEquals(BigDecimal.ONE, summary.pointsRate());
        assertEquals(0, summary.nextLevelPointsGap());
    }

    @Test
    void adminList_shouldMapCouponName() {
        PointsRedeemItem item = item(10, 2, 100);
        CouponDefinition def = new CouponDefinition();
        def.setCouponDefId(2L);
        def.setCouponName("满 20 减 5 券");
        when(redeemItemRepository.findAllOrdered()).thenReturn(List.of(item));
        when(couponDefinitionRepository.findById(2L)).thenReturn(Optional.of(def));

        List<PointsRedeemItemDto> rows = service.adminList();

        assertEquals(1, rows.size());
        assertEquals("满 20 减 5 券", rows.get(0).couponName());
        assertEquals(8, rows.get(0).availableStock());
    }
}
