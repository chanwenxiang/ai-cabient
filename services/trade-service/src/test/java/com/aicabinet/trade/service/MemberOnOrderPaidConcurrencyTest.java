package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.Member;
import com.aicabinet.trade.mapper.MemberLevelRuleMapper;
import com.aicabinet.trade.mapper.MemberMapper;
import com.aicabinet.trade.mapper.MemberPointsLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberOnOrderPaidConcurrencyTest {

    @Mock private MemberMapper memberRepository;
    @Mock private MemberLevelRuleMapper levelRuleRepository;
    @Mock private MemberPointsLogMapper pointsLogRepository;
    @Mock private DistributedLockService distributedLockService;

    private MemberService memberService;

    @BeforeEach
    void setUp() {
        memberService = new MemberService(memberRepository, levelRuleRepository, pointsLogRepository,
                distributedLockService, null);
        ReflectionTestUtils.setField(memberService, "self", memberService);
    }

    @Test
    void onOrderPaid_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                MemberService.memberUserLockKey(10001L), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> memberService.onOrderPaid(10001L, 500, "O-1"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void onOrderPaid_unlocksLockAfterSuccess() {
        when(distributedLockService.tryLock(
                MemberService.memberUserLockKey(10001L), 60L, 5L))
                .thenReturn(true);
        Member member = new Member();
        member.setMemberId(1L);
        member.setUserId(10001L);
        member.setMemberLevel(MemberService.LEVEL_NORMAL);
        member.setTotalSpent(java.math.BigDecimal.ZERO);
        member.setOrderCount(0);
        when(memberRepository.findByUserIdForUpdate(10001L)).thenReturn(Optional.of(member));
        when(levelRuleRepository.findByLevelCode(MemberService.LEVEL_NORMAL)).thenReturn(Optional.empty());
        when(pointsLogRepository.existsByMemberAndSource(1L, "ORDER", "O-1")).thenReturn(false);
        when(memberRepository.save(any(Member.class))).thenAnswer(inv -> inv.getArgument(0));

        memberService.onOrderPaid(10001L, 500, "O-1");

        verify(distributedLockService).unlock(MemberService.memberUserLockKey(10001L));
    }
}
