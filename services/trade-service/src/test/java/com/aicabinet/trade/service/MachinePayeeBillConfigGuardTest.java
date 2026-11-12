package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.MachinePayeeBill;
import com.aicabinet.trade.domain.MachinePositionConfig;
import com.aicabinet.trade.mapper.MachinePayeeBillMapper;
import com.aicabinet.trade.mapper.MachinePositionConfigMapper;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MA-090：机器账单点位配置（按柜长期生效，出账时 gmv×bps/10000 直接成应付）。
 * 同时钉住「MP updateById 跳 null ⇒ 省略字段是保持原值」这条语义没被校验顺手破坏
 * （lessons #384）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MachinePayeeBillConfigGuardTest {

    @Mock private MachinePositionConfigMapper configMapper;
    @Mock private MachinePayeeBillMapper billMapper;
    @Mock private MerchantScopeService merchantScopeService;

    private MachinePayeeBillService service;

    @Mock private AdminAuditService adminAuditService;

    @BeforeEach
    void setUp() {
        service = new MachinePayeeBillService(configMapper, billMapper, null, adminAuditService,
                merchantScopeService);
        when(configMapper.selectById("DEV-1")).thenReturn(null);
    }

    private MachinePositionConfig cfg(int shareBps, String type, long electric, int enabled, String payee) {
        MachinePositionConfig c = new MachinePositionConfig();
        c.setShareBps(shareBps);
        c.setElectricType(type);
        c.setElectricAmountCents(electric);
        c.setEnabled(enabled);
        c.setPayeeName(payee);
        return c;
    }

    @Test
    void shareBps_aboveFull_rejected() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.saveConfig(9L, "DEV-1", cfg(50_000, "NONE", 0L, 1, "张三")));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("shareBps"), ex.getReason());
        verify(configMapper, never()).insert(any());
    }

    @Test
    void enabledJunk_rejected() {
        // generateMonthly 只筛 enabled=1，写 2 = 整柜从出账面消失
        assertEquals("enabled 只能是 0/1",
                assertThrows(ResponseStatusException.class,
                        () -> service.saveConfig(9L, "DEV-1", cfg(0, "NONE", 0L, 2, "张三"))).getReason());
    }

    @Test
    void electricTypeOutsideVocabulary_rejected() {
        assertThrows(ResponseStatusException.class,
                () -> service.saveConfig(9L, "DEV-1", cfg(0, "METERED", 100L, 1, "张三")));
    }

    @Test
    void missingPayeeName_rejectedAsFourHundredNotNineteenCharError() {
        // 列是 NOT NULL（V356），原来只会撞成没有字段名的泛化 409
        assertEquals("payeeName 必填（场地方收款人）",
                assertThrows(ResponseStatusException.class,
                        () -> service.saveConfig(9L, "DEV-1", cfg(0, "NONE", 0L, 1, "  "))).getReason());
    }

    @Test
    void oversizePayeeName_rejected() {
        assertThrows(ResponseStatusException.class,
                () -> service.saveConfig(9L, "DEV-1", cfg(0, "NONE", 0L, 1, "名".repeat(65))));
    }

    @Test
    void validConfig_normalizedAndPersisted() {
        service.saveConfig(9L, "DEV-1", cfg(120, "fixed", 8_000L, 1, " 张三 "));
        ArgumentCaptor<MachinePositionConfig> cap = ArgumentCaptor.forClass(MachinePositionConfig.class);
        verify(configMapper).insert(cap.capture());
        assertEquals("FIXED", cap.getValue().getElectricType());
        assertEquals("张三", cap.getValue().getPayeeName());
        assertEquals(120, cap.getValue().getShareBps());
    }

    @Test
    void omittedFieldsAreNotCoercedToZero() {
        // MP 的 updateById 跳过 null：PUT 只带 payeeName 时，其余列必须原样保持
        MachinePositionConfig partial = new MachinePositionConfig();
        partial.setPayeeName("张三");
        service.saveConfig(9L, "DEV-1", partial);
        ArgumentCaptor<MachinePositionConfig> cap = ArgumentCaptor.forClass(MachinePositionConfig.class);
        verify(configMapper).insert(cap.capture());
        assertEquals(null, cap.getValue().getShareBps());
        assertEquals(null, cap.getValue().getEnabled());
        assertEquals(null, cap.getValue().getElectricType());
    }


    @Test
    void settle_idempotentOnAlreadySettled_noDuplicateAudit() {
        MachinePayeeBill settled = new MachinePayeeBill();
        settled.setBillId(7L);
        settled.setBillMonth("2026-10");
        settled.setStatus("SETTLED");
        when(billMapper.findByIdForUpdateRaw(7L)).thenReturn(settled);

        service.settle(9L, 7L);

        verify(billMapper, never()).updateById(any());
        verify(adminAuditService, never()).appendLog(any(), any(), any(), any(), any());
    }

    @Test
    void settle_usesRowLockRead_notPlainSelectById() {
        MachinePayeeBill bill = new MachinePayeeBill();
        bill.setBillId(8L);
        bill.setBillMonth("2026-10");
        bill.setStatus("PENDING");
        when(billMapper.findByIdForUpdateRaw(8L)).thenReturn(bill);

        service.settle(9L, 8L);

        // R16-MA-175：读必须走 FOR UPDATE 行锁（并发双击结清的竞态收口）
        verify(billMapper).findByIdForUpdateRaw(8L);
        verify(billMapper, never()).selectById(8L);
        verify(billMapper).updateById(bill);
        assertEquals("SETTLED", bill.getStatus());
    }

    @Test
    void saveConfig_takesConfigRowLockBeforeInsertOrUpdate() {
        // setUp 已 stub selectById("DEV-1")=null → insert 分支
        service.saveConfig(9L, "DEV-1", cfg(100, "NONE", 0L, 1, "张三"));

        // R16-MA-175：insert-or-update 前必须先拿配置行锁（并发双保存的竞态收口）
        verify(configMapper).findByIdForUpdateRaw("DEV-1");
        verify(configMapper).insert(any(MachinePositionConfig.class));
    }
}
