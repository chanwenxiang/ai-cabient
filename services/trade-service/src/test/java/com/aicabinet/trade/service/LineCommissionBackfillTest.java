package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.LineDevice;
import com.aicabinet.trade.domain.LineManager;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.LineCommissionDailyMapper;
import com.aicabinet.trade.mapper.LineDeviceMapper;
import com.aicabinet.trade.mapper.LineManagerMapper;
import com.aicabinet.trade.support.ScheduleZones;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H19：日佣金任务自愈式回扫——单设备-日抢锁失败只跳过该设备-日，
 * 最近 7 天内尚无入账记录的设备-日在后续运行中补算。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LineCommissionBackfillTest {

    private static final ZoneId ZONE = ScheduleZones.ZONE;

    @Mock private LineManagerMapper managerMapper;
    @Mock private LineDeviceMapper deviceMapper;
    @Mock private CabinetOrderMapper orderMapper;
    @Mock private LineCommissionDailyMapper commissionDailyMapper;
    @Mock private LineWalletService lineWalletService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private ScheduledTaskService taskService;

    private LineCommissionJob job;

    @BeforeEach
    void setUp() {
        job = new LineCommissionJob(managerMapper, deviceMapper, orderMapper,
                commissionDailyMapper, lineWalletService, distributedLockService, taskService);
        lenient().when(taskService.tryBegin(eq("line-commission"), anyLong())).thenReturn(true);
        lenient().when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        LineDevice binding = new LineDevice();
        binding.setManagerId(7L);
        binding.setDeviceId("CAB-H19");
        lenient().when(deviceMapper.findByStatus(LineManagerService.STATUS_ACTIVE)).thenReturn(List.of(binding));
        LineManager manager = new LineManager();
        manager.setManagerId(7L);
        manager.setStatus(LineManagerService.STATUS_ACTIVE);
        manager.setCommissionRateBps(1000);
        manager.setCommissionFixedCents(0);
        lenient().when(managerMapper.findById(7L)).thenReturn(Optional.of(manager));
        lenient().when(commissionDailyMapper.findByManagerIdAndBizDateAndDeviceId(anyLong(), any(), anyString()))
                .thenReturn(Optional.empty());
    }

    private CabinetOrder paidOrder(int amountCents) {
        CabinetOrder order = new CabinetOrder();
        order.setStatus("PAID");
        order.setTotalAmountCents(amountCents);
        return order;
    }

    @Test
    void backfillsYesterdayWhenNoRecordExists() {
        LocalDate yesterday = LocalDate.now(ZONE).minusDays(1);
        Instant start = yesterday.atStartOfDay(ZONE).toInstant();
        Instant end = yesterday.plusDays(1).atStartOfDay(ZONE).toInstant();
        when(orderMapper.findByDeviceIdAndCreatedAtBetween("CAB-H19", start, end))
                .thenReturn(List.of(paidOrder(10_000)));

        job.postDailyCommission();

        verify(lineWalletService, times(1)).credit(eq(7L), eq(1_000L), eq("COMMISSION"),
                eq("COMMISSION_DAILY"), eq(yesterday + "|CAB-H19"), anyString());
    }

    /** 抢锁失败的设备-日仅跳过当天；昨天已入账的设备-日不重复入账。 */
    @Test
    void existingRecordShortCircuitsWithoutLock() {
        LocalDate yesterday = LocalDate.now(ZONE).minusDays(1);
        when(commissionDailyMapper.findByManagerIdAndBizDateAndDeviceId(7L, yesterday, "CAB-H19"))
                .thenReturn(Optional.of(new com.aicabinet.trade.domain.LineCommissionDaily()));

        job.postDailyCommission();

        verify(distributedLockService, never()).tryLock(
                eq(LineCommissionJob.lineCommissionDailyLockKey(7L, "CAB-H19", yesterday)), anyLong(), anyLong());
        verify(lineWalletService, never()).credit(anyLong(), anyLong(), any(), any(), any(), any());
    }

    /** 无订单的设备-日不产生佣金与入账记录（回扫窗口内下次仍会重算，幂等）。 */
    @Test
    void daysWithoutPaidOrders_produceNoCredit() {
        when(orderMapper.findByDeviceIdAndCreatedAtBetween(eq("CAB-H19"), any(), any()))
                .thenReturn(List.of());

        job.postDailyCommission();

        verify(lineWalletService, never()).credit(anyLong(), anyLong(), any(), any(), any(), any());
    }
}
