package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.domain.PlatformFundAccount;
import com.aicabinet.trade.domain.PlatformFundFlow;
import com.aicabinet.trade.service.AdminAuditService;
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
 *
 * <p>S9 归因（MA-082/115 家族）：四个写动作全部 appendLog——资金池操作虽已有
 * platform_fund_flow 台账流水，但那不是 operator 归因；审计补「谁在何时动了资金池」。</p>
 */
@RestController
@RequestMapping("/api/v2/ops/admin")
public class OpsPlatformFundController {

    private static final String BIZ_PLATFORM_FUND = "PLATFORM_FUND_ACCOUNT";

    private final PlatformFundService platformFundService;
    private final AdminAuditService auditService;

    public OpsPlatformFundController(PlatformFundService platformFundService,
                                     AdminAuditService auditService) {
        this.platformFundService = platformFundService;
        this.auditService = auditService;
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
        Long operatorId = operatorId(request);
        PlatformFundAccount created = platformFundService.createAccount(
                str(body.get("channel")), str(body.get("accountName")), initial,
                operatorId, str(body.get("remark")));
        auditService.appendLog(operatorId, "PLATFORM_FUND_CREATE", BIZ_PLATFORM_FUND,
                String.valueOf(created.getAccountId()),
                "渠道=" + created.getChannel() + " 账户=" + created.getAccountName()
                        + " 初始=" + initial + "分");
        return ApiResponse.ok(created);
    }

    @RequiresPermissions("ops:fund:manage")
    @PostMapping("/platform-fund/accounts/{accountId}/recharge")
    public ApiResponse<PlatformFundAccount> recharge(@PathVariable long accountId,
                                                     @RequestBody Map<String, Object> body,
                                                     HttpServletRequest request) {
        long amt = amount(body);
        Long operatorId = operatorId(request);
        PlatformFundAccount after = platformFundService.recharge(accountId, amt, operatorId, str(body.get("remark")));
        auditService.appendLog(operatorId, "PLATFORM_FUND_RECHARGE", BIZ_PLATFORM_FUND,
                String.valueOf(accountId), "金额=" + amt + "分 余额=" + after.getBalanceCents() + "分");
        return ApiResponse.ok(after);
    }

    @RequiresPermissions("ops:fund:manage")
    @PostMapping("/platform-fund/accounts/{accountId}/sweep")
    public ApiResponse<PlatformFundAccount> sweep(@PathVariable long accountId,
                                                  @RequestBody Map<String, Object> body,
                                                  HttpServletRequest request) {
        long amt = amount(body);
        Long operatorId = operatorId(request);
        PlatformFundAccount after = platformFundService.sweep(accountId, amt, operatorId, str(body.get("remark")));
        auditService.appendLog(operatorId, "PLATFORM_FUND_SWEEP", BIZ_PLATFORM_FUND,
                String.valueOf(accountId), "归集=" + amt + "分 余额=" + after.getBalanceCents() + "分");
        return ApiResponse.ok(after);
    }

    @RequiresPermissions("ops:fund:manage")
    @PostMapping("/platform-fund/accounts/{accountId}/adjust")
    public ApiResponse<PlatformFundAccount> adjust(@PathVariable long accountId,
                                                   @RequestBody Map<String, Object> body,
                                                   HttpServletRequest request) {
        long amt = signedAmount(body);
        Long operatorId = operatorId(request);
        PlatformFundAccount after = platformFundService.adjust(accountId, amt, operatorId, str(body.get("remark")));
        auditService.appendLog(operatorId, "PLATFORM_FUND_ADJUST", BIZ_PLATFORM_FUND,
                String.valueOf(accountId), "调整=" + amt + "分 余额=" + after.getBalanceCents() + "分");
        return ApiResponse.ok(after);
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
