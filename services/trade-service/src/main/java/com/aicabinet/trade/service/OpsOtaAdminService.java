package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OtaReleaseDto;
import org.springframework.stereotype.Service;

import java.util.List;

/** OTA 发布门面（含权限校验）；自原 OpsCommercialFacade 按域抽出。 */
@Service
public class OpsOtaAdminService {

    private final PermissionService permissionService;
    private final OtaService otaService;

    public OpsOtaAdminService(PermissionService permissionService, OtaService otaService) {
        this.permissionService = permissionService;
        this.otaService = otaService;
    }

    public List<OtaReleaseDto> listOta(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:ota:list");
        return otaService.listReleases(operatorId);
    }

    public OtaReleaseDto publishOta(Long operatorId, OtaReleaseDto body) {
        permissionService.requirePermission(operatorId, "ops:ota:publish");
        return otaService.publishRelease(operatorId, body);
    }

    public OtaReleaseDto unpublishOta(Long operatorId, Long releaseId) {
        permissionService.requirePermission(operatorId, "ops:ota:publish");
        return otaService.unpublishRelease(operatorId, releaseId);
    }
}
