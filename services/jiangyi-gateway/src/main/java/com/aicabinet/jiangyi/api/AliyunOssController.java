package com.aicabinet.jiangyi.api;

import com.aicabinet.jiangyi.auth.DeviceAuthInterceptor;
import com.aicabinet.jiangyi.oss.OssStsService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将邑设备面 OSS 直传凭证端点（CB-024，V16 §4.2.6）。
 *
 * <p><b>成因注释（2026-10-19 log3 修订）</b>：固件实际 POST 的是
 * {@code /aliYunOss/getTempToken}（非文档的 getTempUploadToken，V15/V16 同错），
 * 并带 body {@code {"bucketName":"jiangyi-logs","dirName":"machine"}}——那是设备
 * 日志上传的将邑自家桶/目录，我方签发凭证统一用自己桶策略，body 只审计不采用
 * （上传目标由返回凭证里的 bucketName/dirName 决定）。云侧真实响应含 {@code expiration}
 * （设备端 SDK 依赖），文档两版字段表均漏——本控制器一并补齐。两条路径并存兼容。</p>
 *
 * <p>鉴权：DeviceAuthInterceptor（/jiangyi/api/** 全量，token 端点豁免外）。
 * 未配置/签发失败 fail-closed 返回 {@code status:500}（设备侧视为上传失败，不影响购物主链路）。</p>
 */
@RestController
public class AliyunOssController {

    private static final Logger log = LoggerFactory.getLogger(AliyunOssController.class);

    private final OssStsService ossStsService;

    public AliyunOssController(OssStsService ossStsService) {
        this.ossStsService = ossStsService;
    }

    /** 文档路径（V16 §4.2.6 getTempUploadToken）——保留兼容，逻辑同真机路径。 */
    @PostMapping("/jiangyi/api/aliYunOss/getTempUploadToken")
    public Map<String, Object> getTempUploadToken(HttpServletRequest request,
                                                  @RequestBody(required = false) Map<String, Object> body) {
        return issue(request, body, "getTempUploadToken");
    }

    /** 真机实路径（log3 实证 3 次：tokenPath = /aliYunOss/getTempToken）。 */
    @PostMapping("/jiangyi/api/aliYunOss/getTempToken")
    public Map<String, Object> getTempToken(HttpServletRequest request,
                                            @RequestBody(required = false) Map<String, Object> body) {
        return issue(request, body, "getTempToken");
    }

    private Map<String, Object> issue(HttpServletRequest request, Map<String, Object> body, String via) {
        String deviceId = attr(request, DeviceAuthInterceptor.ATTR_DEVICE_ID);
        OssStsService.OssUploadToken token = ossStsService.issueUploadToken(deviceId);
        if (token == null) {
            // fail-closed：不给半可用凭证；设备侧重试 / 走异常兜底
            return Map.of("status", 500, "msg", "upload token unavailable", "data", "");
        }
        log.info("jiangyi oss upload token issued via={} deviceId={} bucket={} dir={} deviceAsked={}",
                via, deviceId, token.bucketName(), token.dirName(), body);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accessKeyId", token.accessKeyId());
        data.put("accessKeySecret", token.accessKeySecret());
        data.put("securityToken", token.securityToken());
        data.put("expiration", token.expiration());
        data.put("endpoint", token.endpoint());
        data.put("bucketName", token.bucketName());
        data.put("dirName", token.dirName());
        return Map.of("status", 200, "msg", "success", "data", data);
    }

    private static String attr(HttpServletRequest request, String name) {
        Object v = request.getAttribute(name);
        return v == null ? "unknown" : v.toString();
    }
}
