package com.aicabinet.trade.service;

import com.aicabinet.trade.client.JiangyiGatherClient;
import com.aicabinet.trade.domain.JiangyiDevice;
import com.aicabinet.trade.domain.JiangyiTrainingTicket;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.dto.JiangyiGatherDtos.JiangyiModelFile;
import com.aicabinet.trade.dto.JiangyiGatherDtos.MerchantTrainingStatusDto;
import com.aicabinet.trade.dto.JiangyiGatherDtos.LearnableProduct;
import com.aicabinet.trade.mapper.JiangyiDeviceMapper;
import com.aicabinet.trade.mapper.JiangyiSkuJiangyiLinkMapper;
import com.aicabinet.trade.mapper.JiangyiTrainingTicketMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 采集编排（CB-023 范围 C）单测：finishNotify 防伪造（未知凭据静默丢弃 / 逐张 CAS 一次性消费）、
 * <b>成功回执即收口</b>（可学习名单只留痕、读面失败也不拦，采集锁必退）、
 * 批量触发的集合粒度单次调用、startGather 开门失败回滚锁、
 * startTraining 幂等与公网回调基址前置校验。
 */
@ExtendWith(MockitoExtension.class)
class JiangyiGatherServiceTest {

    private static final String DEVICE_ID = "100000000001";
    private static final String SKU_ID = "SKU-WATER-001";
    private static final String TICKET_ID = "ticket-uuid-1";
    private static final Long ROW_ID = 11L;

    @Mock private JiangyiGatherClient jiangyiGatherClient;
    @Mock private JiangyiDeviceMapper jiangyiDeviceMapper;
    @Mock private JiangyiTrainingTicketMapper ticketMapper;
    @Mock private JiangyiSkuJiangyiLinkMapper linkMapper;
    @Mock private SkuCatalogMapper skuCatalogMapper;

    private JiangyiGatherService service(String publicBaseUrl) {
        return new JiangyiGatherService(jiangyiGatherClient, jiangyiDeviceMapper,
                ticketMapper, linkMapper, skuCatalogMapper, publicBaseUrl);
    }

    private JiangyiDevice boundDevice() {
        JiangyiDevice d = new JiangyiDevice();
        d.setDeviceId(DEVICE_ID);
        d.setStatus("BOUND");
        d.setIdentifier("CQYB11253");
        return d;
    }

    private JiangyiTrainingTicket pendingTicket(String jiangyiProductId) {
        return pendingTicket(ROW_ID, SKU_ID, jiangyiProductId, TICKET_ID);
    }

    private JiangyiTrainingTicket pendingTicket(Long rowId, String skuId, String jiangyiProductId,
                                                String finishNotifyId) {
        JiangyiTrainingTicket t = new JiangyiTrainingTicket();
        t.setId(rowId);
        t.setFinishNotifyId(finishNotifyId);
        t.setDeviceId(DEVICE_ID);
        t.setSkuId(skuId);
        t.setJiangyiProductId(jiangyiProductId);
        t.setStatus("PENDING");
        return t;
    }

    // ---------- finishNotify 防伪 ----------

    @Test
    void finishNotify_unknownIdIsDroppedSilently() {
        when(ticketMapper.listByFinishNotifyId("forged-id")).thenReturn(List.of());

        service("https://public").handleFinishNotify("forged-id", "success");

        verify(ticketMapper, never()).finishById(anyLong(), anyBoolean(), any());
    }

    @Test
    void finishNotify_nonPendingTicketIsDropped() {
        JiangyiTrainingTicket consumed = pendingTicket("88");
        consumed.setStatus("FINISHED");
        when(ticketMapper.listByFinishNotifyId(TICKET_ID)).thenReturn(List.of(consumed));

        service("https://public").handleFinishNotify(TICKET_ID, "success");

        verify(ticketMapper, never()).finishById(anyLong(), anyBoolean(), any());
    }

