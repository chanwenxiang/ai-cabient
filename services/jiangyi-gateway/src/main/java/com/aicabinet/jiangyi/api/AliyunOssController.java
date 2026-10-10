package com.aicabinet.jiangyi.api;

import com.aicabinet.jiangyi.auth.DeviceAuthInterceptor;
import com.aicabinet.jiangyi.oss.OssStsService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 将邑设备面 OSS 直传凭证端点（CB-024，V16 §4.2.6）。
 *
 * <p>设备（购买/异常视频复核链路）POST 本端点拿阿里云 STS 临时凭证后，
 * 用设备端阿里云 SDK <b>直传</b>视频到商户 OSS 桶——视频流量不过我方服务器
 * （easygo 旧模式是服务端中转 putObject，与将邑设备端协议不同构，不可照搬）。</p>
 *
 * <p>鉴权：DeviceAuthInterceptor（/jiangyi/api/** 全量，token 端点豁免外）。
 * 响应 envelope 与文档表格 16/17 逐字对齐；未配置/签发失败 fail-closed 返回
 * {@code status:500}（设备侧视为上传失败，不影响购物主链路）。</p>
 */
@RestController
public class AliyunOssController {

    private static final Logger log = LoggerFactory.getLogger(AliyunOssController.class);

    private final OssStsService ossStsService;

    public AliyunOssController(OssStsService ossStsService) {
        this.ossStsService = ossStsService;
    }

    @PostMapping("/jiangyi/api/aliYunOss/getTempUploadToken")
    public Map<String, Object> getTempUploadToken(HttpServletRequest request) {
        String deviceId = attr(request, DeviceAuthInterceptor.ATTR_DEVICE_ID);
        OssStsService.OssUploadToken token = ossStsService.issueUploadToken(deviceId);
        if (token == null) {
            // fail-closed：不给半可用凭证；设备侧重试 / 走异常兜底
            return Map.of("status", 500, "msg", "upload token unavailable", "data", "");
        }
        log.info("jiangyi oss upload token issued deviceId={} bucket={} dir={}",
                deviceId, token.bucketName(), token.dirName());
        return Map.of("status", 200, "msg", "success", "data", Map.of(
                "accessKeyId", token.accessKeyId(),
                "accessKeySecret", token.accessKeySecret(),
                "securityToken", token.securityToken(),
                "endpoint", token.endpoint(),
                "bucketName", token.bucketName(),
                "dirName", token.dirName()));
    }

    private static String attr(HttpServletRequest request, String name) {
        Object v = request.getAttribute(name);
        return v == null ? "unknown" : v.toString();
    }
}
