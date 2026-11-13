package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.OpsOpenDoorRequest;
import com.aicabinet.common.dto.SessionDto;
import com.aicabinet.common.dto.SkuCatalogDto;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.AdminAuditService;
import com.aicabinet.trade.service.OpsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v2/ops")
public class OpsController {

    private final OpsService opsService;
    private final AdminAuditService auditService;

    public OpsController(OpsService opsService, AdminAuditService auditService) {
        this.opsService = opsService;
        this.auditService = auditService;
    }

    /** 商品目录（争议审核 / 异常选品） */
    @RequiresPermissions(value = {"ops:sku:list", "ops:dispute", "ops:exception:handle"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/skus")
    public ApiResponse<List<SkuCatalogDto>> listSkus() {
        return ApiResponse.ok(opsService.listSkus());
    }

    /** 运营补货开门 */
    @RequiresPermissions("ops:replenishment:edit")
    @PostMapping("/restock/open-door")
    public ApiResponse<SessionDto> openDoorForRestock(
            HttpServletRequest request,
            @Valid @RequestBody OpsOpenDoorRequest body) {
        Long operatorId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        SessionDto session = opsService.openDoorForRestock(operatorId, body);
        auditService.appendLog(operatorId, "OPS_RESTOCK_OPEN_DOOR", "SHOPPING_SESSION",
                session == null ? null : session.sessionId(),
                "设备=" + body.deviceId() + " 任务=" + body.taskId());
        return ApiResponse.ok(session);
    }
}
