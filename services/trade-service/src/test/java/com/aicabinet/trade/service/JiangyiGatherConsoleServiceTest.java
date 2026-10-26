package com.aicabinet.trade.service;

import com.aicabinet.trade.client.JiangyiGatherClient;
import com.aicabinet.trade.domain.JiangyiDevice;
import com.aicabinet.trade.dto.JiangyiGatherDtos.GatherEntryRequest;
import com.aicabinet.trade.mapper.JiangyiDeviceMapper;
import com.aicabinet.trade.service.JiangyiGatherConsoleService.ConsoleSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 采集台（§4.3.3–4.3.14 一条龙）单测：链路毫秒级同步跑，钉住步骤顺序、409/400 闸门、
 * 失败仍解锁恢复营业、清批次的「无 wait 才放行」前置（§4.3.14 文档原文约束）。
 */
@ExtendWith(MockitoExtension.class)
class JiangyiGatherConsoleServiceTest {

    private static final String DEVICE_ID = "EG943322621391";
    private static final String IDENTIFIER = "CQYB10720";

    @Mock private JiangyiGatherClient client;
    @Mock private JiangyiDeviceMapper deviceMapper;

    private final ObjectMapper mapper = new ObjectMapper();

    /** 毫秒级 timings 的服务：start() 起的真实后台链路秒级收场，测试 await 得住。 */
    private static final class FastConsoleService extends JiangyiGatherConsoleService {
        FastConsoleService(JiangyiGatherClient client, JiangyiDeviceMapper deviceMapper, String mobile) {
            super(client, deviceMapper, mobile);
        }

        @Override int doorTimeoutS() { return 1; }

        @Override int readyTimeoutS() { return 1; }

        @Override int uploadTimeoutS() { return 2; }

        @Override long pollMs() { return 1; }

        @Override long shootPollMs() { return 1; }

        @Override int clampShootSeconds(Integer shootSeconds) {
            return shootSeconds == null ? 45 : Math.max(1, Math.min(120, shootSeconds));
        }
    }

    private JiangyiDevice boundDevice() {
        JiangyiDevice d = new JiangyiDevice();
        d.setDeviceId(DEVICE_ID);
        d.setIdentifier(IDENTIFIER);
        d.setStatus("BOUND");
        return d;
    }

    private static GatherEntryRequest entry() {
        return new GatherEntryRequest(IDENTIFIER, 41175L, "康师傅冰红茶", "康师傅", "310ml", "bottle", "manual");
    }

    private void stubHappyChain() throws Exception {
        when(client.doorStatus(IDENTIFIER)).thenReturn(mapper.readTree("\"open\""));
        when(client.gatherProductInfo(any())).thenReturn(7955L);
        when(client.gatherStartCheck(eq(IDENTIFIER), eq(7955L))).thenReturn(mapper.readTree(
                "{\"cameraStatus\":\"yes\",\"configParamStatus\":\"yes\",\"start\":\"yes\"}"));
        when(client.gatherError(7955L)).thenReturn("no");
        when(client.gatherProducts(IDENTIFIER)).thenReturn(mapper.readTree("[]"));
        when(client.gatherGoOn(IDENTIFIER)).thenReturn(mapper.readTree("{\"goOnGather\":\"no\"}"));
    }

    private static void await(BooleanSupplier predicate) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (predicate.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        org.junit.jupiter.api.Assertions.fail("等待链路终态超时");
    }

    @Test
    void chainRunsInDocumentedOrderAndUnlocksWhenDone() throws Exception {
        JiangyiGatherConsoleService svc = new FastConsoleService(client, deviceMapper, "13800000000");
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice());
        stubHappyChain();
        when(client.uploadInfoNew(IDENTIFIER)).thenReturn(mapper.readTree(
                "[{\"id\":7955,\"productInfo\":\"康师傅_冰红茶_310ml\",\"status\":\"success\"}]"));

        ConsoleSnapshot snapshot = svc.start(DEVICE_ID, entry(), 1);
        await(() -> svc.state(DEVICE_ID) != null && svc.state(DEVICE_ID).terminal());

