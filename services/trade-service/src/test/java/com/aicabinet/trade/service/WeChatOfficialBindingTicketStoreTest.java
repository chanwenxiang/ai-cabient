package com.aicabinet.trade.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WeChatOfficialBindingTicketStoreTest {

    @Mock private RedissonClient redisson;
    @Mock private RBucket<String> bucket;

    @Test
    void issueStoresHashNotRawTicket() {
        doReturn(bucket).when(redisson).getBucket(anyString(), eq(StringCodec.INSTANCE));
        WeChatOfficialBindingTicketStore store = new WeChatOfficialBindingTicketStore(redisson);

        WeChatOfficialBindingTicketStore.Issued issued = store.issue(100L, "gh_test");

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(redisson).getBucket(key.capture(), eq(StringCodec.INSTANCE));
        assertNotEquals(issued.ticket(), key.getValue());
        verify(bucket).set(eq("100|gh_test|" + issued.expireAt().toEpochMilli()),
                eq(WeChatOfficialBindingTicketStore.TTL));
    }

    @Test
    void consumeIsOneTime() {
        doReturn(bucket).when(redisson).getBucket(anyString(), eq(StringCodec.INSTANCE));
        when(bucket.getAndDelete()).thenReturn("100|gh_test|" + (Instant.now().plusSeconds(300).toEpochMilli()))
                .thenReturn(null);
        WeChatOfficialBindingTicketStore store = new WeChatOfficialBindingTicketStore(redisson);

        Optional<WeChatOfficialBindingTicketStore.Issued> first = store.consume("ticket");
        Optional<WeChatOfficialBindingTicketStore.Issued> second = store.consume("ticket");

        assertTrue(first.isPresent());
        assertEquals(100L, first.get().userId());
        assertTrue(second.isEmpty());
    }

    @Test
    void expiredTicketIsRejected() {
        doReturn(bucket).when(redisson).getBucket(anyString(), eq(StringCodec.INSTANCE));
        when(bucket.getAndDelete()).thenReturn("100|gh_test|" + (Instant.now().minusSeconds(1).toEpochMilli()));
        WeChatOfficialBindingTicketStore store = new WeChatOfficialBindingTicketStore(redisson);

        assertTrue(store.consume("ticket").isEmpty());
    }
}
