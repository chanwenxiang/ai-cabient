package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.StagingProperties;
import com.aicabinet.trade.sms.SmsCodeService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** E2E / staging：从数据库读取最新未过期验证码（internal API，非生产用户接口）。 */
@RestController
@RequestMapping("/internal/v1/sms")
public class SmsInternalController {

    private final SmsCodeService smsCodeService;
    private final SecurityProperties securityProperties;
    private final StagingProperties stagingProperties;

    public SmsInternalController(SmsCodeService smsCodeService,
                                 SecurityProperties securityProperties,
                                 StagingProperties stagingProperties) {
        this.smsCodeService = smsCodeService;
        this.securityProperties = securityProperties;
        this.stagingProperties = stagingProperties;
    }

    @GetMapping("/latest-code")
    public ApiResponse<LatestSmsCodeDto> latestCode(@RequestParam String phoneNumber) {
        if (!securityProperties.mockEnabled() && !stagingProperties.stagingMode()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not available in production");
        }
        return smsCodeService.latestActiveCode(phoneNumber)
                .map(s -> new LatestSmsCodeDto(s.phoneNumber(), s.code(), s.expiresAt().toString()))
                .map(ApiResponse::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no active code"));
    }

    public record LatestSmsCodeDto(String phoneNumber, String code, String expiresAt) {}
}
