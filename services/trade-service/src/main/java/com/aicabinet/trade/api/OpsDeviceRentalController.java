package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.domain.DeviceRentalBill;
import com.aicabinet.trade.domain.DeviceRentalDeposit;
import com.aicabinet.trade.domain.DeviceRentalDepositEvent;
import com.aicabinet.trade.service.DeviceRentalService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 设备租赁闭环（CB-034）：押金收据化（登记/扣款/结算）+ 月租应收账单（出账/标记已付/作废）。
 *
 * <p>查看沿用「资金账单」菜单既有权限 {@code ops:fund:list}；
 * 写动作为新增按钮权限 {@code ops:rental:manage}（V342 种子）。</p>
 */
@RestController
@RequestMapping("/api/v2/ops/admin/device-rental")
public class OpsDeviceRentalController {

    private final DeviceRentalService rentalService;

    public OpsDeviceRentalController(DeviceRentalService rentalService) {
        this.rentalService = rentalService;
    }

    // ── 押金 ──────────────────────────────────────────────────────────────

    @RequiresPermissions("ops:fund:list")
    @GetMapping("/deposits")
    public ApiResponse<List<DeviceRentalDeposit>> deposits() {
        return ApiResponse.ok(rentalService.listDeposits());
    }

    @RequiresPermissions("ops:fund:list")
    @GetMapping("/deposits/{depositId}/events")
    public ApiResponse<List<DeviceRentalDepositEvent>> depositEvents(@PathVariable long depositId) {
        return ApiResponse.ok(rentalService.listDepositEvents(depositId));
    }

    @RequiresPermissions("ops:rental:manage")
    @PostMapping("/deposits")
    public ApiResponse<DeviceRentalDeposit> registerDeposit(@RequestBody Map<String, Object> body,
                                                            HttpServletRequest request) {
        return ApiResponse.ok(rentalService.registerDeposit(operatorId(request), str(body.get("deviceId")),
                amount(body), str(body.get("remark")),
                longOrNull(body.get("monthlyRefundCents")), intOrNull(body.get("refundStartMonth")),
                longOrNull(body.get("expireRewardCents")),
                longOrNull(body.get("installFeeDeductCents")), boolOrNull(body.get("installFeeClawbackEnabled")),
                intOrNull(body.get("contractYears"))));
    }

    @RequiresPermissions("ops:rental:manage")
    @PostMapping("/deposits/{depositId}/deduct")
    public ApiResponse<DeviceRentalDeposit> deduct(@PathVariable long depositId,
                                                   @RequestBody Map<String, Object> body,
                                                   HttpServletRequest request) {
        return ApiResponse.ok(rentalService.deduct(operatorId(request), depositId,
                amount(body), str(body.get("reason"))));
    }

    @RequiresPermissions("ops:rental:manage")
    @PostMapping("/deposits/{depositId}/settle")
    public ApiResponse<DeviceRentalDeposit> settle(@PathVariable long depositId,
                                                   @RequestBody(required = false) Map<String, Object> body,
                                                   HttpServletRequest request) {
        return ApiResponse.ok(rentalService.settle(operatorId(request), depositId,
                body == null ? null : str(body.get("remark"))));
    }

    // ── 月租账单 ──────────────────────────────────────────────────────────

    @RequiresPermissions("ops:fund:list")
    @GetMapping("/bills")
    public ApiResponse<PageResult<DeviceRentalBill>> bills(@RequestParam(required = false) String billMonth,
                                                           @RequestParam(required = false) String status,
                                                           @RequestParam(required = false) String deviceId,
                                                           @RequestParam(defaultValue = "1") int page,
                                                           @RequestParam(defaultValue = "20") int size,
                                                           HttpServletRequest request) {
        return ApiResponse.ok(rentalService.listBills(operatorId(request), billMonth, status, deviceId, page, size));
    }

    @RequiresPermissions("ops:rental:manage")
    @PostMapping("/bills/generate")
    public ApiResponse<Object> generate(@RequestBody Map<String, Object> body,
                                        HttpServletRequest request) {
        String deviceId = str(body.get("deviceId"));
        String billMonth = str(body.get("billMonth"));
        if (deviceId != null && !deviceId.isBlank()) {
            return ApiResponse.ok(rentalService.generateBill(operatorId(request), deviceId, billMonth));
        }
        return ApiResponse.ok(rentalService.generateAll(operatorId(request), billMonth));
    }

    @RequiresPermissions("ops:rental:manage")
    @PostMapping("/bills/{billId}/mark-paid")
    public ApiResponse<DeviceRentalBill> markPaid(@PathVariable long billId, HttpServletRequest request) {
        return ApiResponse.ok(rentalService.markPaid(operatorId(request), billId));
    }

    @RequiresPermissions("ops:rental:manage")
    @PostMapping("/bills/{billId}/void")
    public ApiResponse<DeviceRentalBill> voidBill(@PathVariable long billId, HttpServletRequest request) {
        return ApiResponse.ok(rentalService.voidBill(operatorId(request), billId));
    }

    private static long amount(Map<String, Object> body) {
        Number n = (Number) body.get("amountCents");
        return n == null ? 0L : n.longValue();
    }

    private static Long longOrNull(Object v) {
        if (v == null) return null;
        try { return Long.parseLong(String.valueOf(v).trim()); } catch (NumberFormatException e) { return null; }
    }

    private static Integer intOrNull(Object v) {
        if (v == null) return null;
        try { return Integer.parseInt(String.valueOf(v).trim()); } catch (NumberFormatException e) { return null; }
    }

    private static Boolean boolOrNull(Object v) {
        if (v == null) return null;
        return Boolean.parseBoolean(String.valueOf(v).trim());
    }


    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