    @Test
    void finishNotify_failureMsgMarksTicketFailed() {
        when(ticketMapper.listByFinishNotifyId(TICKET_ID)).thenReturn(List.of(pendingTicket("88")));

        service("https://public").handleFinishNotify(TICKET_ID, "failed: quality");

        verify(ticketMapper).finishById(eq(ROW_ID), eq(false), any());
        verify(jiangyiDeviceMapper, never()).markGatherUnlocked(anyString(), any());
    }

    @Test
    void finishNotify_successConfirmsTicketEvenWhenProductMissingFromLearnableList() {
        // 🔴 2026-10-27 语义纠偏：§4.4.2 是「可学习/采集成档」名单，不是学成证据。
        // 商品不在列（厂商学成后出列，或名单读面滞后）也必须收口——否则采集锁永不释放、柜机不能营业。
        when(ticketMapper.listByFinishNotifyId(TICKET_ID)).thenReturn(List.of(pendingTicket("88")));
        when(jiangyiGatherClient.learnableProducts()).thenReturn(List.of(
                new LearnableProduct(99L, "可乐", "可乐 330ml", "6900000000009")));
        when(ticketMapper.finishById(eq(ROW_ID), eq(true), any())).thenReturn(1);
        JiangyiDevice locked = boundDevice();
        locked.setGatherLockedAt(java.time.Instant.now());
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(locked);

        service("https://public").handleFinishNotify(TICKET_ID, "success");

        verify(ticketMapper).finishById(eq(ROW_ID), eq(true), any());
        verify(jiangyiDeviceMapper).markGatherUnlocked(eq(DEVICE_ID), any());
    }

    @Test
    void finishNotify_learnableListReadFailureDoesNotBlockTicketOrLock() {
        // 名单读面挂了（厂商 503 等）绝不能把已到达的成功回执卡在门外
        when(ticketMapper.listByFinishNotifyId(TICKET_ID)).thenReturn(List.of(pendingTicket("88")));
        when(jiangyiGatherClient.learnableProducts())
                .thenThrow(new IllegalStateException("将邑 503 设备不在线"));
        when(ticketMapper.finishById(eq(ROW_ID), eq(true), any())).thenReturn(1);
        JiangyiDevice locked = boundDevice();
        locked.setGatherLockedAt(java.time.Instant.now());
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(locked);

        service("https://public").handleFinishNotify(TICKET_ID, "success");

        verify(ticketMapper).finishById(eq(ROW_ID), eq(true), any());
        verify(jiangyiDeviceMapper).markGatherUnlocked(eq(DEVICE_ID), any());
    }

    @Test
    void finishNotify_successAutoExitsGatherMode() {
        // 学习完成的唯一可见结果：成功回执到达 ⇒ 票据 FINISHED + 立刻恢复营业
        when(ticketMapper.listByFinishNotifyId(TICKET_ID)).thenReturn(List.of(pendingTicket("88")));
        when(jiangyiGatherClient.learnableProducts()).thenReturn(List.of(
                new LearnableProduct(88L, "农夫山泉 550ml", "农夫山泉 550ml", "6901234567890")));
        when(ticketMapper.finishById(eq(ROW_ID), eq(true), any())).thenReturn(1);
        JiangyiDevice locked = boundDevice();
        locked.setGatherLockedAt(java.time.Instant.now());
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(locked);

        service("https://public").handleFinishNotify(TICKET_ID, "success");

        verify(jiangyiDeviceMapper).markGatherUnlocked(eq(DEVICE_ID), any());
    }

    @Test
    void finishNotify_successWhenAlreadyExitedDoesNotTouchLock() {
        when(ticketMapper.listByFinishNotifyId(TICKET_ID)).thenReturn(List.of(pendingTicket("88")));
        when(jiangyiGatherClient.learnableProducts()).thenReturn(List.of(
                new LearnableProduct(88L, "农夫山泉 550ml", "农夫山泉 550ml", "6901234567890")));
        when(ticketMapper.finishById(eq(ROW_ID), eq(true), any())).thenReturn(1);
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice());

        service("https://public").handleFinishNotify(TICKET_ID, "success");

