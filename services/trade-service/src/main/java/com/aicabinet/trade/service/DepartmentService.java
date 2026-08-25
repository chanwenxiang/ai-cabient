package com.aicabinet.trade.service;

import com.aicabinet.common.dto.AssignDepartmentMembersRequest;
import com.aicabinet.common.dto.OpsDepartmentDto;
import com.aicabinet.common.dto.OpsDepartmentMembersDto;
import com.aicabinet.common.dto.UpsertOpsDepartmentRequest;
import com.aicabinet.trade.domain.OpsDepartment;
import com.aicabinet.trade.domain.OpsUserDepartment;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.OpsDepartmentMapper;
import com.aicabinet.trade.mapper.OpsUserDepartmentMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Service
public class DepartmentService {

    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE");

    private final OpsDepartmentMapper departmentRepository;
    private final OpsUserDepartmentMapper userDepartmentRepository;
    private final UserInfoMapper userInfoRepository;
    private final PermissionService permissionService;
    private final AdminAuditService auditService;

    public DepartmentService(OpsDepartmentMapper departmentRepository,
                             OpsUserDepartmentMapper userDepartmentRepository,
                             UserInfoMapper userInfoRepository,
                             PermissionService permissionService,
                             AdminAuditService auditService) {
        this.departmentRepository = departmentRepository;
        this.userDepartmentRepository = userDepartmentRepository;
        this.userInfoRepository = userInfoRepository;
        this.permissionService = permissionService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<OpsDepartmentDto> list(Long operatorId) {
        permissionService.requireAnyPermission(operatorId, "ops:dept:list", "ops:approval:config", "ops:rbac:assign");
        return departmentRepository.findAllOrderBySort().stream().map(this::toDto).toList();
    }

    @Transactional
    public OpsDepartmentDto upsert(Long operatorId, Long deptId, UpsertOpsDepartmentRequest req) {
        permissionService.requirePermission(operatorId, "ops:dept:edit");
        if (req == null || req.deptName() == null || req.deptName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "部门名称不能为空");
        }
        OpsDepartment row;
        if (deptId == null) {
            String key = normalizeKey(req.deptKey(), req.deptName());
            if (departmentRepository.findByDeptKey(key).isPresent()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "部门编码已存在: " + key);
            }
            row = new OpsDepartment();
            row.setDeptKey(key);
            row.setCreatedAt(Instant.now());
        } else {
            row = departmentRepository.selectById(deptId);
            if (row == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "部门不存在");
            }
        }
        Long parentId = req.parentId();
        if (parentId != null) {
            if (deptId != null && parentId.equals(deptId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上级部门不能是自己");
            }
            OpsDepartment parent = departmentRepository.selectById(parentId);
            if (parent == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上级部门不存在");
            }
            if (deptId != null && isAncestorOf(deptId, parentId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能将上级设为自己的下级");
            }
        }
        row.setParentId(parentId);
        row.setDeptName(req.deptName().trim());
        row.setSortOrder(req.sortOrder() == null ? 0 : req.sortOrder());
        String status = req.status() == null || req.status().isBlank() ? "ACTIVE" : req.status().trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status 仅支持 ACTIVE/INACTIVE");
        }
        row.setStatus(status);
        row.setRemark(trimToNull(req.remark()));
        row.setUpdatedAt(Instant.now());
        if (row.getDeptId() == null) {
            departmentRepository.insert(row);
        } else {
            departmentRepository.updateById(row);
        }
        auditService.record(operatorId, "OPS_DEPT_UPSERT", "OPS_DEPT",
                String.valueOf(row.getDeptId()), row.getDeptKey() + ":" + row.getDeptName());
        return toDto(row);
    }

    @Transactional(readOnly = true)
    public OpsDepartmentMembersDto members(Long operatorId, Long deptId) {
        permissionService.requireAnyPermission(operatorId, "ops:dept:list", "ops:dept:edit");
        OpsDepartment dept = requireDept(deptId);
        List<Long> userIds = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (OpsUserDepartment ud : userDepartmentRepository.findByDeptId(deptId)) {
            userIds.add(ud.getUserId());
            UserInfo u = userInfoRepository.selectById(ud.getUserId());
            names.add(u == null ? String.valueOf(ud.getUserId())
                    : (u.getName() == null || u.getName().isBlank() ? u.getPhoneNumber() : u.getName()));
        }
        return new OpsDepartmentMembersDto(dept.getDeptId(), dept.getDeptKey(), dept.getDeptName(), userIds, names);
    }

    @Transactional
    public OpsDepartmentMembersDto assignMembers(Long operatorId, Long deptId, AssignDepartmentMembersRequest req) {
        permissionService.requirePermission(operatorId, "ops:dept:edit");
        OpsDepartment dept = requireDept(deptId);
        Set<Long> next = new LinkedHashSet<>();
        if (req != null && req.userIds() != null) {
            for (Long userId : req.userIds()) {
                if (userId == null || userId < 100000001L) {
                    continue;
                }
                UserInfo u = userInfoRepository.selectById(userId);
                if (u == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户不存在: " + userId);
                }
                next.add(userId);
            }
        }
        Set<Long> previousPrimaries = new HashSet<>();
        for (OpsUserDepartment ud : userDepartmentRepository.findByDeptId(deptId)) {
            if (Boolean.TRUE.equals(ud.getIsPrimary())) {
                previousPrimaries.add(ud.getUserId());
            }
        }
        userDepartmentRepository.deleteByDeptId(deptId);
        for (Long userId : next) {
            boolean makePrimary = previousPrimaries.contains(userId)
                    || userDepartmentRepository.findByUserId(userId).isEmpty();
            OpsUserDepartment row = new OpsUserDepartment(userId, deptId, makePrimary);
            userDepartmentRepository.insert(row);
            if (makePrimary) {
                clearOtherPrimaries(userId, deptId);
            }
        }
        for (Long leftUserId : previousPrimaries) {
            if (!next.contains(leftUserId)) {
                ensureHasPrimary(leftUserId);
            }
        }
        auditService.record(operatorId, "OPS_DEPT_MEMBERS", "OPS_DEPT",
                String.valueOf(deptId), "members=" + next.size());
        return members(operatorId, dept.getDeptId());
    }

    /**
     * 替换用户全部部门归属；primaryDeptId 为空时取列表第一个为主部门。
     */
    @Transactional
    public void replaceUserDepartments(Long userId, List<Long> deptIds, Long primaryDeptId) {
        if (userId == null || userId < 100000001L) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法运营账号");
        }
        LinkedHashSet<Long> next = new LinkedHashSet<>();
        if (deptIds != null) {
            for (Long deptId : deptIds) {
                if (deptId == null) continue;
                requireDept(deptId);
                next.add(deptId);
            }
        }
        Long primary = primaryDeptId;
        if (primary != null && !next.contains(primary)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "主部门必须在所属部门列表中");
        }
        if (primary == null && !next.isEmpty()) {
            primary = next.iterator().next();
        }
        userDepartmentRepository.deleteByUserId(userId);
        for (Long deptId : next) {
            boolean isPrimary = Objects.equals(deptId, primary);
            userDepartmentRepository.insert(new OpsUserDepartment(userId, deptId, isPrimary));
        }
    }

    private void clearOtherPrimaries(Long userId, Long keepDeptId) {
        userDepartmentRepository.update(null, Wrappers.<OpsUserDepartment>lambdaUpdate()
                .set(OpsUserDepartment::getIsPrimary, false)
                .eq(OpsUserDepartment::getUserId, userId)
                .eq(OpsUserDepartment::getIsPrimary, true)
                .ne(OpsUserDepartment::getDeptId, keepDeptId));
    }

    private void ensureHasPrimary(Long userId) {
        List<OpsUserDepartment> list = userDepartmentRepository.findByUserId(userId);
        if (list.isEmpty()) {
            return;
        }
        boolean has = list.stream().anyMatch(ud -> Boolean.TRUE.equals(ud.getIsPrimary()));
        if (!has) {
            OpsUserDepartment first = list.get(0);
            userDepartmentRepository.update(null, Wrappers.<OpsUserDepartment>lambdaUpdate()
                    .set(OpsUserDepartment::getIsPrimary, true)
                    .eq(OpsUserDepartment::getUserId, userId)
                    .eq(OpsUserDepartment::getDeptId, first.getDeptId()));
        }
    }

    /** parentId 是否在 deptId 的子孙链上（会死循环的挂靠） */
    private boolean isAncestorOf(Long deptId, Long candidateParentId) {
        Long cursor = candidateParentId;
        int guard = 0;
        while (cursor != null && guard++ < 64) {
            if (cursor.equals(deptId)) {
                return true;
            }
            OpsDepartment p = departmentRepository.selectById(cursor);
            cursor = p == null ? null : p.getParentId();
        }
        return false;
    }

    private OpsDepartment requireDept(Long deptId) {
        OpsDepartment dept = departmentRepository.selectById(deptId);
        if (dept == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "部门不存在");
        }
        return dept;
    }

    private OpsDepartmentDto toDto(OpsDepartment d) {
        return new OpsDepartmentDto(
                d.getDeptId(),
                d.getDeptKey(),
                d.getDeptName(),
                d.getParentId(),
                d.getSortOrder(),
                d.getStatus(),
                d.getRemark(),
                (int) userDepartmentRepository.countByDeptId(d.getDeptId()),
                d.getUpdatedAt());
    }

    private static String normalizeKey(String deptKey, String deptName) {
        if (deptKey != null && !deptKey.isBlank()) {
            return deptKey.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
        }
        String base = deptName.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
        if (base.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "部门编码不能为空");
        }
        return base;
    }

    private static String trimToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}
