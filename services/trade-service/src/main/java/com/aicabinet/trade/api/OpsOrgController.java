package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.AssignOrgDevicesRequest;
import com.aicabinet.common.dto.OrgNodeDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.SiteContractDto;
import com.aicabinet.common.dto.UpsertOrgNodeRequest;
import com.aicabinet.common.dto.UpsertSiteContractRequest;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.OrgService;
import com.aicabinet.trade.service.SiteContractService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 组织架构与点位合同（从 {@link OpsCommercialController} 按域抽出；URL 不变）。
 */
@RestController
@RequestMapping("/api/v2/ops/admin")
public class OpsOrgController {

    private final OrgService orgService;
    private final SiteContractService siteContractService;

    public OpsOrgController(OrgService orgService, SiteContractService siteContractService) {
        this.orgService = orgService;
        this.siteContractService = siteContractService;
    }

    @RequiresPermissions("ops:org:list")
    @GetMapping("/org/tree")
    public ApiResponse<List<OrgNodeDto>> orgTree(HttpServletRequest request) {
        return ApiResponse.ok(orgService.tree(operatorId(request)));
    }

    @RequiresPermissions("ops:org:edit")
    @PutMapping("/org/nodes")
    public ApiResponse<OrgNodeDto> upsertOrgNode(
            HttpServletRequest request,
            @Valid @RequestBody UpsertOrgNodeRequest body) {
        return ApiResponse.ok(orgService.upsertNode(operatorId(request), body));
    }

    @RequiresPermissions("ops:org:edit")
    @PostMapping("/org/nodes/{nodeId}/toggle")
    public ApiResponse<OrgNodeDto> toggleOrgNode(
            HttpServletRequest request,
            @PathVariable Long nodeId,
            @RequestParam boolean enabled) {
        return ApiResponse.ok(orgService.toggleNode(operatorId(request), nodeId, enabled));
    }

    @RequiresPermissions("ops:org:edit")
    @PutMapping("/org/nodes/{nodeId}/devices")
    public ApiResponse<OrgNodeDto> assignOrgDevices(
            HttpServletRequest request,
            @PathVariable Long nodeId,
            @Valid @RequestBody AssignOrgDevicesRequest body) {
        return ApiResponse.ok(orgService.assignDevices(operatorId(request), nodeId, body.deviceIds()));
    }

    @RequiresPermissions("ops:org:edit")
    @DeleteMapping("/org/nodes/{nodeId}")
    public ApiResponse<Void> deleteOrgNode(
            HttpServletRequest request, @PathVariable Long nodeId) {
        orgService.deleteNode(operatorId(request), nodeId);
        return ApiResponse.ok(null);
    }

    @RequiresPermissions("ops:org:list")
    @GetMapping("/site-contracts")
    public ApiResponse<PageResult<SiteContractDto>> siteContracts(
            HttpServletRequest request,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(siteContractService.listPage(operatorId(request), page, size));
    }

    @RequiresPermissions("ops:org:edit")
    @PutMapping("/site-contracts/{deviceId}")
    public ApiResponse<SiteContractDto> upsertSiteContract(
            HttpServletRequest request,
            @PathVariable String deviceId,
            @Valid @RequestBody UpsertSiteContractRequest body) {
        return ApiResponse.ok(siteContractService.upsert(operatorId(request), deviceId, body));
    }

    @RequiresPermissions("ops:org:edit")
    @DeleteMapping("/site-contracts/{contractId}")
    public ApiResponse<Void> deleteSiteContract(
            HttpServletRequest request, @PathVariable Long contractId) {
        siteContractService.delete(operatorId(request), contractId);
        return ApiResponse.ok(null);
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
