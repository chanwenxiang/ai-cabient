package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.UserInfoMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OperatorUserIdAllocatorTest {

    @Test
    void nextId_usesDatabaseSequence() {
        UserInfoMapper repository = mock(UserInfoMapper.class);
        when(repository.nextOperatorUserId()).thenReturn(100000123L);

        assertEquals(100000123L, new OperatorUserIdAllocator(repository).nextId());
    }

    @Test
    void nextId_rejectsNullSequenceValue() {
        UserInfoMapper repository = mock(UserInfoMapper.class);
        when(repository.nextOperatorUserId()).thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> new OperatorUserIdAllocator(repository).nextId());
    }
}
