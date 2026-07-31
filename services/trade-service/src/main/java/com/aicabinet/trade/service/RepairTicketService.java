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

    private static final Set<String> STATUSES = Set.of("OPEN", "IN_PROGRESS", "DONE", "CANCELLED");
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "OPEN", Set.of("IN_PROGRESS", "CANCELLED"),
            "IN_PROGRESS", Set.of("DONE", "CANCELLED", "OPEN"),
            "DONE", Set.of(),
            "CANCELLED", Set.of()
    );

    private final RepairTicketMapper ticketMapper;
    private final RepairTicketEventMapper eventMapper;
    private final DeviceInfoMapper deviceInfoMapper;
    private final PermissionService permissionService;

    public RepairTicketService(RepairTicketMapper ticketMapper,
                               RepairTicketEventMapper eventMapper,
                               DeviceInfoMapper deviceInfoMapper,
                               PermissionService permissionService) {
        this.ticketMapper = ticketMapper;
        this.eventMapper = eventMapper;
        this.deviceInfoMapper = deviceInfoMapper;
        this.permissionService = permissionService;
    }

    @Transactional(readOnly = true)
    public PageResult<RepairTicketDto> list(Long operatorId, String status, String deviceId,
                                            String priority, int page, int size) {
        permissionService.requireAnyPermission(operatorId, "ops:repair:list", "ops:device:list");
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
        permissionService.requireAnyPermission(operatorId, "ops:repair:list", "ops:device:list", "ops:device:edit");
        int lim = Math.min(Math.max(limit, 1), 50);
        return ticketMapper.selectList(new LambdaQueryWrapper<RepairTicket>()
                        .eq(RepairTicket::getDeviceId, deviceId)
                        .orderByDesc(RepairTicket::getCreatedAt)
                        .last("LIMIT " + lim))
                .stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public RepairTicketDetailDto detail(Long operatorId, long ticketId) {
        permissionService.requireAnyPermission(operatorId, "ops:repair:list", "ops:device:list");
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
        permissionService.requirePermission(operatorId, "ops:repair:edit");
        if (deviceId == null || deviceId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备编号必填");
        }
        if (title == null || title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标题必填");
        }
        if (deviceInfoMapper.selectById(deviceId.trim()) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在");
        }
        Instant now = Instant.now();
        RepairTicket ticket = new RepairTicket();
        ticket.setDeviceId(deviceId.trim());
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
        permissionService.requirePermission(operatorId, "ops:repair:edit");
        RepairTicket ticket = requireTicket(ticketId);
        if ("DONE".equals(ticket.getStatus()) || "CANCELLED".equals(ticket.getStatus())) {
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

    @Transactional
    public RepairTicketDto transition(Long operatorId, long ticketId, String toStatus, String remark) {
        permissionService.requirePermission(operatorId, "ops:repair:edit");
        RepairTicket ticket = requireTicket(ticketId);
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
        if ("DONE".equals(target) || "CANCELLED".equals(target)) {
            ticket.setClosedAt(Instant.now());
        }
        if (remark != null && !remark.isBlank()) {
            ticket.setRemark(remark.trim());
        }
        ticketMapper.updateById(ticket);
        appendEvent(ticketId, from, target, "TRANSITION", operatorId, remark);
        return toDto(ticket);
    }

    private RepairTicket requireTicket(long ticketId) {
        RepairTicket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "工单不存在");
        }
        return ticket;
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
        if (priority == null || priority.isBlank()) return "NORMAL";
        String p = priority.trim().toUpperCase(Locale.ROOT);
        return Set.of("LOW", "NORMAL", "HIGH", "URGENT").contains(p) ? p : "NORMAL";
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
