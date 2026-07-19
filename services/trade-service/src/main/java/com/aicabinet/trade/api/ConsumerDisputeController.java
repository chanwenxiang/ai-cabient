package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.DisputeTicketDto;
import com.aicabinet.common.dto.FileAttachmentDto;
import com.aicabinet.common.dto.FileDisputeRequest;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.domain.FileAttachment;
import com.aicabinet.trade.service.DisputeService;
import com.aicabinet.trade.service.FileAttachmentService;
import com.aicabinet.trade.service.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v2/disputes")
public class ConsumerDisputeController {

    private final DisputeService disputeService;
    private final FileAttachmentService fileAttachmentService;
    private final PermissionService permissionService;

    public ConsumerDisputeController(DisputeService disputeService,
                                     FileAttachmentService fileAttachmentService,
                                     PermissionService permissionService) {
        this.disputeService = disputeService;
        this.fileAttachmentService = fileAttachmentService;
        this.permissionService = permissionService;
    }

    @GetMapping("/mine")
    public ApiResponse<List<DisputeTicketDto>> mine(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(disputeService.listMyTickets(userId));
    }

    @PostMapping
    public ApiResponse<DisputeTicketDto> file(
            HttpServletRequest request,
            @Valid @RequestBody FileDisputeRequest body) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(disputeService.fileByConsumer(userId, body));
    }

    @PostMapping(value = "/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<FileAttachmentDto> uploadEvidence(
            HttpServletRequest request,
            @RequestPart("file") MultipartFile file) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(fileAttachmentService.uploadDisputeEvidence(userId, file));
    }

    @GetMapping("/evidence/{fileId}")
    public void downloadEvidence(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable("fileId") Long fileId) throws Exception {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        boolean operator = permissionService.hasAnyPermission(userId,
                "ops:dispute", "ops:dispute:resolve");
        FileAttachment row = fileAttachmentService.requireReadable(userId, fileId, operator);
        fileAttachmentService.stream(row, response);
    }
}
