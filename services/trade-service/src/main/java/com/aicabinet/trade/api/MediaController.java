package com.aicabinet.trade.api;

import com.aicabinet.trade.domain.FileAttachment;
import com.aicabinet.trade.service.FileAttachmentService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public-ish media endpoints used by {@code <img src>} (no Authorization header).
 * Product catalog images are not sensitive; access is by opaque file id.
 */
@RestController
@RequestMapping("/api/v2/media")
public class MediaController {

    private final FileAttachmentService fileAttachmentService;

    public MediaController(FileAttachmentService fileAttachmentService) {
        this.fileAttachmentService = fileAttachmentService;
    }

    @GetMapping("/sku-images/{fileId}")
    public void skuImage(@PathVariable("fileId") Long fileId, HttpServletResponse response) throws Exception {
        FileAttachment row = fileAttachmentService.requireSkuImage(fileId);
        fileAttachmentService.stream(row, response);
    }
}
