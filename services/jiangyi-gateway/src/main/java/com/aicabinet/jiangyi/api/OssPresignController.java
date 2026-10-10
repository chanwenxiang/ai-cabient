package com.aicabinet.jiangyi.api;

import com.aicabinet.jiangyi.oss.OssStsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部面：OSS 对象预签名 GET URL（CB-029，运营侧订单视频复核的读路径）。
 *
 * <p><b>为什么放在 gateway 而不是 trade</b>：桶名/地域/角色 ARN 与 AssumeRole 能力都只在
 * gateway（{@link OssStsService}）；trade 不该持有 OSS 凭据（铁律 #27 密钥最小暴露面）。</p>
 *
 * <p>鉴权：{@code /internal/**} → common-core InternalApiAuthInterceptor（X-Internal-Api-Key），
 * 与设备面（Bearer JWT）互不重叠，见 {@code GatewayWebConfig}。</p>
 *
 * <p><b>三段式返回</b>（调用方据此三分支处置，不靠异常猜语义）：</p>
 * <ul>
 *   <li>{@code ok=true} —— 已签名的短时效 URL；</li>
 *   <li>{@code external=true} —— 引用不在本桶（设备上报了外部地址），调用方原样使用，
 *       <b>不是错误</b>；</li>
 *   <li>两者皆 false —— 真失败（未配置 / key 越界 / STS 失败），调用方 fail-closed 标记不可播放。</li>
 * </ul>
 */
@RestController
@RequestMapping("/internal/v1/jiangyi")
public class OssPresignController {

    private static final Logger log = LoggerFactory.getLogger(OssPresignController.class);

    private final OssStsService ossStsService;

    public OssPresignController(OssStsService ossStsService) {
        this.ossStsService = ossStsService;
    }

    @PostMapping("/oss/presign-get")
    public PresignResponse presignGet(@RequestBody(required = false) PresignRequest body) {
        String ref = body == null ? null : body.ref();
        OssStsService.RefResolution resolution = ossStsService.resolveRef(ref);
        if (resolution.kind() == OssStsService.RefKind.EXTERNAL) {
            return PresignResponse.externalRef();
        }
        if (resolution.kind() == OssStsService.RefKind.INVALID) {
            log.warn("jiangyi oss presign rejected: unresolvable ref");
            return PresignResponse.failed("UNRESOLVABLE");
        }
        OssStsService.OssPresignUrl presigned = ossStsService.presignGet(resolution.objectKey());
        if (presigned == null) {
            log.warn("jiangyi oss presign unavailable for key={}", resolution.objectKey());
            return PresignResponse.failed("UNAVAILABLE");
        }
        return PresignResponse.signed(presigned.url(), presigned.expiration());
    }

    /** 设备上报的原始引用（完整 URL 或裸 objectKey）。 */
    public record PresignRequest(String ref) {}

    public record PresignResponse(boolean ok, boolean external, String url, String expiration, String reason) {

        static PresignResponse signed(String url, String expiration) {
            return new PresignResponse(true, false, url, expiration, null);
        }

        /**
         * 非本桶引用。🔴 工厂方法<b>不能</b>叫 {@code external()} —— 与 record 分量
         * {@code external} 同名会让编译器把静态方法当成非法访问器（accessor must be public）报错。
         */
        static PresignResponse externalRef() {
            return new PresignResponse(false, true, null, null, "EXTERNAL");
        }

        static PresignResponse failed(String reason) {
            return new PresignResponse(false, false, null, null, reason);
        }
    }
}
