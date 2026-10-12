package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.domain.PlatformFundAccount;
import com.aicabinet.trade.domain.PlatformFundFlow;
import com.aicabinet.trade.service.PlatformFundService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 平台资金池台账（CB-033）：渠道账户余额登记（充值/归集/调整）与流水查询。
 *
 * <p>查看沿用「资金账单」菜单既有权限 {@code ops:fund:list}；
 * 写动作（登记充值/归集/调整）为新增按钮权限 {@code ops:fund:manage}（V341 种子）。</p>
 */
@RestController
@RequestMapping("/api/v2/ops/admin")
public class OpsPlatformFundController {

    private final PlatformFundService platformFundService;

    public OpsPlatformFundController(PlatformFundService platformFundService) {
        this.platformFundService = platformFundService;
    }

    @RequiresPermissions("ops:fund:list")
    @GetMapping("/platform-fund/accounts")
    public ApiResponse<List<PlatformFundAccount>> accounts() {
        return ApiResponse.ok(platformFundService.listAccounts());
    }

    @RequiresPermissions("ops:fund:list")
    @GetMapping("/platform-fund/accounts/{accountId}/flows")
    public ApiResponse<List<PlatformFundFlow>> flows(@PathVariable long accountId,
                                                     @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(platformFundService.listFlows(accountId, limit));
    }

    @RequiresPermissions("ops:fund:manage")
    @PostMapping("/platform-fund/accounts")
    public ApiResponse<PlatformFundAccount> create(@RequestBody Map<String, Object> body,
                                                   HttpServletRequest request) {
        long initial = body.get("initialBalanceCents") == null
                ? 0L : ((Number) body.get("initialBalanceCents")).longValue();
        return ApiResponse.ok(platformFundService.createAccount(
                str(body.get("channel")), str(body.get("accountName")), initial,
                operatorId(request), str(body.get("remark"))));
    }

    @RequiresPermissions("ops:fund:manage")
    @PostMapping("/platform-fund/accounts/{accountId}/recharge")
    public ApiResponse<PlatformFundAccount> recharge(@PathVariable long accountId,
                                                     @RequestBody Map<String, Object> body,
                                                     HttpServletRequest request) {
        return ApiResponse.ok(platformFundService.recharge(accountId,
                amount(body), operatorId(request), str(body.get("remark"))));
    }

    @RequiresPermissions("ops:fund:manage")
    @PostMapping("/platform-fund/accounts/{accountId}/sweep")
    public ApiResponse<PlatformFundAccount> sweep(@PathVariable long accountId,
                                                  @RequestBody Map<String, Object> body,
                                                  HttpServletRequest request) {
        return ApiResponse.ok(platformFundService.sweep(accountId,
                amount(body), operatorId(request), str(body.get("remark"))));
    }

    @RequiresPermissions("ops:fund:manage")
    @PostMapping("/platform-fund/accounts/{accountId}/adjust")
    public ApiResponse<PlatformFundAccount> adjust(@PathVariable long accountId,
                                                   @RequestBody Map<String, Object> body,
                                                   HttpServletRequest request) {
        return ApiResponse.ok(platformFundService.adjust(accountId,
                signedAmount(body), operatorId(request), str(body.get("remark"))));
    }

    private static long amount(Map<String, Object> body) {
        Number n = (Number) body.get("amountCents");
        return n == null ? 0L : n.longValue();
    }

    private static long signedAmount(Map<String, Object> body) {
        return amount(body);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
