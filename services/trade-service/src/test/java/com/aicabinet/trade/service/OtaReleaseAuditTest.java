package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OtaReleaseDto;
import com.aicabinet.trade.domain.OtaRelease;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.OtaDeviceReportMapper;
import com.aicabinet.trade.mapper.OtaReleaseMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MA-115：OTA 发布/下架是「推错固件全车队瘫」的最高危动作，此前 operatorId 收下即弃、
 * 零审计零日志。这里钉住两条动作都落到 admin_audit_log，且 detail 足以回答「推了哪个包」。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OtaReleaseAuditTest {

    @Mock private OtaReleaseMapper releaseRepository;
    @Mock private DeviceInfoMapper deviceRepository;
    @Mock private OtaDeviceReportMapper reportRepository;
    @Mock private OtaCdnService otaCdnService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private SystemConfigService systemConfigService;
    @Mock private AdminAuditService auditService;

    private OtaService service;

    @BeforeEach
    void setUp() {
        service = new OtaService(releaseRepository, deviceRepository, reportRepository,
                otaCdnService, new ObjectMapper(), distributedLockService, systemConfigService, auditService);
        when(releaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
    }

    @Test
    void publishRelease_recordsOperatorAndPackageIdentity() {
        OtaReleaseDto body = new OtaReleaseDto(null, "9.9.9", "stable", "https://cdn/app.apk",
                null, "a".repeat(64), null, true, null, null, null, 20, java.util.List.of(), 3600);

        service.publishRelease(4242L, body);

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).appendLog(eq(4242L), eq("OTA_PUBLISH"), eq("OTA_RELEASE"),
                any(), detail.capture());
        assertTrue(detail.getValue().contains("version=9.9.9"), detail.getValue());
        assertTrue(detail.getValue().contains("grayPercent=20"), detail.getValue());
        // checksum 只留前 12 位：足以对包，又不至于把 detail 撑爆（appendLog 512 截断）
        assertTrue(detail.getValue().contains("checksum=" + "a".repeat(12)), detail.getValue());
    }

    @Test
    void unpublishRelease_recordsOperator() {
        OtaRelease release = new OtaRelease();
        release.setReleaseId(88L);
        release.setAppVersion("9.9.9");
        release.setChannel("stable");
        release.setChecksumSha256("b".repeat(64));
        release.setStatus("PUBLISHED");
        when(releaseRepository.findByIdForUpdate(88L)).thenReturn(Optional.of(release));

        service.unpublishRelease(4242L, 88L);

        verify(auditService).appendLog(eq(4242L), eq("OTA_UNPUBLISH"), eq("OTA_RELEASE"),
                eq("88"), contains("version=9.9.9"));
    }
}
