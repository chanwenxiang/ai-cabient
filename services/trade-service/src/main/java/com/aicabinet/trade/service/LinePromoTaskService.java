package com.aicabinet.trade.service;

import com.aicabinet.common.dto.LinePromoTaskDto;
import com.aicabinet.common.dto.UpsertLinePromoTaskRequest;
import com.aicabinet.trade.domain.LinePromoTask;
import com.aicabinet.trade.mapper.LinePromoTaskMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Service
public class LinePromoTaskService {

    private static final Set<String> STATUSES = Set.of("OPEN", "DONE", "CANCELLED");

    private final LinePromoTaskMapper taskMapper;
    private final LineManagerService lineManagerService;
    private final PermissionService permissionService;
    private final AdminAuditService auditService;

    public LinePromoTaskService(LinePromoTaskMapper taskMapper,
                                LineManagerService lineManagerService,
                                PermissionService permissionService,
                                AdminAuditService auditService) {
        this.taskMapper = taskMapper;
        this.lineManagerService = lineManagerService;
        this.permissionService = permissionService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<LinePromoTaskDto> list(Long operatorId, Long managerId, String status) {
        permissionService.requireAnyPermission(operatorId, "ops:line-manager:list", "ops:finance:view");
        return taskMapper.findByManager(managerId, status).stream().map(this::toDto).toList();
    }

    @Transactional
    public LinePromoTaskDto upsert(Long operatorId, Long taskId, UpsertLinePromoTaskRequest req) {
        permissionService.requirePermission(operatorId, "ops:line-manager:edit");
        lineManagerService.requireManager(req.managerId());
        LinePromoTask task;
        if (taskId != null) {
            task = taskMapper.selectById(taskId);
            if (task == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "地推任务不存在");
            }
        } else {
            task = new LinePromoTask();
            task.setCreatedAt(Instant.now());
        }
        task.setManagerId(req.managerId());
        task.setTitle(req.title().trim());
        task.setRouteCode(blankToNull(req.routeCode()));
        task.setTargetQty(Math.max(0, req.targetQty()));
        task.setBountyCents(Math.max(0, req.bountyCents()));
        task.setDueDate(req.dueDate());
        if (req.doneQty() != null) {
            task.setDoneQty(Math.max(0, req.doneQty()));
        }
        String status = req.status() == null || req.status().isBlank() ? "OPEN" : req.status().trim().toUpperCase();
        if (!STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法 status");
        }
        if (task.getDoneQty() >= task.getTargetQty() && task.getTargetQty() > 0 && "OPEN".equals(status)) {
            status = "DONE";
        }
        task.setStatus(status);
        task.setUpdatedAt(Instant.now());
        if (task.getTaskId() == null) {
            taskMapper.insert(task);
        } else {
            taskMapper.updateById(task);
        }
        auditService.record(operatorId, "LINE_PROMO_TASK", "TASK", String.valueOf(task.getTaskId()), status);
        return toDto(task);
    }

    private LinePromoTaskDto toDto(LinePromoTask t) {
        return new LinePromoTaskDto(
                t.getTaskId(), t.getManagerId(), t.getTitle(), t.getRouteCode(),
                t.getTargetQty(), t.getDoneQty(), t.getBountyCents(), t.getStatus(),
                t.getDueDate(), t.getUpdatedAt());
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