        ConsoleSnapshot done = svc.state(DEVICE_ID);
        assertEquals(JiangyiGatherConsoleService.PHASE_DONE, done.phase());
        assertEquals(1, done.shootSeconds());
        InOrder inOrder = inOrder(client);
        inOrder.verify(client).gatherOpenDoor(IDENTIFIER, "");
        inOrder.verify(client).doorStatus(IDENTIFIER);
        inOrder.verify(client).gatherProductInfo(entry());
        inOrder.verify(client).gatherConfirmStart(IDENTIFIER, 7955L);
        inOrder.verify(client).gatherEndOne(IDENTIFIER, 7955L);
        inOrder.verify(client).gatherProducts(IDENTIFIER);
        inOrder.verify(client).gatherGoOn(IDENTIFIER);
        inOrder.verify(client).gatherEndAll(IDENTIFIER, "13800000000");
        inOrder.verify(client, atLeastOnce()).uploadInfoNew(IDENTIFIER);
        verify(deviceMapper).markGatherLocked(eq(DEVICE_ID), any());
        verify(deviceMapper).markGatherUnlocked(eq(DEVICE_ID), any());
    }

    @Test
    void vendorFailureStillUnlocksAndRecordsPhase() {
        JiangyiGatherConsoleService svc = new FastConsoleService(client, deviceMapper, "13800000000");
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice());
        org.mockito.Mockito.doThrow(new IllegalStateException("开门失败"))
                .when(client).gatherOpenDoor(any(), any());

        svc.start(DEVICE_ID, entry(), null);
        await(() -> svc.state(DEVICE_ID) != null && svc.state(DEVICE_ID).terminal());

        assertEquals(JiangyiGatherConsoleService.PHASE_FAILED, svc.state(DEVICE_ID).phase());
        verify(deviceMapper).markGatherUnlocked(eq(DEVICE_ID), any());
    }

    @Test
    void clearBatchIsBlockedWhileAnyUploadRowIsWaiting() throws Exception {
        JiangyiGatherConsoleService svc = new FastConsoleService(client, deviceMapper, "13800000000");
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice());
        stubHappyChain();
        when(client.uploadInfoNew(IDENTIFIER)).thenReturn(mapper.readTree(
                "[{\"id\":7955,\"status\":\"wait\"}]"));

        svc.start(DEVICE_ID, entry(), 1);
        // 上传轮询 2s 超时停在 UPLOADING（有 wait 未清），此刻清批次必须被 §4.3.14 前置拦下
        await(() -> svc.state(DEVICE_ID) != null
                && JiangyiGatherConsoleService.PHASE_UPLOADING.equals(svc.state(DEVICE_ID).phase())
                && svc.state(DEVICE_ID).finishedAt() != null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> svc.clearBatch(DEVICE_ID));
        assertEquals(409, ex.getStatusCode().value());
        verify(client, atLeastOnce()).uploadInfoNew(IDENTIFIER);
        verify(client, org.mockito.Mockito.never()).deleteBatchNoNew(IDENTIFIER);
    }

    @Test
    void clearBatchPassesWhenNoRowIsWaiting() throws Exception {
        JiangyiGatherConsoleService svc = new FastConsoleService(client, deviceMapper, "13800000000");
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice());
        stubHappyChain();
        when(client.uploadInfoNew(IDENTIFIER)).thenReturn(mapper.readTree(
                "[{\"id\":7955,\"status\":\"success\"}]"));

        svc.start(DEVICE_ID, entry(), 1);
        await(() -> svc.state(DEVICE_ID) != null && svc.state(DEVICE_ID).terminal());

        svc.clearBatch(DEVICE_ID);
        verify(client).deleteBatchNoNew(IDENTIFIER);
    }

    @Test
    void startRejectsUnknownDeviceInvalidEntryAndShootOutOfRange() {
        JiangyiGatherConsoleService svc = new FastConsoleService(client, deviceMapper, "13800000000");

        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> svc.start("unknown-device", entry(), null)).getStatusCode().value());

        JiangyiDevice device = boundDevice();
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device);

        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> svc.start(DEVICE_ID, new GatherEntryRequest(IDENTIFIER, 0, "x", null, null, null, null), null))
                .getStatusCode().value());

        device.setGatherLockedAt(java.time.Instant.now());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> svc.start(DEVICE_ID, entry(), 10)).getStatusCode().value());

        device.setGatherLockedAt(null);
        when(client.doorStatus(IDENTIFIER)).thenAnswer(inv -> null); // 后台线程等门超时自灭
        ConsoleSnapshot snapshot = svc.start(DEVICE_ID, entry(), 999);
        assertEquals(120, snapshot.shootSeconds(), "拍摄窗口夹取到 [20,120]");
        assertEquals(JiangyiGatherConsoleService.PHASE_WAIT_DOOR, snapshot.phase());

        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> svc.start(DEVICE_ID, entry(), null)).getStatusCode().value());
        await(() -> svc.state(DEVICE_ID) != null && svc.state(DEVICE_ID).terminal());
    }
}
