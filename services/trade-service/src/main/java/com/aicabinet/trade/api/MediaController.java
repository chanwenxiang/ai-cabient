package com.aicabinet.trade.api;

import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.domain.FileAttachment;
import com.aicabinet.trade.service.FileAttachmentService;
import com.aicabinet.trade.service.MediaAssetService;
import com.aicabinet.trade.service.OperatorAuth;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * Media endpoints used by {@code <img src>} (no Authorization header).
 * Product catalog images, brand logos and ad-asset previews are public by opaque id;
 * ops avatars require an operator session (H43: 自增 fileId 不再可匿名枚举，同源 <img>
 * 经 HttpOnly 会话 Cookie 鉴权）。
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

    /** H43：运营头像不再公开——需运营会话（归属人本人/运营权限），防止按自增 fileId 匿名枚举。 */
    @GetMapping("/ops-avatars/{fileId}")
    public void opsAvatar(HttpServletRequest request,
                          @PathVariable("fileId") Long fileId,
                          HttpServletResponse response) throws IOException {
        Long operatorId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        OperatorAuth.requireOperator(operatorId);
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
