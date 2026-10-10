package com.aicabinet.trade.service;

import com.aicabinet.trade.client.JiangyiGatherClient;
import com.aicabinet.trade.domain.JiangyiDevice;
import com.aicabinet.trade.domain.JiangyiTrainingTicket;
import com.aicabinet.trade.dto.JiangyiGatherDtos.TrainedProduct;
import com.aicabinet.trade.mapper.JiangyiDeviceMapper;
import com.aicabinet.trade.mapper.JiangyiSkuJiangyiLinkMapper;
import com.aicabinet.trade.mapper.JiangyiTrainingTicketMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 采集编排（CB-023 范围 C）单测：finishNotify 三重防伪（未知凭据静默丢弃 /
 * trainedProducts 交叉验证 / CAS 一次性消费）、startGather 开门失败回滚锁、
 * startTraining 幂等与公网回调基址前置校验。
 */
@ExtendWith(MockitoExtension.class)
class JiangyiGatherServiceTest {

    private static final String DEVICE_ID = "100000000001";
    private static final String SKU_ID = "SKU-WATER-001";
    private static final String TICKET_ID = "ticket-uuid-1";

    @Mock private JiangyiGatherClient jiangyiGatherClient;
    @Mock private JiangyiDeviceMapper jiangyiDeviceMapper;
    @Mock private JiangyiTrainingTicketMapper ticketMapper;
    @Mock private JiangyiSkuJiangyiLinkMapper linkMapper;

    private JiangyiGatherService service(String publicBaseUrl) {
        return new JiangyiGatherService(jiangyiGatherClient, jiangyiDeviceMapper,
                ticketMapper, linkMapper, publicBaseUrl);
    }

    private JiangyiDevice boundDevice() {
        JiangyiDevice d = new JiangyiDevice();
        d.setDeviceId(DEVICE_ID);
        d.setStatus("BOUND");
        d.setIdentifier("CQYB11253");
        return d;
    }

    private JiangyiTrainingTicket pendingTicket(String jiangyiProductId) {
        JiangyiTrainingTicket t = new JiangyiTrainingTicket();
        t.setFinishNotifyId(TICKET_ID);
        t.setDeviceId(DEVICE_ID);
        t.setSkuId(SKU_ID);
        t.setJiangyiProductId(jiangyiProductId);
        t.setStatus("PENDING");
        return t;
    }

    // ---------- finishNotify 防伪 ----------

    @Test
    void finishNotify_unknownIdIsDroppedSilently() {
        when(ticketMapper.byFinishNotifyId("forged-id")).thenReturn(Optional.empty());

        service("https://public").handleFinishNotify("forged-id", "success");

        verify(ticketMapper, never()).finish(anyString(), anyBoolean(), any());
    }

    @Test
    void finishNotify_nonPendingTicketIsDropped() {
        JiangyiTrainingTicket consumed = pendingTicket("88");
        consumed.setStatus("FINISHED");
        when(ticketMapper.byFinishNotifyId(TICKET_ID)).thenReturn(Optional.of(consumed));

        service("https://public").handleFinishNotify(TICKET_ID, "success");

        verify(ticketMapper, never()).finish(anyString(), anyBoolean(), any());
    }

    @Test
    void finishNotify_failureMsgMarksTicketFailed() {
        when(ticketMapper.byFinishNotifyId(TICKET_ID)).thenReturn(Optional.of(pendingTicket("88")));

        service("https://public").handleFinishNotify(TICKET_ID, "failed: quality");

        verify(ticketMapper).finish(eq(TICKET_ID), eq(false), any());
    }

    @Test
    void finishNotify_successCrossValidatesAgainstTrainedProducts() {
        when(ticketMapper.byFinishNotifyId(TICKET_ID)).thenReturn(Optional.of(pendingTicket("88")));
        when(jiangyiGatherClient.trainedProducts()).thenReturn(List.of(
                new TrainedProduct(88L, "农夫山泉 550ml", "农夫山泉 550ml", "6901234567890")));

        service("https://public").handleFinishNotify(TICKET_ID, "success");

        verify(ticketMapper).finish(eq(TICKET_ID), eq(true), any());
    }

    @Test
    void finishNotify_successButNotYetTrainedKeepsPending() {
        // 异步延迟容忍：回执到了但将邑 trained 列表还没有 → 保留 PENDING 等下轮，不假成功
        when(ticketMapper.byFinishNotifyId(TICKET_ID)).thenReturn(Optional.of(pendingTicket("88")));
        when(jiangyiGatherClient.trainedProducts()).thenReturn(List.of(
                new TrainedProduct(99L, "可乐", "可乐 330ml", "6900000000009")));

        service("https://public").handleFinishNotify(TICKET_ID, "success");

        verify(ticketMapper, never()).finish(anyString(), anyBoolean(), any());
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
        verify(ticketMapper).finish(anyString(), eq(false), any());
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
}
