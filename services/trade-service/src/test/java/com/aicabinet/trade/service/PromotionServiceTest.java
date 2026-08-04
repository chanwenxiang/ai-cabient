package com.aicabinet.trade.service;

import com.aicabinet.common.dto.CreatePromotionRequest;
import com.aicabinet.trade.domain.PromotionActivity;
import com.aicabinet.trade.mapper.PromotionActivityMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PromotionServiceTest {

    @Mock private PromotionActivityMapper repository;
    private PromotionService promotionService;

    @BeforeEach
    void setUp() {
        promotionService = new PromotionService(repository);
    }

    @Test
    void create_shouldSaveAndReturn() {
        var req = new CreatePromotionRequest(
                "国庆活动", "FULL_REDUCE",
                Instant.now(), Instant.now().plus(7, ChronoUnit.DAYS),
                1000000L, 1, "ALL", "{\"threshold\":1000,\"discount\":200}", "测试活动");
        when(repository.save(any())).thenAnswer(i -> {
            var a = (PromotionActivity) i.getArgument(0);
            a.setActivityId(1L);
            return a;
        });

        var result = promotionService.create(req);

        assertEquals("国庆活动", result.activityName());
        assertEquals("FULL_REDUCE", result.activityType());
        assertEquals("DRAFT", result.status());
        assertEquals(1000000L, result.budgetCents());
        verify(repository, times(1)).save(any());
    }

    @Test
    void launch_shouldSetActive() {
        var activity = new PromotionActivity();
        activity.setActivityId(1L);
        activity.setStatus("DRAFT");
        when(repository.findById(1L)).thenReturn(Optional.of(activity));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        var result = promotionService.launch(1L);

        assertEquals("ACTIVE", result.status());
    }

    @Test
    void stop_shouldSetStopped() {
        var activity = new PromotionActivity();
        activity.setActivityId(1L);
        activity.setStatus("ACTIVE");
        when(repository.findById(1L)).thenReturn(Optional.of(activity));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        var result = promotionService.stop(1L);

        assertEquals("STOPPED", result.status());
    }

    @Test
    void updateStatus_shouldThrow_whenNotFound() {
        when(repository.findById(999L)).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class,
                () -> promotionService.updateStatus(999L, "ACTIVE"));
    }

    @Test
    void listActive_shouldReturnOnlyActive() {
        var draft = new PromotionActivity();
        draft.setActivityId(1L);
        draft.setStatus("DRAFT");

        var active = new PromotionActivity();
        active.setActivityId(2L);
        active.setStatus("ACTIVE");

        when(repository.findByStatus("ACTIVE")).thenReturn(List.of(active));

        var result = promotionService.listActive();

        assertEquals(1, result.size());
        assertEquals("ACTIVE", result.get(0).status());
    }
}
