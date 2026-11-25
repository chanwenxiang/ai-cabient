package com.aicabinet.jiangyi.api;

import com.aicabinet.jiangyi.client.TradeInternalClient;
import com.aicabinet.jiangyi.tracker.CommandTracker;
import com.aicabinet.jiangyi.ws.DeviceWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelPushControllerTest {

    @Mock private TradeInternalClient tradeInternalClient;
    @Mock private DeviceWebSocketHandler deviceWebSocketHandler;
    @Mock private CommandTracker commandTracker;

    @Test
    void modelPush_registersBeforeSendSoEarlyAckCanComplete() {
        ModelPushController controller = new ModelPushController(
                tradeInternalClient, deviceWebSocketHandler, commandTracker);
        TradeInternalClient.JiangyiDeviceView device = new TradeInternalClient.JiangyiDeviceView(
                "DEV-1", "SN-1", "ID-1", "model", "v1", "BOUND", 1L, null, null, true);
        when(tradeInternalClient.device("DEV-1")).thenReturn(Optional.of(device));
        when(deviceWebSocketHandler.isOnline("ID-1")).thenReturn(true);
        when(commandTracker.register(CommandTracker.Kind.MODEL, "9", "DEV-1", "ID-1")).thenReturn(true);
        when(deviceWebSocketHandler.sendModelUpdate(eq("ID-1"), eq("model"), any(), any(), eq(1)))
                .thenReturn(true);

        controller.modelPush("DEV-1", new ModelPushController.ModelPushRequest(
                9L, "model", "https://example.test/model", "https://example.test/classes", 1));

        InOrder ordered = inOrder(commandTracker, deviceWebSocketHandler);
        ordered.verify(commandTracker).register(CommandTracker.Kind.MODEL, "9", "DEV-1", "ID-1");
        ordered.verify(deviceWebSocketHandler).sendModelUpdate(eq("ID-1"), eq("model"), any(), any(), eq(1));
    }

    @Test
    void modelPush_sendFailureEvictsTracking() {
        ModelPushController controller = new ModelPushController(
                tradeInternalClient, deviceWebSocketHandler, commandTracker);
        TradeInternalClient.JiangyiDeviceView device = new TradeInternalClient.JiangyiDeviceView(
                "DEV-1", "SN-1", "ID-1", "model", "v1", "BOUND", 1L, null, null, true);
        when(tradeInternalClient.device("DEV-1")).thenReturn(Optional.of(device));
        when(deviceWebSocketHandler.isOnline("ID-1")).thenReturn(true);
        when(commandTracker.register(CommandTracker.Kind.MODEL, "9", "DEV-1", "ID-1")).thenReturn(true);
        when(deviceWebSocketHandler.sendModelUpdate(any(), any(), any(), any(), anyInt())).thenReturn(false);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.modelPush("DEV-1", new ModelPushController.ModelPushRequest(
                        9L, "model", "url", "text", 1)));

        if (error.getStatusCode() != HttpStatus.SERVICE_UNAVAILABLE) throw new AssertionError(error);
        verify(commandTracker).evict(CommandTracker.Kind.MODEL, "9");
        verify(commandTracker, never()).completeByDevice(any(), any());
    }

    @Test
    void modelPush_pendingDeploymentOnDevice_rejectsWithoutSending() {
        ModelPushController controller = new ModelPushController(
                tradeInternalClient, deviceWebSocketHandler, commandTracker);
        TradeInternalClient.JiangyiDeviceView device = new TradeInternalClient.JiangyiDeviceView(
                "DEV-1", "SN-1", "ID-1", "model", "v1", "BOUND", 1L, null, null, true);
        when(tradeInternalClient.device("DEV-1")).thenReturn(Optional.of(device));
        when(deviceWebSocketHandler.isOnline("ID-1")).thenReturn(true);
        when(commandTracker.register(CommandTracker.Kind.MODEL, "10", "DEV-1", "ID-1")).thenReturn(false);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.modelPush("DEV-1", new ModelPushController.ModelPushRequest(
                        10L, "model", "url", "text", 1)));

        if (error.getStatusCode() != HttpStatus.CONFLICT) throw new AssertionError(error);
        verify(deviceWebSocketHandler, never()).sendModelUpdate(any(), any(), any(), any(), anyInt());
        verify(commandTracker, never()).evict(any(), any());
    }

    @Test
    void modelPush_sendExceptionEvictsTracking() {
        ModelPushController controller = new ModelPushController(
                tradeInternalClient, deviceWebSocketHandler, commandTracker);
        TradeInternalClient.JiangyiDeviceView device = new TradeInternalClient.JiangyiDeviceView(
                "DEV-1", "SN-1", "ID-1", "model", "v1", "BOUND", 1L, null, null, true);
        when(tradeInternalClient.device("DEV-1")).thenReturn(Optional.of(device));
        when(deviceWebSocketHandler.isOnline("ID-1")).thenReturn(true);
        when(commandTracker.register(CommandTracker.Kind.MODEL, "9", "DEV-1", "ID-1")).thenReturn(true);
        when(deviceWebSocketHandler.sendModelUpdate(any(), any(), any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("ws closed"));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.modelPush("DEV-1", new ModelPushController.ModelPushRequest(
                        9L, "model", "url", "text", 1)));

        if (error.getStatusCode() != HttpStatus.SERVICE_UNAVAILABLE) throw new AssertionError(error);
        verify(commandTracker).evict(CommandTracker.Kind.MODEL, "9");
    }
}
