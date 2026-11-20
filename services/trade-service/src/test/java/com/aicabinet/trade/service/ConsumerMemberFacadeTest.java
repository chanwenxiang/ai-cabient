package com.aicabinet.trade.service;

import com.aicabinet.common.dto.MemberProfileDto;
import com.aicabinet.trade.domain.Member;
import com.aicabinet.trade.domain.MemberLevelRule;
import com.aicabinet.trade.mapper.MemberLevelRuleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 会员档案的「档位未配置」分支（2026-11-20 真机：会员中心显示英文 NORMAL、等级说明整段空白）。
 *
 * <p>生产 {@code member_level_rule} 被清空后 {@code current == null}，此前代码直接把
 * {@code member.memberLevel}（编码 "NORMAL"）当展示名下发；等级名必须是中文，
 * 且不能凭空声称「已达最高等级」。
 */
@ExtendWith(MockitoExtension.class)
class ConsumerMemberFacadeTest {

    @Mock
    private MemberService memberService;

    @Mock
    private MemberLevelRuleMapper levelRuleRepository;

    private ConsumerMemberFacade facade;

    @BeforeEach
    void setUp() {
        facade = new ConsumerMemberFacade(memberService, levelRuleRepository);
    }

    private Member normalMember() {
        Member m = new Member();
        m.setMemberId(11L);
        m.setUserId(7L);
        m.setMemberLevel(MemberService.LEVEL_NORMAL);
        m.setTotalSpent(BigDecimal.ZERO);
        m.setAvailablePoints(0);
        m.setTotalPoints(0);
        m.setOrderCount(0);
        m.setCreatedAt(Instant.parse("2026-11-20T00:58:00Z"));
        return m;
    }

    @Test
    void profile_levelRulesEmpty_levelNameFallsBackToChinese() {
        when(memberService.getMemberByUserId(7L)).thenReturn(Optional.of(normalMember()));
        when(levelRuleRepository.findByStatusOrderBySortorderAsc("ACTIVE")).thenReturn(List.of());

        MemberProfileDto dto = facade.profile(7L);

        assertEquals(MemberService.LEVEL_NORMAL, dto.levelCode());
        assertEquals("普通会员", dto.levelName(), "档位规则为空时等级名必须回退中文，不能下发 NORMAL");
        assertNull(dto.nextLevelName());
        assertTrue(dto.levels().isEmpty(), "未配置档位时不应造假档位列表");
    }

    @Test
    void profile_ruleNameBlank_usesCodeFallback() {
        MemberLevelRule normal = new MemberLevelRule();
        normal.setId(1L);
        normal.setLevelCode(MemberService.LEVEL_NORMAL);
        normal.setLevelName(MemberService.LEVEL_NORMAL); // 历史 fallback 把编码当名字存进了库
        normal.setMinSpent(BigDecimal.ZERO);
        normal.setMaxSpent(new BigDecimal("1000"));
        normal.setPointsRate(BigDecimal.ONE);
        normal.setSortorder(1);
        normal.setStatus("ACTIVE");

        when(memberService.getMemberByUserId(7L)).thenReturn(Optional.of(normalMember()));
        when(levelRuleRepository.findByStatusOrderBySortorderAsc("ACTIVE"))
                .thenReturn(List.of(normal));

        MemberProfileDto dto = facade.profile(7L);

        assertEquals("普通会员", dto.levelName(), "名字==编码时也必须回退中文");
        assertEquals("普通会员", dto.levels().get(0).levelName());
    }

    @Test
    void profile_rulesConfigured_reportsNextLevel() {
        MemberLevelRule normal = new MemberLevelRule();
        normal.setLevelCode(MemberService.LEVEL_NORMAL);
        normal.setLevelName("普通会员");
        normal.setMinSpent(BigDecimal.ZERO);
        normal.setMaxSpent(new BigDecimal("1000"));
        normal.setPointsRate(BigDecimal.ONE);
        normal.setSortorder(1);
        normal.setStatus("ACTIVE");

        MemberLevelRule silver = new MemberLevelRule();
        silver.setLevelCode(MemberService.LEVEL_SILVER);
        silver.setLevelName("银卡会员");
        silver.setMinSpent(new BigDecimal("1000"));
        silver.setMaxSpent(new BigDecimal("5000"));
        silver.setPointsRate(new BigDecimal("1.20"));
        silver.setSortorder(2);
        silver.setStatus("ACTIVE");

        when(memberService.getMemberByUserId(7L)).thenReturn(Optional.of(normalMember()));
        when(levelRuleRepository.findByStatusOrderBySortorderAsc("ACTIVE"))
                .thenReturn(List.of(normal, silver));

        MemberProfileDto dto = facade.profile(7L);

        assertEquals("普通会员", dto.levelName());
        assertEquals("银卡会员", dto.nextLevelName());
        assertEquals(1000, dto.spentToNextLevel());
        assertEquals(2, dto.levels().size());
    }
}
