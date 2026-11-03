package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.JiangyiClassMapping;
import com.aicabinet.trade.mapper.JiangyiClassMappingMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JiangyiClassMappingServiceTest {

    @Mock private JiangyiClassMappingMapper mapper;
    @Mock private AdminAuditService auditService;

    @Test
    void upsert_and_status_shouldAuditOperator() {
        JiangyiClassMapping mapping = new JiangyiClassMapping();
        mapping.setDeviceId("CAB-1");
        mapping.setClassId(7);
        mapping.setSkuId("SKU-1");
        mapping.setStatus("ACTIVE");
        mapping.setSource("MANUAL");
        when(mapper.findOne("CAB-1", 7)).thenReturn(Optional.of(mapping));
        when(mapper.setStatus("CAB-1", 7, false)).thenReturn(1);

        JiangyiClassMappingService service = new JiangyiClassMappingService(mapper, auditService);
        JiangyiClassMapping saved = service.upsert(9L, mapping);
        int rows = service.setStatus(9L, "CAB-1", 7, false);

        assertEquals("SKU-1", saved.getSkuId());
        assertEquals(1, rows);
        verify(auditService).appendLog(eq(9L), eq("JIANGYI_CLASS_MAPPING_UPSERT"),
                eq("JIANGYI_CLASS_MAPPING"), eq("CAB-1:7"), contains("skuId=SKU-1"));
        verify(auditService).appendLog(eq(9L), eq("JIANGYI_CLASS_MAPPING_STATUS"),
                eq("JIANGYI_CLASS_MAPPING"), eq("CAB-1:7"), contains("active=false"));
    }
}
