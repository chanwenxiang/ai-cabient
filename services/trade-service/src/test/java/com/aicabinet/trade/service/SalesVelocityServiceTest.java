package com.aicabinet.trade.service;

import com.aicabinet.trade.config.RopProperties;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalesVelocityServiceTest {

    @Mock
    private CabinetOrderLineMapper lineRepository;

    private SalesVelocityService salesVelocityService;

    @BeforeEach
    void setUp() {
        salesVelocityService = new SalesVelocityService(lineRepository, new RopProperties(2, 1));
    }

    @Test
    void velocityBySku_computesRopFromSevenDaySales() {
        when(lineRepository.sumSoldQtyBySkuSince(eq("CAB-001"), any(Instant.class)))
                .thenReturn(List.of(new Object[][]{{"SKU-A", 14}}))
                .thenReturn(List.of(new Object[][]{{"SKU-A", 20}}));

        SalesVelocityService.SkuVelocity velocity = salesVelocityService.velocityBySku("CAB-001").get("SKU-A");

        assertEquals(14, velocity.soldQty7d());
        assertEquals(20, velocity.soldQty14d());
        assertEquals(6, velocity.ropPoint());
    }
}
