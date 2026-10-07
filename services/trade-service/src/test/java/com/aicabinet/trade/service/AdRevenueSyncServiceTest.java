package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.AdRevenueDaily;
import com.aicabinet.trade.mapper.AdRevenueDailyMapper;
import com.aicabinet.trade.wechat.WeChatPublisherStatClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V310：广告收益拉取落库。
 *
 * <p>🔴 本类钉住三个<b>最容易造成真实资损</b>的行为：
 * <ol>
 *   <li><b>UPSERT 覆盖而非累加</b> —— 定时任务反复拉同一区间（微信无增量接口），
 *       用「有则累加」会让收入每次调度<b>翻一倍</b>；</li>
 *   <li><b>微信报错必须抛出，不能吞成空列表</b> —— 未开通流量主（ret=2009）
 *       返回的是错误而不是空数据，吞掉会把「故障」伪装成「今天没收入」；</li>
 *   <li><b>eCPM 换算用微元且 null ≠ 0</b> —— 存整数分会截断（6.79 分 → 6 分，
 *       误差 11%）；把「未返回」当0 会让「没测到」变成「收益为零」。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class AdRevenueSyncServiceTest {

    @Mock private AdRevenueDailyMapper mapper;
    @Mock private WeChatPublisherStatClient client;

    private AdRevenueSyncService service;

    @BeforeEach
    void setUp() {
        service = new AdRevenueSyncService(mapper, client);
    }

    private WeChatPublisherStatClient.AdPosGeneralRow posRow(String slot, LocalDate date, long incomeCents) {
        WeChatPublisherStatClient.AdPosGeneralRow r = new WeChatPublisherStatClient.AdPosGeneralRow();
        r.adSlot = slot;
        r.date = date;
        r.reqSuccCount = 100L;
        r.exposureCount = 50L;
        r.clickCount = 3L;
        r.incomeCents = incomeCents;
        r.ecpmCents = 6.79d;
        return r;
    }

    @Test
    @DisplayName("首次入库走 insert")
    void firstSync_inserts() {
        lenient().when(mapper.selectOne(any())).thenReturn(null);
        when(client.fetchAdPosGeneral(any(), any(), anyString()))
                .thenReturn(List.of(posRow("SLOT_ID_WEAPP_BANNER", LocalDate.of(2026, 10, 6), 100L)));

        int saved = service.syncEstimates(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 6), "SLOT_ID_WEAPP_BANNER");

        assertEquals(1, saved);
        verify(mapper, times(1)).insert(any(AdRevenueDaily.class));
        verify(mapper, never()).updateById(any());
    }

    @Test
    @DisplayName("🔴 已存在时覆盖而非累加（累加会让收入每次调度翻倍）")
    void existingRow_isOverwrittenNotAccumulated() {
        AdRevenueDaily existing = new AdRevenueDaily();
        existing.setAdRevenueId(77L);
        existing.setCreatedAt(java.time.Instant.parse("2026-10-01T00:00:00Z"));
        // 🔴 关键：existing 必须有**非零**的历史金额，否则「覆盖」与「累加」
        //   的结果都是 100+0 与 0+100，判据形同虚设（负向对照实测发现）。
        //   真实场景里existing 一定有上轮拉到的收入。
        existing.setIncomeCents(1_000L);
        when(mapper.selectOne(any())).thenReturn(existing);
        when(client.fetchAdPosGeneral(any(), any(), anyString()))
                .thenReturn(List.of(posRow("SLOT_ID_WEAPP_BANNER", LocalDate.of(2026, 10, 6), 250L)));

        service.syncEstimates(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 6), "SLOT_ID_WEAPP_BANNER");

        ArgumentCaptor<AdRevenueDaily> captor = ArgumentCaptor.forClass(AdRevenueDaily.class);
        verify(mapper).updateById(captor.capture());
        AdRevenueDaily saved = captor.getValue();
        assertEquals(77L, saved.getAdRevenueId(), "应复用原行 id，不能插新行");
        // 覆盖 ⇒ 250；累加 ⇒ 1000 + 250 = 1250。断言必须能区分这两者。
        assertEquals(250L, saved.getIncomeCents(),
                "金额必须被覆盖为本次拉到的值（累加会得到 1250，那是收入翻倍的bug）");
        assertEquals("ESTIMATE", saved.getDataSource());
        // created_at 保留首次入库时间 —— 能看出这条被重拉了几次
        assertEquals(java.time.Instant.parse("2026-10-01T00:00:00Z"), saved.getCreatedAt());
    }

    @Test
    @DisplayName("🔴 微信报错直接抛出，不吞成 0 条（否则故障伪装成「今天没收入」）")
    void wechatApiError_propagates() {
        when(client.fetchAdPosGeneral(any(), any(), anyString()))
                .thenThrow(new WeChatPublisherStatClient.WeChatApiException(2009, "无效的流量主"));

        WeChatPublisherStatClient.WeChatApiException e = assertThrows(
                WeChatPublisherStatClient.WeChatApiException.class,
                () -> service.syncEstimates(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 6), "SLOT"));
        assertTrue(e.isPublisherNotEnabled());
        verify(mapper, never()).insert(any());
    }

    @Test
    @DisplayName("微信返回空列表 ⇒ 落 0 行且不报错（尚无曝光属正常）")
    void emptyResponse_savesZero() {
        when(client.fetchAdPosGeneral(any(), any(), anyString())).thenReturn(List.of());
        assertEquals(0, service.syncEstimates(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 6), "SLOT"));
        verify(mapper, never()).insert(any());
    }

    @Test
    @DisplayName("缺 date 的行被跳过（落库 biz_date 为 null 会直接违反 NOT NULL）")
    void rowWithoutDate_isSkipped() {
        lenient().when(mapper.selectOne(any())).thenReturn(null);
        // 空 ad_slot 由解析层过滤；这里验证「有 slot 但没日期」也必须跳过
        when(client.fetchAdPosGeneral(any(), any(), anyString()))
                .thenReturn(List.of(posRow("SLOT_ID_WEAPP_BANNER", null, 100L)));

        assertEquals(0, service.syncEstimates(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 6), "SLOT"));
        verify(mapper, never()).insert(any());
    }

    @Test
    @DisplayName("eCPM 6.79 分 ⇒ 存 679 微元（存整数分会截成 6，误差 11%）")
    void ecpm_scaledToMicros() {
        assertEquals(679L, AdRevenueSyncService.toEcpmMicros(6.79d));
        assertEquals(0L, AdRevenueSyncService.toEcpmMicros(0d));
        assertEquals(10000L, AdRevenueSyncService.toEcpmMicros(100d));
    }

    @Test
    @DisplayName("🔴 eCPM 未返回（NaN）⇒ null 而非 0（0 是合法 eCPM）")
    void ecpmNaN_mapsToNull() {
        assertNull(AdRevenueSyncService.toEcpmMicros(Double.NaN));
        assertNull(AdRevenueSyncService.toEcpmMicros(Double.POSITIVE_INFINITY));
        assertNotNull(AdRevenueSyncService.toEcpmMicros(0d), "0 是合法值，必须与「未返回」区分");
    }

    @Test
    @DisplayName("回溯窗口留2 天缓冲：88 天而非 90（避免卡边界导致某天永久缺失）")
    void lookbackWindow_hasBuffer() {
        assertEquals(88, AdRevenueSyncService.MAX_LOOKBACK_DAYS);
        assertTrue(AdRevenueSyncService.MAX_LOOKBACK_DAYS < 90,
                "必须小于微信 90 天上限，否则边界日可能永久取不到");
    }

    @Test
    @DisplayName("结算只落已完成状态（1 结算中 / 4 付款中 金额还会变，落库等于记了会变的账）")
    void settlement_onlyWhenSettled() {
        WeChatPublisherStatClient.SettlementRow pending = new WeChatPublisherStatClient.SettlementRow();
        pending.month = "202609";
        pending.order = 1;
        pending.settStatus = 1; // 结算中
        WeChatPublisherStatClient.SlotRevenue sr = new WeChatPublisherStatClient.SlotRevenue();
        sr.adSlot = "SLOT_ID_WEAPP_BANNER";
        sr.settledRevenueCents = 999_99L;
        pending.bySlot.add(sr);

        lenient().when(mapper.selectOne(any())).thenReturn(null);
        when(client.fetchSettlement(any(), any())).thenReturn(List.of(pending));

        assertEquals(0, service.syncSettlements(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)));
        verify(mapper, never()).insert(any());
    }

    @Test
    @DisplayName("结算已完成（status=2）⇒ 落 SETTLED 行，收入进该半月上半月日期")
    void settledRow_isPersisted() {
        WeChatPublisherStatClient.SettlementRow done = new WeChatPublisherStatClient.SettlementRow();
        done.month = "202609";
        done.order = 1;
        done.settStatus = 2; // 已结算
        done.zone = "2026年9月1日至15日";
        WeChatPublisherStatClient.SlotRevenue sr = new WeChatPublisherStatClient.SlotRevenue();
        sr.adSlot = "SLOT_ID_WEAPP_BANNER";
        sr.settledRevenueCents = 123_45L;
        done.bySlot.add(sr);

        when(mapper.selectOne(any())).thenReturn(null);
        when(client.fetchSettlement(any(), any())).thenReturn(List.of(done));

        assertEquals(1, service.syncSettlements(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)));

        ArgumentCaptor<AdRevenueDaily> captor = ArgumentCaptor.forClass(AdRevenueDaily.class);
        verify(mapper).insert(captor.capture());
        AdRevenueDaily saved = captor.getValue();
        assertEquals("SETTLED", saved.getDataSource());
        assertEquals(123_45L, saved.getIncomeCents());
        assertEquals(LocalDate.of(2026, 9, 1), saved.getBizDate(), "上半月应落在 1 号");
        assertNull(saved.getEcpmMicros(), "结算数据没有 eCPM，不该编造");
    }

    @Test
    @DisplayName("结算下半月落在 16 号（不是同月同一天）")
    void settlementSecondHalf_landsOn16th() {
        WeChatPublisherStatClient.SettlementRow done = new WeChatPublisherStatClient.SettlementRow();
        done.month = "202609";
        done.order = 2; // 下半月
        done.settStatus = 5; // 已付款
        WeChatPublisherStatClient.SlotRevenue sr = new WeChatPublisherStatClient.SlotRevenue();
        sr.adSlot = "SLOT_ID_WEAPP_BANNER";
        sr.settledRevenueCents = 1L;
        done.bySlot.add(sr);

        when(mapper.selectOne(any())).thenReturn(null);
        when(client.fetchSettlement(any(), any())).thenReturn(List.of(done));

        service.syncSettlements(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

        ArgumentCaptor<AdRevenueDaily> captor = ArgumentCaptor.forClass(AdRevenueDaily.class);
        verify(mapper).insert(captor.capture());
        assertEquals(LocalDate.of(2026, 9, 16), captor.getValue().getBizDate());
    }
}