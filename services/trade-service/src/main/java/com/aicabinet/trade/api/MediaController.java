package com.aicabinet.trade.api;

import com.aicabinet.trade.domain.FileAttachment;
import com.aicabinet.trade.service.FileAttachmentService;
import com.aicabinet.trade.service.MediaAssetService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * Public-ish media endpoints used by {@code <img src>} (no Authorization header).
 * Product catalog images and ad-asset previews are accessed by opaque id.
 */
@RestController
@RequestMapping("/api/v2/media")
public class MediaController {

    private final FileAttachmentService fileAttachmentService;
    private final MediaAssetService mediaAssetService;

    public MediaController(FileAttachmentService fileAttachmentService,
                           MediaAssetService mediaAssetService) {
        this.fileAttachmentService = fileAttachmentService;
        this.mediaAssetService = mediaAssetService;
    }

    @GetMapping("/sku-images/{fileId}")
    public void skuImage(@PathVariable("fileId") Long fileId, HttpServletResponse response) throws IOException {
        FileAttachment row = fileAttachmentService.requireSkuImage(fileId);
        fileAttachmentService.stream(row, response);
    }

    @GetMapping("/ops-avatars/{fileId}")
    public void opsAvatar(@PathVariable("fileId") Long fileId, HttpServletResponse response) throws IOException {
        FileAttachment row = fileAttachmentService.requireOpsAvatar(fileId);
        fileAttachmentService.stream(row, response);
    }

    @GetMapping("/ops-brand/{fileId}")
    public void opsBrand(@PathVariable("fileId") Long fileId, HttpServletResponse response) throws IOException {
        FileAttachment row = fileAttachmentService.requireOpsBrandLogo(fileId);
        fileAttachmentService.stream(row, response);
    }

    @GetMapping("/ad-assets/{assetId}")
    public void adAsset(@PathVariable("assetId") Long assetId,
                        HttpServletRequest request,
                        HttpServletResponse response) {
        mediaAssetService.streamPreview(assetId, request, response);
    }
}
