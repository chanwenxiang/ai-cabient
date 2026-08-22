package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OrgNodeDto;
import com.aicabinet.common.dto.UpsertOrgNodeRequest;
import com.aicabinet.trade.domain.OpsDeviceOrg;
import com.aicabinet.trade.domain.OpsOrgNode;
import com.aicabinet.trade.mapper.OpsDeviceOrgMapper;
import com.aicabinet.trade.mapper.OpsOrgNodeMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 组织架构：总部/区域/分公司组织树，设备按节点归属，支撑点位管理。
 */
@Service
public class OrgService {

    private final OpsOrgNodeMapper nodeRepository;
    private final OpsDeviceOrgMapper deviceOrgRepository;
    private final PermissionService permissionService;
    private final AdminAuditService auditService;
    private final DistributedLockService distributedLockService;

    public OrgService(OpsOrgNodeMapper nodeRepository,
                      OpsDeviceOrgMapper deviceOrgRepository,
                      PermissionService permissionService,
                      AdminAuditService auditService,
                      DistributedLockService distributedLockService) {
        this.nodeRepository = nodeRepository;
        this.deviceOrgRepository = deviceOrgRepository;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.distributedLockService = distributedLockService;
    }

    @Transactional(readOnly = true)
    public List<OrgNodeDto> tree(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:device:list");
        Map<Long, List<String>> devicesByNode = new HashMap<>();
        for (OpsDeviceOrg mapping : deviceOrgRepository.findAll()) {
            devicesByNode.computeIfAbsent(mapping.getNodeId(), k -> new ArrayList<>())
                    .add(mapping.getDeviceId());
        }
        Map<Long, OrgNodeDto> byId = new HashMap<>();
        for (OpsOrgNode node : nodeRepository.findAllOrderBySort()) {
            byId.put(node.getNodeId(), new OrgNodeDto(
                    node.getNodeId(), node.getParentId(), node.getName(),
                    node.getNodeType(), node.getSortOrder(), node.isEnabled(),
                    devicesByNode.getOrDefault(node.getNodeId(), List.of()),
                    new ArrayList<>()));
        }
        List<OrgNodeDto> roots = new ArrayList<>();
        for (OrgNodeDto node : byId.values()) {
            if (node.parentId() == null || !byId.containsKey(node.parentId())) {
                roots.add(node);
            } else {
                byId.get(node.parentId()).children().add(node);
            }
        }
        return roots;
    }

    @Transactional
    public OrgNodeDto upsertNode(Long operatorId, UpsertOrgNodeRequest request) {
        if (request.nodeId() != null) {
            return runWithOrgNodeLock(request.nodeId(),
                    () -> doUpsertNode(operatorId, request));
        }
        return doUpsertNode(operatorId, request);
    }

