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
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberStatsConcurrencyTest {

    @Mock private MemberMapper memberRepository;
    @Mock private MemberLevelRuleMapper levelRuleRepository;
    @Mock private MemberPointsLogMapper pointsLogRepository;
    @Mock private DistributedLockService distributedLockService;

    private MemberService memberService;

    @BeforeEach
    void setUp() {
        memberService = new MemberService(memberRepository, levelRuleRepository, pointsLogRepository,
                distributedLockService, null);
    }

    @Test
    void createMember_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                MemberService.memberUserLockKey(200L), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> memberService.createMember(200L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void clawbackPointsOnRefund_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                MemberService.memberUserLockKey(201L), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> memberService.clawbackPointsOnRefund(201L, 100, "O-2", "R-1"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void updateMemberStats_unlocksLockAfterSuccess() {
        Member preview = new Member();
        preview.setMemberId(10L);
        preview.setUserId(202L);
        when(memberRepository.findById(10L)).thenReturn(Optional.of(preview));
        when(distributedLockService.tryLock(
                MemberService.memberUserLockKey(202L), 60L, 5L))
                .thenReturn(true);
        Member locked = new Member();
        locked.setMemberId(10L);
        locked.setUserId(202L);
        locked.setTotalSpent(BigDecimal.ZERO);
        locked.setOrderCount(0);
        locked.setMemberLevel(MemberService.LEVEL_NORMAL);
        when(memberRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(locked));
        when(levelRuleRepository.findByStatusOrderBySortorderAsc("ACTIVE")).thenReturn(java.util.List.of());

        memberService.updateMemberStats(10L, BigDecimal.ONE);

        verify(distributedLockService).unlock(MemberService.memberUserLockKey(202L));
    }
}
