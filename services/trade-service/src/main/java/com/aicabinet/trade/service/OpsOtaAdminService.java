package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OtaReleaseDto;
import com.aicabinet.common.dto.OtaUpgradeProgressDto;
import org.springframework.stereotype.Service;

import java.util.List;

/** OTA 发布门面（含权限校验）；自原 OpsCommercialFacade 按域抽出。 */
@Service
public class OpsOtaAdminService {

    /** 运营台进度列表的默认返回上限（一行一设备，够看全量小集群）。 */
    private static final int DEFAULT_REPORT_LIMIT = 200;

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

    /** 设备升级进度列表（O2）；复用既有的 ops:ota:list 权限，不新增权限点。 */
    public List<OtaUpgradeProgressDto> listProgress(Long operatorId, String status) {
        permissionService.requirePermission(operatorId, "ops:ota:list");
        return otaService.listProgress(status, DEFAULT_REPORT_LIMIT);
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
