package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.FileAttachment;
import com.aicabinet.trade.mapper.FileAttachmentMapper;
import com.aicabinet.trade.storage.MinioVideoService;
import com.aicabinet.trade.config.MinioProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/** H24：运营经 requireReadable 只能读白名单 refType 的附件。 */
@ExtendWith(MockitoExtension.class)
class FileAttachmentOperatorReadTest {

    /** 运营号段：无请求上下文时 OperatorAuth 按 userId ≥ 1 亿判定。 */
    private static final Long OPERATOR_ID = 100_000_001L;
    private static final Long CONSUMER_ID = 100L;

    @Mock private FileAttachmentMapper fileAttachmentMapper;
    @Mock private MinioVideoService minioVideoService;
    @Mock private MinioProperties minioProperties;
    @Mock private DistributedLockService distributedLockService;

    private FileAttachmentService service;

    @BeforeEach
    void setUp() {
        service = new FileAttachmentService(fileAttachmentMapper, minioVideoService,
                minioProperties, distributedLockService, "./data/attachments");
    }

    private FileAttachment row(String refType, Long uploadedBy) {
        FileAttachment row = new FileAttachment();
        row.setFileId(9L);
        row.setRefType(refType);
        row.setUploadedBy(uploadedBy);
        return row;
    }

    @Test
    void uploadOpsAvatar_rejectsConsumerAccount() {
        // 2026-10-29 实测：消费者 JWT 打 POST /api/v2/ops/admin/rbac/me/avatar 拿到 500（不是 403）
        // ⇒ 写侧也没拦；ops-avatars 命名空间只应接受运营账号。
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.uploadOpsAvatar(CONSUMER_ID, null));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void uploadBusinessLicense_rejectsConsumerAccount() {
        // MA-404：执照图命名空间与 ops-avatars 同守卫——仅运营账号可传。
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.uploadBusinessLicense(CONSUMER_ID,
                        new org.springframework.mock.web.MockMultipartFile(
                                "file", "license.jpg", "image/jpeg", new byte[] {1})));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void uploadBusinessLicense_rejectsEmptyFile() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.uploadBusinessLicense(OPERATOR_ID,
                        new org.springframework.mock.web.MockMultipartFile(
                                "file", "license.jpg", "image/jpeg", new byte[0])));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void uploadBusinessLicense_rejectsNonImage() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.uploadBusinessLicense(OPERATOR_ID,
                        new org.springframework.mock.web.MockMultipartFile(
                                "file", "license.txt", "text/plain", "hello".getBytes())));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void uploadBusinessLicense_rejectsOversize() {
        byte[] big = new byte[(int) (5 * 1024 * 1024L + 1)];
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.uploadBusinessLicense(OPERATOR_ID,
                        new org.springframework.mock.web.MockMultipartFile(
                                "file", "license.jpg", "image/jpeg", big)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void operator_canReadDisputeEvidence() {
        FileAttachment dispute = row(FileAttachmentService.REF_DISPUTE, CONSUMER_ID);
        when(fileAttachmentMapper.selectById(9L)).thenReturn(dispute);

        assertSame(dispute, service.requireReadable(CONSUMER_ID, 9L, true));
    }

    @Test
    void operator_canReadReplenishmentEvidence() {
        FileAttachment replenishment = row(FileAttachmentService.REF_REPLENISHMENT, CONSUMER_ID);
        when(fileAttachmentMapper.selectById(9L)).thenReturn(replenishment);

        assertSame(replenishment, service.requireReadable(CONSUMER_ID, 9L, true));
    }

    @Test
    void operator_cannotReadSkuImageViaGenericEntry() {
        when(fileAttachmentMapper.selectById(9L)).thenReturn(row(FileAttachmentService.REF_SKU_IMAGE, CONSUMER_ID));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.requireReadable(CONSUMER_ID, 9L, true));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void operator_cannotReadUnknownRefType() {
        when(fileAttachmentMapper.selectById(9L)).thenReturn(row("SOME_NEW_TYPE", CONSUMER_ID));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.requireReadable(CONSUMER_ID, 9L, true));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void uploader_canStillReadOwnFile() {
        FileAttachment own = row(FileAttachmentService.REF_SKU_IMAGE, CONSUMER_ID);
        when(fileAttachmentMapper.selectById(9L)).thenReturn(own);

        assertSame(own, service.requireReadable(CONSUMER_ID, 9L, false));
    }

    @Test
    void otherUser_cannotReadForeignFile() {
        when(fileAttachmentMapper.selectById(9L)).thenReturn(row(FileAttachmentService.REF_DISPUTE, 200L));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.requireReadable(CONSUMER_ID, 9L, false));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }
}
