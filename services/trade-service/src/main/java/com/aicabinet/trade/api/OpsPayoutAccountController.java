package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.PayoutAccountCreateRequest;
import com.aicabinet.common.dto.PayoutAccountDto;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.domain.PayoutAccount;
import com.aicabinet.trade.payout.PayoutConstants;
import com.aicabinet.trade.service.AdminAuditService;
import com.aicabinet.trade.service.MerchantScopeService;
import com.aicabinet.trade.service.PayoutAccountService;
import com.aicabinet.trade.service.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 收款账户管理（CB-066）：G2-13 实测「未配置收款账户」报错指向的配置页不存在、create 无任何
 * Controller 暴露 ⇒ 除种子 MCH-DEFAULT 外全部主体无法提现。本控制器补上管理面
 * （列表/新增/设默认/停用），权限按 ownerType 分域：LINE_MANAGER=ops:line-manager:*，
 * MERCHANT=ops:merchant:*。户名一致性守卫在 {@link com.aicabinet.trade.service.PayoutAccountService}。
 */
@RestController
@RequestMapping("/api/v2/ops/admin/payout-accounts")
public class OpsPayoutAccountController {

    private final PayoutAccountService payoutAccountService;
    private final PermissionService permissionService;
    private final AdminAuditService auditService;
    private final MerchantScopeService merchantScopeService;

    public OpsPayoutAccountController(PayoutAccountService payoutAccountService,
                                      PermissionService permissionService,
                                      AdminAuditService auditService,
                                      MerchantScopeService merchantScopeService) {
        this.payoutAccountService = payoutAccountService;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.merchantScopeService = merchantScopeService;
    }

    @RequiresPermissions("ops:finance:view")
    @GetMapping
    public ApiResponse<List<PayoutAccountDto>> list(HttpServletRequest request,
                                                     @RequestParam String ownerType,
                                                     @RequestParam String ownerId) {
        requireOwnerPermission(operator(request), ownerType, ownerId, false);
        return ApiResponse.ok(payoutAccountService.listByOwner(ownerType, ownerId).stream()
                .map(OpsPayoutAccountController::toDto)
                .toList());
    }

    @PostMapping
    public ApiResponse<PayoutAccountDto> create(HttpServletRequest request,
                                                @Valid @RequestBody PayoutAccountCreateRequest body) {
        Long operator = operator(request);
        requireOwnerPermission(operator, body.ownerType(), body.ownerId(), true);
        PayoutAccount account = payoutAccountService.create(body.ownerType(), body.ownerId(),
                body.accountType(), body.channel(), body.accountName(), body.accountNo(),
                body.bankName(), body.bankBranch(), body.taxNo(),
                Boolean.TRUE.equals(body.asDefault()), operator);
        auditService.appendLog(operator, "PAYOUT_ACCOUNT_CREATE", "PAYOUT_ACCOUNT",
                String.valueOf(account.getAccountId()),
                "owner=" + body.ownerType() + "/" + body.ownerId()
                        + ",channel=" + account.getChannel()
                        + ",accountNo=" + account.getAccountNoMask()
                        + ",default=" + account.getIsDefault());
        return ApiResponse.ok(toDto(account));
    }

    @PostMapping("/{accountId}/default")
    public ApiResponse<Void> setDefault(HttpServletRequest request,
                                        @PathVariable long accountId,
                                        @RequestBody PayoutAccountCreateRequest body) {
        Long operator = operator(request);
        requireOwnerPermission(operator, body.ownerType(), body.ownerId(), true);
        payoutAccountService.setDefault(body.ownerType(), body.ownerId(), accountId);
        auditService.appendLog(operator, "PAYOUT_ACCOUNT_SET_DEFAULT", "PAYOUT_ACCOUNT",
                String.valueOf(accountId), "owner=" + body.ownerType() + "/" + body.ownerId());
        return ApiResponse.ok(null);
    }

    @PostMapping("/{accountId}/disable")
    public ApiResponse<Void> disable(HttpServletRequest request,
                                     @PathVariable long accountId,
                                     @RequestBody PayoutAccountCreateRequest body) {
        Long operator = operator(request);
        requireOwnerPermission(operator, body.ownerType(), body.ownerId(), true);
        payoutAccountService.disable(body.ownerType(), body.ownerId(), accountId, operator);
        auditService.appendLog(operator, "PAYOUT_ACCOUNT_DISABLE", "PAYOUT_ACCOUNT",
                String.valueOf(accountId), "owner=" + body.ownerType() + "/" + body.ownerId());
        return ApiResponse.ok(null);
    }

    /**
     * 权限按主体分域：线长走线长管理权限，商户走商户管理权限（防止跨域用一张通行证管两种主体）。
     *
     * <p>MA-077：原实现的 else 分支把<b>任何未知 ownerType</b> 兜到只读码 {@code ops:finance:view} 上，
     * 于是「有查看权 = 能往未知主体写收款账户」，且垃圾值原样落库。现在未知值一律 400；
     * {@code PLATFORM} 保留只读面（现网无平台收款账户的写路径，写侧只认线长/商户）。
     *
     * <p>R19-15（2026-11-12）：{@code ops:merchant:edit} 是<b>功能码</b>，不含商户维度——
     * {@link PermissionService#requirePermission} 只回答「你这个角色有没有这个功能」，
     * 不回答「这个商户在不在你的数据范围」。原实现只查功能码，于是被授权管 A 商户的运营
     * 可以拿 {@code ownerId=MCH-B} 把 B 商户的默认收款账户改成自己的卡（资金改道）。
     * 商户域权限在本仓的既有范式是 {@link MerchantScopeService#requireMerchantAccess}
     * （MerchantService / MerchantWithdrawService / DeviceAssetService 均如此），此处补齐。
     *
     * <p>LINE_MANAGER 无同类域模型（线长按线路归属，现网没有「运营↔线长」的数据范围表），
     * 只能停在功能码粒度；这是与商户域<b>有意不同</b>，不是漏改。
     */
    private void requireOwnerPermission(Long operatorId, String ownerType, String ownerId, boolean write) {
        if (PayoutConstants.PAYEE_OWNER_LINE_MANAGER.equals(ownerType)) {
            permissionService.requirePermission(operatorId, "ops:line-manager:edit");
        } else if (PayoutConstants.PAYEE_OWNER_MERCHANT.equals(ownerType)) {
            permissionService.requirePermission(operatorId, "ops:merchant:edit");
            // 功能码之后必须过数据范围闸：否则「能改商户」= 「能改所有商户」
            merchantScopeService.requireMerchantAccess(operatorId, ownerId);
        } else if (PayoutConstants.PAYEE_OWNER_PLATFORM.equals(ownerType) && !write) {
            permissionService.requirePermission(operatorId, "ops:finance:view");
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, write
                    ? "ownerType 只能是 LINE_MANAGER/MERCHANT（收款账户写侧不认平台/未知主体）"
                    : "ownerType 只能是 LINE_MANAGER/MERCHANT/PLATFORM");
        }
    }

    private Long operator(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }

    private static PayoutAccountDto toDto(PayoutAccount a) {
        return new PayoutAccountDto(
                a.getAccountId(),
                a.getAccountType(),
                a.getChannel(),
                a.getAccountName(),
                a.getAccountNoMask(),
                a.getBankName(),
                a.getBankBranch(),
                a.getTaxNo(),
                a.getIsDefault(),
                a.getStatus(),
                a.getCreatedAt()
        );
    }
}
