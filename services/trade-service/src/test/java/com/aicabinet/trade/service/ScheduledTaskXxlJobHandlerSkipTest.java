package com.aicabinet.trade.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** M19：XXL 调度侧感知 action 内部 tryBegin 跳过并落 SKIPPED 状态。 */
@ExtendWith(MockitoExtension.class)
class ScheduledTaskXxlJobHandlerSkipTest {

    private static final String KEY = "unpaid-cancel";

    @Mock private ScheduledTaskRegistry registry;
    @Mock private ScheduledTaskService taskService;

    private ScheduledTaskXxlJobHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ScheduledTaskXxlJobHandler(registry, taskService);
        when(registry.get(KEY)).thenReturn(Optional.of(new ScheduledTaskRegistry.TaskDescriptor(
                KEY, "未付订单自动取消", "TRADE", "每 15 分钟", 600, true, () -> { })));
    }

    @Test
    void runKey_whenTryBeginSkipped_recordsSkippedStatus() {
        when(taskService.consumeLastTryBeginSkip()).thenReturn("lock busy");

        handler.unpaidCancelJob();

        verify(taskService).markSkipped(eq(KEY), contains("lock busy"));
    }

    @Test
    void runKey_whenActionSucceeds_doesNotRecordSkipped() {
        when(taskService.consumeLastTryBeginSkip()).thenReturn(null);

        handler.unpaidCancelJob();

        verify(taskService, never()).markSkipped(anyString(), anyString());
    }
}
