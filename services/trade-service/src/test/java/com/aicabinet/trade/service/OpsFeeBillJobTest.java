package com.aicabinet.trade.service;

import com.aicabinet.trade.config.FeeBillProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MA-278：月结出账拆开关——租金默认自跑、流量费保持默认停用，两键独立互不门控。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpsFeeBillJobTest {

    @Mock SiteRentBillService rentBillService;
    @Mock DeviceDataFeeBillService dataFeeBillService;
    @Mock FeeBillMonthResolver monthResolver;
    @Mock ScheduledTaskService taskService;
    @Mock SystemConfigService systemConfigService;

    private OpsFeeBillJob job;

    @BeforeEach
    void setUp() {
        job = new OpsFeeBillJob(rentBillService, dataFeeBillService, monthResolver, taskService,
                systemConfigService);
        when(taskService.tryBegin(eq("ops-fee-bill-monthly"), anyLong())).thenReturn(true);
        when(monthResolver.resolve(null)).thenReturn("2026-11");
        // 默认开关：租金 true、流量费 false（MA-278 拆开关后的默认语义）
        when(systemConfigService.getBoolean("feebill.site_rent.auto_generate.enabled", true)).thenReturn(true);
        when(systemConfigService.getBoolean("feebill.auto_generate.enabled", false)).thenReturn(false);
    }

    @Test
    void defaultSwitches_rentRuns_dataSkipped() {
        when(rentBillService.autoGenerate("2026-11")).thenReturn(List.of());

        job.generateMonthlyFees();

        verify(rentBillService).autoGenerate("2026-11");
        verify(dataFeeBillService, never()).autoGenerate(anyString());
        verify(taskService).finish(eq("ops-fee-bill-monthly"), eq("SUCCESS"),
                org.mockito.ArgumentMatchers.contains("租金账单 0 条，流量费账单 0 条"), anyLong());
    }

    @Test
    void bothSwitchesOff_marksSkipped() {
        when(systemConfigService.getBoolean("feebill.site_rent.auto_generate.enabled", true)).thenReturn(false);

        job.generateMonthlyFees();

        verify(rentBillService, never()).autoGenerate(anyString());
        verify(dataFeeBillService, never()).autoGenerate(anyString());
        verify(taskService).finish(eq("ops-fee-bill-monthly"), eq("SKIPPED"), anyString(), anyLong());
    }

    @Test
    void rentSwitchOff_dataOn_onlyDataRuns() {
        when(systemConfigService.getBoolean("feebill.site_rent.auto_generate.enabled", true)).thenReturn(false);
        when(systemConfigService.getBoolean("feebill.auto_generate.enabled", false)).thenReturn(true);
        when(dataFeeBillService.autoGenerate("2026-11")).thenReturn(List.of());

        job.generateMonthlyFees();

        verify(rentBillService, never()).autoGenerate(anyString());
        verify(dataFeeBillService).autoGenerate("2026-11");
    }
}
