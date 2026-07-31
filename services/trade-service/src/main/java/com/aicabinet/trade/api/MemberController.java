package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.MemberProfileDto;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.service.ConsumerMemberFacade;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/member")
public class MemberController {

    private final ConsumerMemberFacade memberFacade;

    public MemberController(ConsumerMemberFacade memberFacade) {
        this.memberFacade = memberFacade;
    }

    @GetMapping("/profile")
    public ApiResponse<MemberProfileDto> profile(HttpServletRequest request) {
        return ApiResponse.ok(memberFacade.profile(userId(request)));
    }

    private static Long userId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