        verify(jiangyiDeviceMapper, never()).markGatherUnlocked(anyString(), any());
    }

    @Test
    void finishNotify_concurrentDuplicateDoesNotUnlockAgain() {
        // 两条重复回调并发：CAS 只让一条拿到终态，另一条不得再动设备锁
        when(ticketMapper.listByFinishNotifyId(TICKET_ID)).thenReturn(List.of(pendingTicket("88")));
        when(jiangyiGatherClient.learnableProducts()).thenReturn(List.of(
                new LearnableProduct(88L, "农夫山泉 550ml", "农夫山泉 550ml", "6901234567890")));
        when(ticketMapper.finishById(eq(ROW_ID), eq(true), any())).thenReturn(0);

        service("https://public").handleFinishNotify(TICKET_ID, "success");

        verify(jiangyiDeviceMapper, never()).markGatherUnlocked(anyString(), any());
    }

    @Test
    void finishNotify_batchFansOutEveryPendingTicket() {
        // 批量一次训练扇出多张票据（§4.4.3 是集合粒度，厂商不会分批发回执）：每张都收口，采集锁只退一次
        JiangyiTrainingTicket t1 = pendingTicket(1L, "SKU-A", "88", TICKET_ID);
        JiangyiTrainingTicket t2 = pendingTicket(2L, "SKU-B", "99", TICKET_ID);
        when(ticketMapper.listByFinishNotifyId(TICKET_ID)).thenReturn(List.of(t1, t2));
        when(jiangyiGatherClient.learnableProducts()).thenReturn(List.of(
                new LearnableProduct(88L, "农夫山泉", "农夫山泉 550ml", "6901234567890")));
        when(ticketMapper.finishById(eq(1L), eq(true), any())).thenReturn(1);
        when(ticketMapper.finishById(eq(2L), eq(true), any())).thenReturn(1);
        JiangyiDevice locked = boundDevice();
        locked.setGatherLockedAt(java.time.Instant.now());
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(locked);

        service("https://public").handleFinishNotify(TICKET_ID, "success");

        verify(ticketMapper).finishById(eq(1L), eq(true), any());
        verify(ticketMapper).finishById(eq(2L), eq(true), any());
        verify(jiangyiDeviceMapper, times(1)).markGatherUnlocked(eq(DEVICE_ID), any());
    }

    // ---------- startTraining ----------

    @Test
    void startTraining_withoutPublicBaseUrlIsRejected() {
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service("").startTraining(DEVICE_ID, SKU_ID, "model"));
        assertEquals(409, e.getStatusCode().value());
        assertTrue(e.getReason().contains("JIANGYI_PUBLIC_BASE_URL"));
    }

    @Test
    void startTraining_pendingTicketMakesCallIdempotent() {
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice());
        JiangyiTrainingTicket existing = pendingTicket("88");
        when(ticketMapper.findPendingBySku(SKU_ID)).thenReturn(Optional.of(existing));

        JiangyiTrainingTicket result = service("https://public")
                .startTraining(DEVICE_ID, SKU_ID, "model");

        assertEquals(TICKET_ID, result.getFinishNotifyId());
        verify(ticketMapper, never()).insert(any(JiangyiTrainingTicket.class));
        verify(jiangyiGatherClient, never()).commitTraining(anyString(), anyString(), anyString());
    }

    @Test
    void startTraining_commitFailureClosesTicket() {
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice());
        when(ticketMapper.findPendingBySku(SKU_ID)).thenReturn(Optional.empty());
        when(linkMapper.listBySku(SKU_ID)).thenReturn(List.of());
        org.mockito.Mockito.doThrow(new IllegalStateException("将邑 500"))
                .when(jiangyiGatherClient).commitTraining(anyString(), anyString(), anyString());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service("https://public").startTraining(DEVICE_ID, SKU_ID, "model"));
        assertEquals(502, e.getStatusCode().value());
        // ticket 已 insert 但随即 fail（一次性凭据不残留 PENDING）
        verify(ticketMapper).insert(any(JiangyiTrainingTicket.class));
        verify(ticketMapper).finishById(any(), eq(false), any());
    }

    // ---------- startTrainingBatch ----------

    @Test
    void startTrainingBatch_oneVendorCallWithSharedNotifyId() {
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice());
        when(linkMapper.listBySku("SKU-A")).thenReturn(List.of(link("SKU-A", "88")));
        when(linkMapper.listBySku("SKU-B")).thenReturn(List.of(link("SKU-B", "99")));
        when(ticketMapper.listPendingBySkus(List.of("SKU-A", "SKU-B"))).thenReturn(List.of());
        when(jiangyiGatherClient.modelFiles()).thenReturn(List.of(model("模型v1")));

        JiangyiGatherService.BatchTrainingResult result = service("https://public")
                .startTrainingBatch(DEVICE_ID, List.of("SKU-A", "SKU-B"), "模型v1");

        assertEquals(2, result.count());
        assertEquals(List.of("SKU-A", "SKU-B"),
                result.items().stream().map(JiangyiGatherService.BatchTrainingItem::skuId).toList());
        // 集合粒度：一次厂商调用，多张票据共享同一个 finishNotifyId
        verify(jiangyiGatherClient, times(1))
                .commitTraining(eq("模型v1"), eq("https://public/jiangyi/api/gather-finish-notify"),
                        anyString());
        verify(ticketMapper, times(2)).insert(any(JiangyiTrainingTicket.class));
    }

    @Test
    void startTrainingBatch_rejectsInFlightSku() {
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice());
        when(linkMapper.listBySku("SKU-A")).thenReturn(List.of(link("SKU-A", "88")));
        when(ticketMapper.listPendingBySkus(List.of("SKU-A"))).thenReturn(List.of(pendingTicket("88")));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service("https://public").startTrainingBatch(DEVICE_ID, List.of("SKU-A"), "模型v1"));
        assertEquals(409, e.getStatusCode().value());
        assertTrue(e.getReason().contains("在途"));
        verify(jiangyiGatherClient, never()).commitTraining(anyString(), anyString(), anyString());
    }

    @Test
    void startTrainingBatch_rejectsUnlinkedSku() {
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice());
        when(linkMapper.listBySku("SKU-A")).thenReturn(List.of());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service("https://public").startTrainingBatch(DEVICE_ID, List.of("SKU-A"), "模型v1"));
        assertEquals(409, e.getStatusCode().value());
        assertTrue(e.getReason().contains("未挂将邑商品"));
        verify(ticketMapper, never()).insert(any(JiangyiTrainingTicket.class));
    }

    @Test
    void startTrainingBatch_rejectsUnknownModelWhenListAvailable() {
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice());
        when(linkMapper.listBySku("SKU-A")).thenReturn(List.of(link("SKU-A", "88")));
        when(ticketMapper.listPendingBySkus(List.of("SKU-A"))).thenReturn(List.of());
        when(jiangyiGatherClient.modelFiles()).thenReturn(List.of(model("模型v1")));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service("https://public").startTrainingBatch(DEVICE_ID, List.of("SKU-A"), "模型v2"));
        assertEquals(409, e.getStatusCode().value());
        assertTrue(e.getReason().contains("模型v1"));
        verify(jiangyiGatherClient, never()).commitTraining(anyString(), anyString(), anyString());
    }

    @Test
    void startTrainingBatch_acceptsNameFoundOnlyInCustomerModelName() {
        // 台账 H-33：厂商可能把我方提交的模型名改写成内部命名，初命名只在 customerModelName 列——
        // 软锁只认 modelName 会把「重训同一个模型」判成非法名字而 409 自己
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice());
        when(linkMapper.listBySku("SKU-A")).thenReturn(List.of(link("SKU-A", "88")));
        when(ticketMapper.listPendingBySkus(List.of("SKU-A"))).thenReturn(List.of());
        when(jiangyiGatherClient.modelFiles()).thenReturn(
                List.of(modelRenamedByVendor("426_20261027_76_1", "模型v1")));

        JiangyiGatherService.BatchTrainingResult result = service("https://public")
                .startTrainingBatch(DEVICE_ID, List.of("SKU-A"), "模型v1");

        assertEquals(1, result.count());
        verify(jiangyiGatherClient).commitTraining(eq("模型v1"), anyString(), anyString());
    }

    @Test
    void startTrainingBatch_allowsAnyModelWhenVendorListEmpty() {
        // 首次学习：厂商模型列表为空（§4.4.4 回 []），软锁必须放行，否则学习永远发不出去
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice());
        when(linkMapper.listBySku("SKU-A")).thenReturn(List.of(link("SKU-A", "88")));
        when(ticketMapper.listPendingBySkus(List.of("SKU-A"))).thenReturn(List.of());
        when(jiangyiGatherClient.modelFiles()).thenReturn(List.of());

        JiangyiGatherService.BatchTrainingResult result = service("https://public")
                .startTrainingBatch(DEVICE_ID, List.of("SKU-A"), "模型v1");

        assertEquals(1, result.count());
        verify(jiangyiGatherClient).commitTraining(eq("模型v1"), anyString(), anyString());
    }

    @Test
    void startTrainingBatch_commitFailureClosesEveryTicket() {
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice());
        when(linkMapper.listBySku("SKU-A")).thenReturn(List.of(link("SKU-A", "88")));
        when(linkMapper.listBySku("SKU-B")).thenReturn(List.of(link("SKU-B", "99")));
        when(ticketMapper.listPendingBySkus(List.of("SKU-A", "SKU-B"))).thenReturn(List.of());
        when(jiangyiGatherClient.modelFiles()).thenReturn(List.of());
        org.mockito.Mockito.doThrow(new IllegalStateException("将邑 503"))
                .when(jiangyiGatherClient).commitTraining(anyString(), anyString(), anyString());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service("https://public")
                        .startTrainingBatch(DEVICE_ID, List.of("SKU-A", "SKU-B"), "模型v1"));
        assertEquals(502, e.getStatusCode().value());
        verify(ticketMapper, times(2)).finishById(any(), eq(false), any());
    }

    // ---------- 商户端只读学习进度 ----------

    @Test
    void merchantTrainingStatus_withoutTicketsIsEmpty() {
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice());
        when(ticketMapper.latestByDevice(DEVICE_ID)).thenReturn(Optional.empty());

        MerchantTrainingStatusDto status = service("https://public").merchantTrainingStatus(DEVICE_ID);

        assertTrue(status.bound());
        assertFalse(status.gatherLocked());
        assertNull(status.status());
        assertTrue(status.items().isEmpty());
    }

    @Test
    void merchantTrainingStatus_listsWholeBatchWithSkuNames() {
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice());
        JiangyiTrainingTicket t1 = pendingTicket(1L, "SKU-A", "88", TICKET_ID);
        JiangyiTrainingTicket t2 = pendingTicket(2L, "SKU-B", "99", TICKET_ID);
        t2.setStatus("FINISHED");
        t1.setCreatedAt(java.time.Instant.parse("2026-10-26T08:00:00Z"));
        t2.setCreatedAt(java.time.Instant.parse("2026-10-26T08:00:00Z"));
        t2.setFinishedAt(java.time.Instant.parse("2026-10-26T08:05:00Z"));
        when(ticketMapper.latestByDevice(DEVICE_ID)).thenReturn(Optional.of(t2));
        when(ticketMapper.listByFinishNotifyId(TICKET_ID)).thenReturn(List.of(t1, t2));
        SkuCatalog a = new SkuCatalog();
        a.setSkuId("SKU-A");
        a.setSkuName("农夫山泉 550ml");
        SkuCatalog b = new SkuCatalog();
        b.setSkuId("SKU-B");
        b.setSkuName("可乐 330ml");
        when(skuCatalogMapper.findBySkuIds(List.of("SKU-A", "SKU-B"))).thenReturn(List.of(a, b));

        MerchantTrainingStatusDto status = service("https://public").merchantTrainingStatus(DEVICE_ID);

        assertEquals("PENDING", status.status());
        assertEquals(2, status.items().size());
        assertEquals("农夫山泉 550ml", status.items().get(0).skuName());
        assertEquals("FINISHED", status.items().get(1).status());
    }

    @Test
    void merchantTrainingStatus_unboundDeviceHidesProgress() {
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(null);

        MerchantTrainingStatusDto status = service("https://public").merchantTrainingStatus(DEVICE_ID);

        assertFalse(status.bound());
        assertTrue(status.items().isEmpty());
        verify(ticketMapper, never()).latestByDevice(anyString());
    }

    // ---------- 采集模式锁 ----------

    @Test
    void startGather_alreadyLockedIs409() {
        JiangyiDevice d = boundDevice();
        d.setGatherLockedAt(java.time.Instant.now());
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(d);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service("https://public").startGather(DEVICE_ID, "1"));
        assertEquals(409, e.getStatusCode().value());
        verify(jiangyiGatherClient, never()).gatherOpenDoor(anyString(), anyString());
    }

    @Test
    void startGather_openDoorFailureRollsBackLock() {
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice());
        org.mockito.Mockito.doThrow(new IllegalStateException("将邑开门失败"))
                .when(jiangyiGatherClient).gatherOpenDoor(eq("CQYB11253"), anyString());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service("https://public").startGather(DEVICE_ID, "1"));
        assertEquals(502, e.getStatusCode().value());
        // 成对语义：置锁 → 失败必须回滚
        verify(jiangyiDeviceMapper).markGatherLocked(eq(DEVICE_ID), any());
        verify(jiangyiDeviceMapper).markGatherUnlocked(eq(DEVICE_ID), any());
    }

    @Test
    void startGather_successLocksAndOpens() {
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice());

        service("https://public").startGather(DEVICE_ID, "1");

        verify(jiangyiDeviceMapper).markGatherLocked(eq(DEVICE_ID), any());
        verify(jiangyiGatherClient).gatherOpenDoor("CQYB11253", "1");
        verify(jiangyiDeviceMapper, never()).markGatherUnlocked(anyString(), any());
    }

    @Test
    void isGatherLocked_falseWhenDeviceMissing() {
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(null);
        assertFalse(service("https://public").isGatherLocked(DEVICE_ID));
    }

    @Test
    void progress_exposesLatestTrainingTicket() {
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice());
        JiangyiTrainingTicket finished = pendingTicket("88");
        finished.setStatus("FINISHED");
        finished.setCreatedAt(java.time.Instant.now());
        finished.setFinishedAt(java.time.Instant.now());
        when(ticketMapper.latestByDevice(DEVICE_ID)).thenReturn(Optional.of(finished));

        JiangyiGatherService.GatherProgress p = service("https://public").progress(DEVICE_ID);

        assertFalse(p.gatherLocked());
        assertEquals("FINISHED", p.lastTraining().status());
        assertEquals(SKU_ID, p.lastTraining().skuId());
    }

    @Test
    void progress_lastTrainingNullWhenDeviceNeverTrained() {
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice());
        when(ticketMapper.latestByDevice(DEVICE_ID)).thenReturn(Optional.empty());

        assertNull(service("https://public").progress(DEVICE_ID).lastTraining());
    }

    private static com.aicabinet.trade.domain.JiangyiSkuJiangyiLink link(String skuId, String productId) {
        com.aicabinet.trade.domain.JiangyiSkuJiangyiLink l =
                new com.aicabinet.trade.domain.JiangyiSkuJiangyiLink();
        l.setSkuId(skuId);
        l.setJiangyiProductId(productId);
        return l;
    }

    private static JiangyiModelFile model(String modelName) {
        return new JiangyiModelFile(1L, modelName, null, "https://cdn/model.rknn",
                "88", "https://cdn/classes.txt", 4);
    }

    /** 厂商把我方提交的名字改写成内部命名时，初命名只落在 customerModelName 列（台账 H-33）。 */
    private static JiangyiModelFile modelRenamedByVendor(String modelName, String customerModelName) {
        return new JiangyiModelFile(1L, modelName, customerModelName, "https://cdn/model.rknn",
                "76", "https://cdn/classes.txt", 4);
    }
}
