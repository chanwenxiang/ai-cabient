package com.aicabinet.trade.service;

import com.aicabinet.common.dto.LinePromoTaskDto;
import com.aicabinet.common.dto.UpsertLinePromoTaskRequest;
import com.aicabinet.trade.domain.LinePromoTask;
import com.aicabinet.trade.mapper.LinePromoTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Service
public class LinePromoTaskService {

    private static final Logger log = LoggerFactory.getLogger(LinePromoTaskService.class);
    private static final Set<String> STATUSES = Set.of("OPEN", "DONE", "CANCELLED");
    private static final String STATUS_DONE = "DONE";
    private static final String ENTRY_BOUNTY = "BOUNTY";
    private static final String REF_LINE_PROMO = "LINE_PROMO";

    private final LinePromoTaskMapper taskMapper;
    private final LineManagerService lineManagerService;
    private final PermissionService permissionService;
    private final AdminAuditService auditService;
    private final DistributedLockService distributedLockService;
    private final LineWalletService lineWalletService;

    public LinePromoTaskService(LinePromoTaskMapper taskMapper,
                                LineManagerService lineManagerService,
                                PermissionService permissionService,
                                AdminAuditService auditService,
                                DistributedLockService distributedLockService,
                                LineWalletService lineWalletService) {
        this.taskMapper = taskMapper;
        this.lineManagerService = lineManagerService;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.distributedLockService = distributedLockService;
        this.lineWalletService = lineWalletService;
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
        if (taskId != null) {
            return runWithTaskLock(taskId, () -> doUpsert(operatorId, taskId, req));
        }
        return runWithManagerLock(req.managerId(), () -> doUpsert(operatorId, null, req));
    }

    private LinePromoTaskDto doUpsert(Long operatorId, Long taskId, UpsertLinePromoTaskRequest req) {
        LinePromoTask task;
        String previousStatus = null;
        if (taskId != null) {
            task = taskMapper.findByIdForUpdate(taskId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "地推任务不存在"));
            previousStatus = task.getStatus();
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
            status = STATUS_DONE;
        }
        task.setStatus(status);
        task.setUpdatedAt(Instant.now());
        if (task.getTaskId() == null) {
            taskMapper.insert(task);
        } else {
            taskMapper.updateById(task);
        }
        maybeCreditBounty(task, previousStatus);
        auditService.appendLog(operatorId, "LINE_PROMO_TASK", "TASK", String.valueOf(task.getTaskId()), status);
        return toDto(task);
    }

    /**
     * OPEN→DONE（含完成量自动达标）时按赏金入账；幂等依赖钱包 creditIfAbsent(ref=taskId)。
     */
    private void maybeCreditBounty(LinePromoTask task, String previousStatus) {
        if (!STATUS_DONE.equals(task.getStatus()) || STATUS_DONE.equals(previousStatus)) {
            return;
        }
        int bounty = Math.max(0, task.getBountyCents());
        if (bounty <= 0 || task.getTaskId() == null || task.getManagerId() == null) {
            return;
        }
        boolean credited = lineWalletService.creditIfAbsent(
                task.getManagerId(),
                bounty,
                ENTRY_BOUNTY,
                REF_LINE_PROMO,
                String.valueOf(task.getTaskId()),
                "地推赏金：" + task.getTitle());
        log.info("line promo bounty credit managerId={} taskId={} bountyCents={} credited={}",
                task.getManagerId(), task.getTaskId(), bounty, credited);
    }

    static String linePromoTaskLockKey(Long taskId) {
        return "line-promo:task:" + taskId;
    }

    static String linePromoManagerLockKey(Long managerId) {
        return "line-promo:manager:" + managerId;
    }

    private <T> T runWithTaskLock(Long taskId, java.util.function.Supplier<T> action) {
        String key = linePromoTaskLockKey(taskId);
        if (!distributedLockService.tryLock(key, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "地推任务处理中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            distributedLockService.unlock(key);
        }
    }

    private <T> T runWithManagerLock(Long managerId, java.util.function.Supplier<T> action) {
        String key = linePromoManagerLockKey(managerId);
        if (!distributedLockService.tryLock(key, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "地推任务处理中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            distributedLockService.unlock(key);
        }
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