    private OrgNodeDto doUpsertNode(Long operatorId, UpsertOrgNodeRequest request) {
        permissionService.requirePermission(operatorId, "ops:device:edit");
        String name = request.name().trim();
        if (name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "组织名称不能为空");
        }
        if (request.parentId() != null && request.parentId().equals(request.nodeId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上级组织不能是自己");
        }
        OpsOrgNode node;
        if (request.nodeId() == null) {
            node = new OpsOrgNode();
            node.setName(name);
            node.setParentId(request.parentId());
            node.setNodeType(request.nodeType() == null || request.nodeType().isBlank()
                    ? "BRANCH" : request.nodeType().trim().toUpperCase());
            node.setSortOrder(request.sortOrder());
            node.setEnabled(true);
            node.setCreatedAt(Instant.now());
            node.setUpdatedAt(Instant.now());
            nodeRepository.insert(node);
            auditService.record(operatorId, "ORG_NODE_CREATE", "ORG_NODE",
                    String.valueOf(node.getNodeId()), "name=" + name);
        } else {
            node = nodeRepository.findByIdForUpdate(request.nodeId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "组织不存在"));
            node.setName(name);
            node.setParentId(request.parentId());
            node.setNodeType(request.nodeType() == null || request.nodeType().isBlank()
                    ? node.getNodeType() : request.nodeType().trim().toUpperCase());
            node.setSortOrder(request.sortOrder());
            node.setUpdatedAt(Instant.now());
            nodeRepository.updateById(node);
            auditService.record(operatorId, "ORG_NODE_UPDATE", "ORG_NODE",
                    String.valueOf(node.getNodeId()), "name=" + name);
        }
        return toDto(node, deviceOrgRepository.findByNodeId(node.getNodeId()));
    }

    @Transactional
    public OrgNodeDto toggleNode(Long operatorId, Long nodeId, boolean enabled) {
        return runWithOrgNodeLock(nodeId, () -> {
            permissionService.requirePermission(operatorId, "ops:device:edit");
            OpsOrgNode node = requireNodeForUpdate(nodeId);
            node.setEnabled(enabled);
            node.setUpdatedAt(Instant.now());
            nodeRepository.updateById(node);
            auditService.record(operatorId, enabled ? "ORG_NODE_ENABLE" : "ORG_NODE_DISABLE",
                    "ORG_NODE", String.valueOf(nodeId), "name=" + node.getName());
            return toDto(node, deviceOrgRepository.findByNodeId(nodeId));
        });
    }

    @Transactional
    public OrgNodeDto assignDevices(Long operatorId, Long nodeId, List<String> deviceIds) {
        List<String> normalized = deviceIds == null ? List.of()
                : deviceIds.stream().filter(d -> d != null && !d.isBlank())
                .map(d -> d.trim().toUpperCase()).distinct().sorted().toList();
        List<String> lockKeys = new ArrayList<>();
        lockKeys.add(orgNodeLockKey(nodeId));
        normalized.forEach(d -> lockKeys.add(orgDeviceAssignLockKey(d)));
        lockKeys.sort(Comparator.naturalOrder());
        List<String> acquired = new ArrayList<>();
        try {
            for (String key : lockKeys) {
                if (!distributedLockService.tryLock(key, 60, 5)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "组织设备归属处理中，请稍后重试");
                }
                acquired.add(key);
            }
            return doAssignDevices(operatorId, nodeId, normalized);
        } finally {
            for (int i = acquired.size() - 1; i >= 0; i--) {
                distributedLockService.unlock(acquired.get(i));
            }
        }
    }

    private OrgNodeDto doAssignDevices(Long operatorId, Long nodeId, List<String> normalized) {
        permissionService.requirePermission(operatorId, "ops:device:edit");
        OpsOrgNode node = requireNodeForUpdate(nodeId);
        deviceOrgRepository.deleteByDeviceIds(normalized);
        deviceOrgRepository.deleteByNodeId(nodeId);
        for (String deviceId : normalized) {
            OpsDeviceOrg mapping = new OpsDeviceOrg();
            mapping.setNodeId(nodeId);
            mapping.setDeviceId(deviceId);
            deviceOrgRepository.insert(mapping);
        }
        auditService.record(operatorId, "ORG_NODE_ASSIGN", "ORG_NODE",
                String.valueOf(nodeId), "devices=" + normalized.size());
        return toDto(node, deviceOrgRepository.findByNodeId(nodeId));
    }

    @Transactional
    public void deleteNode(Long operatorId, Long nodeId) {
        runWithOrgNodeLock(nodeId, () -> {
            permissionService.requireAnyPermission(operatorId, "ops:org:edit", "ops:device:edit");
            OpsOrgNode node = requireNodeForUpdate(nodeId);
            if (nodeRepository.countByParentId(nodeId) > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "请先删除子组织");
            }
            if (!deviceOrgRepository.findByNodeId(nodeId).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "请先解除该组织下的设备归属");
            }
            nodeRepository.deleteById(nodeId);
            auditService.record(operatorId, "ORG_NODE_DELETE", "ORG_NODE",
                    String.valueOf(nodeId), "name=" + node.getName());
            return null;
        });
    }

    private OpsOrgNode requireNode(Long nodeId) {
        return nodeRepository.findById(nodeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "组织不存在"));
    }

    private OpsOrgNode requireNodeForUpdate(Long nodeId) {
        return nodeRepository.findByIdForUpdate(nodeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "组织不存在"));
    }

    static String orgNodeLockKey(Long nodeId) {
        return "org:node:" + nodeId;
    }

    static String orgDeviceAssignLockKey(String deviceId) {
        return "org:device:" + deviceId;
    }

    private <T> T runWithOrgNodeLock(Long nodeId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(orgNodeLockKey(nodeId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "组织节点处理中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            distributedLockService.unlock(orgNodeLockKey(nodeId));
        }
    }

    private OrgNodeDto toDto(OpsOrgNode node, List<OpsDeviceOrg> mappings) {
        return new OrgNodeDto(
                node.getNodeId(), node.getParentId(), node.getName(), node.getNodeType(),
                node.getSortOrder(), node.isEnabled(),
                mappings.stream().map(OpsDeviceOrg::getDeviceId).toList(),
                List.of());
    }
}
