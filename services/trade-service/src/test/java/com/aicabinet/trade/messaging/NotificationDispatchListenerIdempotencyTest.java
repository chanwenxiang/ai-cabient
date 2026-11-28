package com.aicabinet.trade.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aicabinet.common.dto.NotificationDispatchMessage;
import com.aicabinet.trade.service.ExternalNotificationDispatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

@ExtendWith(MockitoExtension.class)
class NotificationDispatchListenerIdempotencyTest {

    private static final String PAYLOAD = "{\"templateCode\":\"order_paid\",\"userId\":100,"
            + "\"title\":\"订单支付成功\",\"body\":\"已支付 12 元\",\"bizType\":\"ORDER\",\"bizId\":\"O1\"}";

    @Mock private ExternalNotificationDispatcher dispatcher;
    @Mock private RedissonClient redisson;
    @Mock private RScript script;

    private NotificationDispatchListener listener;

    @BeforeEach
    void setUp() {
        listener = new NotificationDispatchListener(dispatcher, new ObjectMapper(), redisson);
        when(redisson.getScript(StringCodec.INSTANCE)).thenReturn(script);
    }

    private void scriptReturns(Long... results) {
        when(script.eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.INTEGER),
                anyList(), any(Object[].class))).thenReturn(results[0], Arrays.copyOfRange(results, 1, results.length));
    }

    @Test
    void onMessage_firstDelivery_dispatchesAndMarksDone() {
        scriptReturns(1L, 1L);

        listener.onMessage(PAYLOAD);

        verify(dispatcher).dispatch(any(NotificationDispatchMessage.class));
        ArgumentCaptor<String> lua = ArgumentCaptor.forClass(String.class);
        verify(script, times(2)).eval(eq(RScript.Mode.READ_WRITE), lua.capture(),
                eq(RScript.ReturnType.INTEGER), anyList(), any(Object[].class));
        assertTrue(lua.getAllValues().get(0).contains("|PROCESSING"));
        assertTrue(lua.getAllValues().get(1).contains("|DONE"));
    }

    @Test
    void onMessage_donePayload_skipsDispatch() {
        scriptReturns(0L);

        listener.onMessage(PAYLOAD);

        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    void onMessage_processingPayload_retriesInsteadOfAck() {
        scriptReturns(2L);

        assertThatThrownBy(() -> listener.onMessage(PAYLOAD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("still processing");

        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    void onMessage_dispatchFails_releasesOwnedSlotAndRethrows() {
        scriptReturns(1L, 0L);
        org.mockito.Mockito.doThrow(new RuntimeException("sms gateway down"))
                .when(dispatcher).dispatch(any(NotificationDispatchMessage.class));

        assertThatThrownBy(() -> listener.onMessage(PAYLOAD))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("sms gateway down");

        ArgumentCaptor<String> lua = ArgumentCaptor.forClass(String.class);
        verify(script, times(2)).eval(eq(RScript.Mode.READ_WRITE), lua.capture(),
                eq(RScript.ReturnType.INTEGER), anyList(), any(Object[].class));
        assertTrue(lua.getAllValues().get(1).contains("DEL"));
    }

    @Test
    void onMessage_unparseablePayload_releasesOwnedSlotAndRethrows() {
        scriptReturns(1L, 0L);

        assertThatThrownBy(() -> listener.onMessage("{not-json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("notification dispatch failed");

        ArgumentCaptor<String> lua = ArgumentCaptor.forClass(String.class);
        verify(script, times(2)).eval(eq(RScript.Mode.READ_WRITE), lua.capture(),
                eq(RScript.ReturnType.INTEGER), anyList(), any(Object[].class));
        assertTrue(lua.getAllValues().get(1).contains("DEL"));
    }

    @Test
    void onMessage_releaseFailure_doesNotMaskOriginalDispatchFailure() {
        org.mockito.Mockito.doThrow(new RuntimeException("sms gateway down"))
                .when(dispatcher).dispatch(any(NotificationDispatchMessage.class));
        when(script.eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.INTEGER),
                anyList(), any(Object[].class))).thenReturn(1L).thenThrow(new RuntimeException("redis down"));

        assertThatThrownBy(() -> listener.onMessage(PAYLOAD))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("sms gateway down");
    }

    @Test
    void onMessage_markDoneFailure_releasesAndRethrows() {
        scriptReturns(1L, 0L, 0L);

        assertThatThrownBy(() -> listener.onMessage(PAYLOAD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lost dedupe ownership");

        verify(dispatcher).dispatch(any(NotificationDispatchMessage.class));
        verify(script, times(3)).eval(eq(RScript.Mode.READ_WRITE), anyString(),
                eq(RScript.ReturnType.INTEGER), anyList(), any(Object[].class));
    }

    @Test
    void onMessage_claimFailure_doesNotDispatch() {
        when(script.eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.INTEGER),
                anyList(), any(Object[].class))).thenThrow(new RuntimeException("redis down"));

        assertThatThrownBy(() -> listener.onMessage(PAYLOAD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claim failed");

        verify(dispatcher, never()).dispatch(any());
    }
}
