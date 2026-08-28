package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DevRecognitionTestRequest;
import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecognitionTestConcurrencyTest {

    @Mock private SessionService sessionService;
    @Mock private ShoppingSessionMapper sessionRepository;
    @Mock private SettlementService settlementService;
    @Mock private VisionServiceClient visionClient;
    @Mock private SkuCatalogMapper skuCatalogRepository;
    @Mock private DistributedLockService distributedLockService;

    private RecognitionTestService service;

    @BeforeEach
    void setUp() {
        service = new RecognitionTestService(sessionService, sessionRepository, settlementService,
                visionClient, skuCatalogRepository, distributedLockService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    void runWithUpload_whenSessionLockBusy_rejectsWithConflict() {
        when(visionClient.recognizeUpload(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new VisionServiceClient.RecognitionResult(
                        "TASK-1",
                        java.util.List.of(new VisionServiceClient.RecognizedItem("SKU1", 1, 0.95f)),
                        0.95f, false, "yolov8", java.util.List.of("SKU1")));
        when(sessionService.createSessionForDevTest(eq(1L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.aicabinet.common.dto.SessionDto(
                        "S-REC-1", 1L, "CAB-R", com.aicabinet.common.enums.SessionState.SHOPPING,
                        null, null, null, null, null, null));
        when(distributedLockService.tryLock(
                SessionService.sessionLifeLockKey("S-REC-1"), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.runWithUpload(1L,
                        new DevRecognitionTestRequest("CAB-R", null, "FULL"),
                        new byte[] {1, 2}, "test.jpg", true));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }
}
