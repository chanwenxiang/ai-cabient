package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.LineRouteMember;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.LineManagerMapper;
import com.aicabinet.trade.mapper.LineRouteMemberMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MA-100：线路成员三列（basis/rateBps/perUnitCents）被 LineCommissionJob 乘进补货员可提现钱包，
 * 写侧必须限幅 + 必须留痕；MA-108 的同端点必填/存在性闸一并钉住。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LineRouteMemberCommissionGuardTest {

    @Mock private LineRouteMemberMapper memberMapper;
    @Mock private LineManagerMapper lineManagerMapper;
    @Mock private UserInfoMapper userInfoMapper;
    @Mock private AdminAuditService auditService;

    private LineRouteAdminService service;

    @BeforeEach
    void setUp() {
        service = new LineRouteAdminService(null, memberMapper, null, null, null, null,
                lineManagerMapper, userInfoMapper, auditService);
        when(userInfoMapper.selectById(7L)).thenReturn(mock(UserInfo.class));
    }

    private LineRouteMember member(int bps, long perUnit, String role, String basis) {
        LineRouteMember m = new LineRouteMember();
        m.setUserId(7L);
        m.setRole(role);
        m.setCommissionBasis(basis);
        m.setCommissionRateBps(bps);
        m.setCommissionPerUnitCents(perUnit);
        return m;
    }

    @Test
    void upsertMember_overFullBps_rejectedBeforeWrite() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.upsertMember(1L, 2L, member(10_001, 0L, "STAFF", "GMV")));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("commissionRateBps"), ex.getReason());
        verify(memberMapper, never()).insert(any());
    }

    @Test
    void upsertMember_overCapPerUnit_rejected() {
        assertThrows(ResponseStatusException.class,
                () -> service.upsertMember(1L, 2L, member(200, 5_001L, "STAFF", "RESTOCK_QTY")));
        verify(memberMapper, never()).insert(any());
    }

    @Test
    void upsertMember_inventedRoleOrBasis_rejected() {
        // V358 列注释即词表；写错值只会让这名成员从计佣面静默消失
        assertThrows(ResponseStatusException.class,
                () -> service.upsertMember(1L, 2L, member(200, 0L, "BOSS", "GMV")));
        assertThrows(ResponseStatusException.class,
                () -> service.upsertMember(1L, 2L, member(200, 0L, "STAFF", "PER_ORDER")));
    }

    @Test
    void upsertMember_ghostUserId_rejected() {
        when(userInfoMapper.selectById(any(Long.class))).thenReturn(null);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.upsertMember(1L, 2L, member(200, 0L, "STAFF", "GMV")));
        assertEquals("userId 不存在，不能挂进线路成员", ex.getReason());
    }

    @Test
    void upsertMember_valid_leavesAuditTrailWithParams() {
        when(memberMapper.selectOne(any())).thenReturn(null);
        LineRouteMember created = service.upsertMember(9001L, 2L, member(200, 30L, "manager", "gmv"));

        // 小写入参归一成大写词表值（原来大小写敏感，"active" 类垃圾就这么进过库）
        assertEquals("MANAGER", created.getRole());
        assertEquals("GMV", created.getCommissionBasis());
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).appendLog(eq(9001L), eq("LINE_ROUTE_MEMBER_CREATE"),
                eq("LINE_ROUTE_MEMBER"), any(), detail.capture());
        assertTrue(detail.getValue().contains("rateBps=200"), detail.getValue());
        assertTrue(detail.getValue().contains("perUnitCents=30"), detail.getValue());
    }

    @Test
    void upsertMember_existing_leavesBeforeAfterAudit() {
        LineRouteMember existing = member(100, 10L, "STAFF", "GMV");
        existing.setId(55L);
        existing.setStatus("ACTIVE");
        when(memberMapper.selectOne(any())).thenReturn(existing);

        service.upsertMember(9001L, 2L, member(200, 30L, "STAFF", "GMV"));

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).appendLog(eq(9001L), eq("LINE_ROUTE_MEMBER_UPDATE"),
                eq("LINE_ROUTE_MEMBER"), eq("55"), detail.capture());
        assertTrue(detail.getValue().contains("rateBps=100,perUnitCents=10,status=ACTIVE -> basis=GMV,rateBps=200"),
                detail.getValue());
    }

    @Test
    void removeMember_leavesAuditTrail() {
        LineRouteMember existing = member(100, 10L, "STAFF", "GMV");
        existing.setId(55L);
        existing.setRouteId(2L);
        when(memberMapper.selectById(55L)).thenReturn(existing);

        service.removeMember(9001L, 2L, 55L);

        verify(memberMapper).deleteById(55L);
        verify(auditService).appendLog(eq(9001L), eq("LINE_ROUTE_MEMBER_REMOVE"),
                eq("LINE_ROUTE_MEMBER"), eq("55"), anyString());
    }

    @Test
    void create_rejectsGhostManagerAndBlankName() {
        com.aicabinet.trade.domain.LineRoute route = new com.aicabinet.trade.domain.LineRoute();
        route.setRouteName("R1");
        route.setManagerId(3L);
        when(lineManagerMapper.selectById(3L)).thenReturn(null);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.create(route));
        assertEquals("managerId 不存在（须是已建线长）", ex.getReason());

        route.setManagerId(4L);
        when(lineManagerMapper.selectById(4L)).thenReturn(mock(com.aicabinet.trade.domain.LineManager.class));
        route.setRouteName("  ");
        assertEquals("routeName 必填",
                assertThrows(ResponseStatusException.class, () -> service.create(route)).getReason());
    }
}
