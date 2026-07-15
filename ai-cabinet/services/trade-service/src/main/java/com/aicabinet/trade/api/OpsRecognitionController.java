package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.DevRecognitionPreviewDto;
import com.aicabinet.common.dto.DevRecognitionTestRequest;
import com.aicabinet.common.dto.DevRecognitionTestResponse;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.service.OperatorAuth;
import com.aicabinet.trade.service.RecognitionTestService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 运营端：上传商品图测试 YOLO 识别能力 */
@RestController
@RequestMapping("/api/v2/ops")
public class OpsRecognitionController {

    private final RecognitionTestService recognitionTestService;

    public OpsRecognitionController(RecognitionTestService recognitionTestService) {
        this.recognitionTestService = recognitionTestService;
    }

    @PostMapping(value = "/recognition-preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<DevRecognitionPreviewDto> preview(
            HttpServletRequest request,
            @RequestPart("image") MultipartFile image) throws Exception {
        Long operatorId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        OperatorAuth.requireOperator(operatorId);
        byte[] bytes = requireImage(image);
        return ApiResponse.ok(recognitionTestService.previewUpload(bytes, image.getOriginalFilename()));
    }

    @PostMapping(value = "/recognition-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<DevRecognitionTestResponse> upload(
            HttpServletRequest request,
            @RequestParam("deviceId") String deviceId,
            @RequestPart("image") MultipartFile image,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "mode", required = false, defaultValue = "FULL") String mode,
            @RequestParam(value = "settle", required = false, defaultValue = "false") boolean settle) throws Exception {
        Long operatorId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        OperatorAuth.requireOperator(operatorId);
        byte[] bytes = requireImage(image);
        DevRecognitionTestRequest body = new DevRecognitionTestRequest(deviceId, sessionId, mode);
        return ApiResponse.ok(recognitionTestService.runWithUpload(
                operatorId, body, bytes, image.getOriginalFilename(), settle));
    }

    private static byte[] requireImage(MultipartFile image) throws Exception {
        if (image == null || image.isEmpty()) {
            throw new IllegalArgumentException("请上传商品图片");
        }
        String contentType = image.getContentType();
        if (contentType != null && !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("仅支持图片文件");
        }
        return image.getBytes();
    }
}
