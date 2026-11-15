package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.dto.GenerateMonthlyFeeBillsRequest;
import com.aicabinet.trade.domain.SiteContract;
import com.aicabinet.trade.domain.SiteRentBill;
import com.aicabinet.trade.mapper.SiteContractMapper;
import com.aicabinet.trade.mapper.SiteRentBillMapper;
import com.aicabinet.trade.mapper.SiteRentSplitRuleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * MA-273：场地租金出账必须落在合同租期内。
 *
 * <p>这组用例钉的是**入口真的调用了判据**——纯静态判据用例（{@code SiteRentBillServiceTest}）
 * 只能证明函数本身对，证明不了出账链路会用它。两条入口语义不同：
 * <ul>
 *   <li>手动出账（{@code generateForContract}）：租期外要 <b>400</b>，操作员才知道为什么不出；</li>
 *   <li>批量/定时（{@code autoGenerate}）：租期外 <b>跳过</b>，一张合同异常不能让整批中断。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SiteRentBillContractTermTest {

    @Mock private SiteRentBillMapper billMapper;
    @Mock private SiteContractMapper contractMapper;
    @Mock private SiteRentSplitRuleMapper ruleMapper;
    @Mock private PermissionService permissionService;
    @Mock private AdminAuditService auditService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private FeeBillMonthResolver monthResolver;

    private SiteRentBillService service;

    @BeforeEach
    void setUp() {
        service = new SiteRentBillService(billMapper, contractMapper, ruleMapper,
                permissionService, auditService, distributedLockService, monthResolver);
        // 不给 requirePermission 打桩：mock 的 void 方法本就什么都不做，
        // 而批量入口（operatorId=null）根本不调它 —— 打桩会触发 strict-stubs 的「未使用」报错。
    }

    private SiteContract contract(Long id, String start, String end) {
        SiteContract c = new SiteContract();
        c.setContractId(id);
        c.setDeviceId("EG943322621391");
        c.setSiteName("E2E-场地");
        c.setStartDate(start == null ? null : LocalDate.parse(start));
        c.setEndDate(end == null ? null : LocalDate.parse(end));
        c.setMonthlyFeeCents(100000);
        c.setStatus(CabinetConstants.PROMOTION_STATUS_ACTIVE);
        return c;
    }

    /** 手动出账碰上「还没起租」：400 + 明确文案，且一行账单都不写。 */
    @Test
    void generateForContract_notYetStarted_rejectsWithoutWriting() {
        when(monthResolver.resolve("2026-04")).thenReturn("2026-04");
        when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        when(contractMapper.findByIdForUpdate(7L))
                .thenReturn(Optional.of(contract(7L, "2026-05-01", null)));
        when(billMapper.countNonVoidByContractAndMonth(7L, "2026-04")).thenReturn(0L);

        var ex = assertThrows(ResponseStatusException.class, () -> service.generateForContract(
                1L, 7L, GenerateMonthlyFeeBillsRequest.ofMonth("2026-04")));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(String.valueOf(ex.getReason()).contains("未起租"));
        verify(billMapper, never()).insert(any());
        verifyNoInteractions(ruleMapper);
    }

    /** 手动出账碰上「已到期」：同样 400，文案说清是到期而非未起租。 */
    @Test
    void generateForContract_expired_rejectsWithoutWriting() {
        when(monthResolver.resolve("2026-04")).thenReturn("2026-04");
        when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        when(contractMapper.findByIdForUpdate(7L))
                .thenReturn(Optional.of(contract(7L, "2025-01-01", "2026-03-31")));
        when(billMapper.countNonVoidByContractAndMonth(7L, "2026-04")).thenReturn(0L);

        var ex = assertThrows(ResponseStatusException.class, () -> service.generateForContract(
                1L, 7L, GenerateMonthlyFeeBillsRequest.ofMonth("2026-04")));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(String.valueOf(ex.getReason()).contains("已到期"));
        verify(billMapper, never()).insert(any());
    }

    /** 批量出账碰上租期外：跳过，不抛（否则整批被一张异常合同掐断）。 */
    @Test
    void autoGenerate_skipsExpiredContractWithoutWriting() {
        when(monthResolver.resolve(null)).thenReturn("2026-04");
        when(contractMapper.findAllOrderByUpdatedDesc())
                .thenReturn(List.of(contract(7L, "2025-01-01", "2026-03-31")));
        // 批量路径逐张重读（findByIdForUpdate）后才判租期，这一层也要给
        when(contractMapper.findByIdForUpdate(7L))
                .thenReturn(Optional.of(contract(7L, "2025-01-01", "2026-03-31")));
        when(billMapper.countNonVoidByContractAndMonth(7L, "2026-04")).thenReturn(0L);
        when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);

        var out = service.autoGenerate(null);

        assertTrue(out.isEmpty());
        verify(billMapper, never()).insert(any());
    }

    /** 正向控制：租期内的合同照常出账（防「改成永不出账」也被判绿）。 */
    @Test
    void autoGenerate_generatesWithinTerm() {
        when(monthResolver.resolve(null)).thenReturn("2026-04");
        when(contractMapper.findAllOrderByUpdatedDesc())
                .thenReturn(List.of(contract(7L, "2025-01-01", "2027-12-31")));
        when(contractMapper.findByIdForUpdate(7L))
                .thenReturn(Optional.of(contract(7L, "2025-01-01", "2027-12-31")));
        when(billMapper.countNonVoidByContractAndMonth(7L, "2026-04")).thenReturn(0L);
        when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        when(ruleMapper.findByContractId(7L)).thenReturn(List.of());
        when(billMapper.insert(any(SiteRentBill.class))).thenReturn(1);

        var out = service.autoGenerate(null);

        // 没有分账规则 ⇒ 全额归房东，恰好 1 张
        assertEquals(1, out.size());
        verify(billMapper).insert(any(SiteRentBill.class));
    }

    /** null 日期 = 不限：不因缺日期而停止出账。 */
    @Test
    void autoGenerate_nullDatesStillGenerates() {
        when(monthResolver.resolve(null)).thenReturn("2026-04");
        when(contractMapper.findAllOrderByUpdatedDesc())
                .thenReturn(List.of(contract(7L, null, null)));
        when(contractMapper.findByIdForUpdate(7L)).thenReturn(Optional.of(contract(7L, null, null)));
        when(billMapper.countNonVoidByContractAndMonth(7L, "2026-04")).thenReturn(0L);
        when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        when(ruleMapper.findByContractId(7L)).thenReturn(List.of());
        when(billMapper.insert(any(SiteRentBill.class))).thenReturn(1);

        var out = service.autoGenerate(null);

        assertEquals(1, out.size());
    }
}
