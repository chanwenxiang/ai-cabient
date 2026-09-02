package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.GenerateMonthlyFeeBillsRequest;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.SiteRentBillDto;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.SiteRentBillService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v2/ops/admin")
public class SiteRentBillController {

    private final SiteRentBillService billService;

    public SiteRentBillController(SiteRentBillService billService) {
        this.billService = billService;
    }

    @RequiresPermissions("ops:org:list")
    @GetMapping("/site-rent-bills")
    public ApiResponse<PageResult<SiteRentBillDto>> list(
            HttpServletRequest request,
            @RequestParam(required = false) String billMonth,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long contractId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(billService.list(operatorId(request), billMonth, status, contractId, page, size));
    }

    @RequiresPermissions("ops:org:list")
    @GetMapping("/site-contracts/{contractId}/rent-bills")
    public ApiResponse<List<SiteRentBillDto>> listByContract(
            HttpServletRequest request,
            @PathVariable Long contractId,
            @RequestParam(required = false) String billMonth) {
        return ApiResponse.ok(billService.listByContract(operatorId(request), contractId, billMonth));
    }

    @RequiresPermissions("ops:org:edit")
    @PostMapping("/site-contracts/{contractId}/rent-bills/generate")
    public ApiResponse<List<SiteRentBillDto>> generateOne(
            HttpServletRequest request,
            @PathVariable Long contractId,
            @Valid @RequestBody(required = false) GenerateMonthlyFeeBillsRequest body) {
        return ApiResponse.ok(billService.generateForContract(operatorId(request), contractId,
                body == null ? new GenerateMonthlyFeeBillsRequest(null) : body));
    }

    @RequiresPermissions("ops:org:edit")
    @PostMapping("/site-rent-bills/generate")
    public ApiResponse<List<SiteRentBillDto>> generateAll(
            HttpServletRequest request,
            @Valid @RequestBody(required = false) GenerateMonthlyFeeBillsRequest body) {
        return ApiResponse.ok(billService.generateForAllActive(operatorId(request),
                body == null ? new GenerateMonthlyFeeBillsRequest(null) : body));
    }

    @RequiresPermissions("ops:org:edit")
    @PostMapping("/site-rent-bills/{billId}/pay")
    public ApiResponse<SiteRentBillDto> markPaid(HttpServletRequest request, @PathVariable Long billId) {
        return ApiResponse.ok(billService.markPaid(operatorId(request), billId));
    }

    @RequiresPermissions("ops:org:edit")
    @PostMapping("/site-rent-bills/{billId}/void")
    public ApiResponse<SiteRentBillDto> voidBill(HttpServletRequest request, @PathVariable Long billId) {
        return ApiResponse.ok(billService.voidBill(operatorId(request), billId));
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
