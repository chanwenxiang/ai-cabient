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

    private static final Long OPERATOR_ID = 9001L;
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
