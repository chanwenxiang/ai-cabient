package com.aicabinet.trade.service;

import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.RepairTicketDetailDto;
import com.aicabinet.common.dto.RepairTicketDto;
import com.aicabinet.common.dto.RepairTicketEventDto;
import com.aicabinet.trade.domain.RepairTicket;
import com.aicabinet.trade.domain.RepairTicketEvent;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.RepairTicketEventMapper;
import com.aicabinet.trade.mapper.RepairTicketMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class RepairTicketService {
    private static final String PERM_OPS_DEVICE_LIST = "ops:device:list";
    private static final String PERM_OPS_REPAIR_EDIT = "ops:repair:edit";
    private static final String PERM_OPS_REPAIR_LIST = "ops:repair:list";
    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final String STATUS_NORMAL = "NORMAL";


    private static final Set<String> STATUSES = Set.of("OPEN", STATUS_IN_PROGRESS, "DONE", STATUS_CANCELLED);
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "OPEN", Set.of(STATUS_IN_PROGRESS, STATUS_CANCELLED),
            STATUS_IN_PROGRESS, Set.of("DONE", STATUS_CANCELLED, "OPEN"),
            "DONE", Set.of(),
            STATUS_CANCELLED, Set.of()
    );

    private final RepairTicketMapper ticketMapper;
    private final RepairTicketEventMapper eventMapper;
    private final DeviceInfoMapper deviceInfoMapper;
    private final PermissionService permissionService;
    private final DeviceSalesLockService salesLockService;
    private final OpsExceptionService opsExceptionService;
    private final DistributedLockService distributedLockService;
    /** 经 Spring 代理调用本类 @Transactional 方法，避免自调用失效。 */
    private final RepairTicketService self;

    public RepairTicketService(RepairTicketMapper ticketMapper,
                               RepairTicketEventMapper eventMapper,
                               DeviceInfoMapper deviceInfoMapper,
                               PermissionService permissionService,
                               DeviceSalesLockService salesLockService,
                               @Lazy OpsExceptionService opsExceptionService,
                               DistributedLockService distributedLockService, @Lazy RepairTicketService self) {
        this.ticketMapper = ticketMapper;
        this.eventMapper = eventMapper;
        this.deviceInfoMapper = deviceInfoMapper;
        this.permissionService = permissionService;
        this.salesLockService = salesLockService;
        this.opsExceptionService = opsExceptionService;
        this.distributedLockService = distributedLockService;
        this.self = self;
    }

    @Transactional(readOnly = true)
    public PageResult<RepairTicketDto> list(Long operatorId, String status, String deviceId,
                                            String priority, int page, int size) {
        permissionService.requireAnyPermission(operatorId, PERM_OPS_REPAIR_LIST, PERM_OPS_DEVICE_LIST);
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        LambdaQueryWrapper<RepairTicket> q = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            q.eq(RepairTicket::getStatus, status.trim().toUpperCase(Locale.ROOT));
        }
        if (deviceId != null && !deviceId.isBlank()) {
            q.eq(RepairTicket::getDeviceId, deviceId.trim());
        }
        if (priority != null && !priority.isBlank()) {
            q.eq(RepairTicket::getPriority, priority.trim().toUpperCase(Locale.ROOT));
        }
        q.orderByDesc(RepairTicket::getCreatedAt);
        Page<RepairTicket> result = ticketMapper.selectPage(new Page<>(p + 1L, s), q);
        List<RepairTicketDto> items = result.getRecords().stream().map(this::toDto).toList();
        return new PageResult<>(items, p, s, result.getTotal());
    }

    @Transactional(readOnly = true)
    public List<RepairTicketDto> listByDevice(Long operatorId, String deviceId, int limit) {
        permissionService.requireAnyPermission(operatorId, PERM_OPS_REPAIR_LIST, PERM_OPS_DEVICE_LIST, "ops:device:edit");
        int lim = Math.min(Math.max(limit, 1), 50);
        return ticketMapper.selectList(new LambdaQueryWrapper<RepairTicket>()
                        .eq(RepairTicket::getDeviceId, deviceId)
                        .orderByDesc(RepairTicket::getCreatedAt)
                        .last("LIMIT " + lim))
                .stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public RepairTicketDetailDto detail(Long operatorId, long ticketId) {
        permissionService.requireAnyPermission(operatorId, PERM_OPS_REPAIR_LIST, PERM_OPS_DEVICE_LIST);
        RepairTicket ticket = requireTicket(ticketId);
        List<RepairTicketEventDto> events = eventMapper.selectList(new LambdaQueryWrapper<RepairTicketEvent>()
                        .eq(RepairTicketEvent::getTicketId, ticketId)
                        .orderByDesc(RepairTicketEvent::getCreatedAt))
                .stream().map(this::toEventDto).toList();
        return new RepairTicketDetailDto(toDto(ticket), events);
    }

    @Transactional
    public RepairTicketDto create(Long operatorId, String deviceId, String title, String faultType,
                                  String assignee, String priority, String remark) {
        permissionService.requirePermission(operatorId, PERM_OPS_REPAIR_EDIT);
        if (deviceId == null || deviceId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备编号必填");
        }
        if (title == null || title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标题必填");
        }
        String trimmedDeviceId = deviceId.trim();
        if (deviceInfoMapper.selectById(trimmedDeviceId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在");
        }
        return runWithRepairDeviceLock(trimmedDeviceId,
                () -> doCreate(operatorId, trimmedDeviceId, title, faultType, assignee, priority, remark));
    }

    private RepairTicketDto doCreate(Long operatorId, String deviceId, String title, String faultType,
                                     String assignee, String priority, String remark) {
        Instant now = Instant.now();
        RepairTicket ticket = new RepairTicket();
        ticket.setDeviceId(deviceId);
        ticket.setTitle(title.trim());
        ticket.setFaultType(trimToNull(faultType));
        ticket.setStatus("OPEN");
        ticket.setAssignee(trimToNull(assignee));
        ticket.setPriority(normalizePriority(priority));
        ticket.setRemark(trimToNull(remark));
        ticket.setCreatedBy(operatorId);
        ticket.setCreatedAt(now);
        ticket.setUpdatedAt(now);
        ticketMapper.insert(ticket);
        appendEvent(ticket.getTicketId(), null, "OPEN", "CREATE", operatorId, remark);
        return toDto(ticket);
    }

    @Transactional
    public RepairTicketDto update(Long operatorId, long ticketId, String title, String faultType,
                                  String assignee, String priority, String remark) {
        permissionService.requirePermission(operatorId, PERM_OPS_REPAIR_EDIT);
        return runWithTicketLock(ticketId, () -> doUpdate(operatorId, ticketId, title, faultType, assignee, priority, remark));
    }

    private RepairTicketDto doUpdate(Long operatorId, long ticketId, String title, String faultType,
                                     String assignee, String priority, String remark) {
        RepairTicket ticket = requireTicketForUpdate(ticketId);
        if ("DONE".equals(ticket.getStatus()) || STATUS_CANCELLED.equals(ticket.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已关闭工单不可编辑");
        }
        if (title != null && !title.isBlank()) ticket.setTitle(title.trim());
        if (faultType != null) ticket.setFaultType(trimToNull(faultType));
        if (assignee != null) ticket.setAssignee(trimToNull(assignee));
        if (priority != null && !priority.isBlank()) ticket.setPriority(normalizePriority(priority));
        if (remark != null) ticket.setRemark(trimToNull(remark));
        ticket.setUpdatedAt(Instant.now());
        ticketMapper.updateById(ticket);
        appendEvent(ticketId, ticket.getStatus(), ticket.getStatus(), "UPDATE", operatorId, remark);
        return toDto(ticket);
    }

    /** 批量指派：只更新未关闭（OPEN / IN_PROGRESS）的工单。 */
    @Transactional
    public int batchAssign(Long operatorId, List<Long> ticketIds, String assignee) {
        permissionService.requirePermission(operatorId, PERM_OPS_REPAIR_EDIT);
        String name = trimToNull(assignee);
        if (name == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写指派人");
        }
        if (ticketIds == null || ticketIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择工单");
        }
        int count = 0;
        for (Long ticketId : ticketIds) {
            count += runWithTicketLock(ticketId, () -> doAssignOne(operatorId, ticketId, name) ? 1 : 0);
        }
        return count;
    }

    private boolean doAssignOne(Long operatorId, Long ticketId, String name) {
        RepairTicket ticket = requireTicketForUpdate(ticketId);
        if ("DONE".equals(ticket.getStatus()) || STATUS_CANCELLED.equals(ticket.getStatus())) {
            return false;
        }
        ticket.setAssignee(name);
        ticket.setUpdatedAt(Instant.now());
        ticketMapper.updateById(ticket);
        appendEvent(ticketId, ticket.getStatus(), ticket.getStatus(), "ASSIGN", operatorId, "批量指派给 " + name);
        return true;
    }

    @Transactional
    public RepairTicketDto transition(Long operatorId, long ticketId, String toStatus, String remark) {
        return self.transition(operatorId, ticketId, toStatus, remark, false);
    }

    @Transactional
    public RepairTicketDto transition(Long operatorId, long ticketId, String toStatus, String remark,
                                      boolean unlockDevice) {
        permissionService.requirePermission(operatorId, PERM_OPS_REPAIR_EDIT);
        return runWithTicketLock(ticketId, () -> doTransition(operatorId, ticketId, toStatus, remark, unlockDevice));
    }

    private RepairTicketDto doTransition(Long operatorId, long ticketId, String toStatus, String remark,
                                       boolean unlockDevice) {
        RepairTicket ticket = requireTicketForUpdate(ticketId);
        String target = toStatus == null ? "" : toStatus.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(target)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效状态");
        }
        String from = ticket.getStatus();
        Set<String> allowed = TRANSITIONS.getOrDefault(from, Set.of());
        if (!allowed.contains(target)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "不允许从 " + from + " 转到 " + target);
        }
        ticket.setStatus(target);
        ticket.setUpdatedAt(Instant.now());
        if ("DONE".equals(target) || STATUS_CANCELLED.equals(target)) {
            ticket.setClosedAt(Instant.now());
        }
        if (remark != null && !remark.isBlank()) {
            ticket.setRemark(remark.trim());
        }
        ticketMapper.updateById(ticket);
        appendEvent(ticketId, from, target, "TRANSITION", operatorId, remark);

        if ("DONE".equals(target) && unlockDevice) {
            unlockAfterRepair(operatorId, ticket);
        }
        return toDto(ticket);
    }

    private void unlockAfterRepair(Long operatorId, RepairTicket ticket) {
        var device = deviceInfoMapper.selectById(ticket.getDeviceId());
        if (device == null) {
            return;
        }
        if (device.salesLockedEnabled()) {
            salesLockService.applySalesLock(operatorId, device, false,
                    "repair-done#" + ticket.getTicketId(), true);
        }
        opsExceptionService.resolveSystem("DEVICE_FAULT", ticket.getDeviceId(),
                "维修工单 #" + ticket.getTicketId() + " 完成并解锁");
        opsExceptionService.resolveSystem("DEVICE_OFFLINE", ticket.getDeviceId(),
                "维修工单 #" + ticket.getTicketId() + " 完成");
    }

    private RepairTicket requireTicket(long ticketId) {
        RepairTicket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "工单不存在");
        }
        return ticket;
    }

    private RepairTicket requireTicketForUpdate(long ticketId) {
        return ticketMapper.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "工单不存在"));
    }

    static String ticketLockKey(long ticketId) {
        return "repair:ticket:" + ticketId;
    }

    static String repairDeviceLockKey(String deviceId) {
        return "repair:device:" + deviceId;
    }

    private <T> T runWithRepairDeviceLock(String deviceId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(repairDeviceLockKey(deviceId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该设备维修工单处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(repairDeviceLockKey(deviceId));
        }
    }

    private <T> T runWithTicketLock(long ticketId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(ticketLockKey(ticketId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "维修工单处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(ticketLockKey(ticketId));
        }
    }

    private void appendEvent(Long ticketId, String from, String to, String action, Long operatorId, String remark) {
        RepairTicketEvent event = new RepairTicketEvent();
        event.setTicketId(ticketId);
        event.setFromStatus(from);
        event.setToStatus(to);
        event.setAction(action);
        event.setOperatorId(operatorId);
        event.setRemark(trimToNull(remark));
        event.setCreatedAt(Instant.now());
        eventMapper.insert(event);
    }

    private RepairTicketDto toDto(RepairTicket t) {
        return new RepairTicketDto(
                t.getTicketId(), t.getDeviceId(), t.getTitle(), t.getFaultType(), t.getStatus(),
                t.getAssignee(), t.getPriority(), t.getRemark(), t.getCreatedBy(),
                t.getCreatedAt(), t.getUpdatedAt(), t.getClosedAt());
    }

    private RepairTicketEventDto toEventDto(RepairTicketEvent e) {
        return new RepairTicketEventDto(
                e.getEventId(), e.getTicketId(), e.getFromStatus(), e.getToStatus(),
                e.getAction(), e.getOperatorId(), e.getRemark(), e.getCreatedAt());
    }

    private static String normalizePriority(String priority) {
        if (priority == null || priority.isBlank()) return STATUS_NORMAL;
        String p = priority.trim().toUpperCase(Locale.ROOT);
        return Set.of("LOW", STATUS_NORMAL, "HIGH", "URGENT").contains(p) ? p : STATUS_NORMAL;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
