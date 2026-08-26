package com.aicabinet.trade.service;

import com.aicabinet.common.dto.ApprovalDefinitionDto;
import com.aicabinet.common.dto.ApprovalInboxDto;
import com.aicabinet.common.dto.ApprovalNodeDto;
import com.aicabinet.common.dto.ApprovalTaskDto;
import com.aicabinet.common.dto.CreateApprovalDefinitionRequest;
import com.aicabinet.common.dto.NotificationDto;
import com.aicabinet.common.dto.UpsertApprovalDefinitionRequest;
import com.aicabinet.trade.domain.ApprovalDefinition;
import com.aicabinet.trade.domain.ApprovalInstance;
import com.aicabinet.trade.domain.ApprovalNode;
import com.aicabinet.trade.domain.ApprovalTask;
import com.aicabinet.trade.mapper.ApprovalDefinitionMapper;
import com.aicabinet.trade.mapper.ApprovalInstanceMapper;
import com.aicabinet.trade.mapper.ApprovalNodeMapper;
import com.aicabinet.trade.mapper.ApprovalTaskMapper;
import com.aicabinet.trade.mapper.OpsPermissionMapper;
import com.aicabinet.trade.mapper.OpsUserDepartmentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class ApprovalWorkflowService {
    private static final String PERM_OPS_APPROVAL_CONFIG = "ops:approval:config";
    private static final String APPROVAL_DEF = "APPROVAL_DEF";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_SKIPPED = "SKIPPED";


    private static final Logger log = LoggerFactory.getLogger(ApprovalWorkflowService.class);
    private static final Set<String> ASSIGNEE_TYPES = Set.of("PERM", "ROLE", "DEPT", "USER");
    private static final Set<String> PASS_RULES = Set.of("ANY", "ALL");

    private final ApprovalDefinitionMapper definitionRepository;
    private final ApprovalNodeMapper nodeRepository;
    private final ApprovalInstanceMapper instanceRepository;
    private final ApprovalTaskMapper taskRepository;
    private final OpsPermissionMapper permissionRepository;
    private final OpsUserDepartmentMapper userDepartmentRepository;
    private final NotificationService notificationService;
    private final PermissionService permissionService;
    private final AdminAuditService auditService;
    /** 经 Spring 代理调用本类 @Transactional 方法，避免自调用失效。 */
    private final ApprovalWorkflowService self;

    public ApprovalWorkflowService(ApprovalDefinitionMapper definitionRepository,
                                     ApprovalNodeMapper nodeRepository,
                                     ApprovalInstanceMapper instanceRepository,
                                     ApprovalTaskMapper taskRepository,
                                     OpsPermissionMapper permissionRepository,
                                     OpsUserDepartmentMapper userDepartmentRepository,
                                     NotificationService notificationService,
                                     PermissionService permissionService,
                                     AdminAuditService auditService, @Lazy ApprovalWorkflowService self) {
        this.definitionRepository = definitionRepository;
        this.nodeRepository = nodeRepository;
        this.instanceRepository = instanceRepository;
        this.taskRepository = taskRepository;
        this.permissionRepository = permissionRepository;
        this.userDepartmentRepository = userDepartmentRepository;
        this.notificationService = notificationService;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.self = self;
    }

    @Transactional
    public void start(String bizType, String bizId, Long submitterId, String title) {
        if (bizType == null || bizType.isBlank() || bizId == null || bizId.isBlank()) {
            return;
        }
        String type = bizType.trim();
        String id = bizId.trim();
        ApprovalDefinition def = definitionRepository.findByBizType(type).orElse(null);
        if (def == null || Boolean.FALSE.equals(def.getEnabled())) {
            log.debug("approval definition missing/disabled bizType={}", bizType);
            return;
        }
        List<ApprovalNode> nodes = nodeRepository.findByDefIdOrderBySeqAsc(def.getDefId());
        if (nodes.isEmpty()) {
            log.warn("approval definition has no nodes bizType={}", bizType);
            return;
        }
        ApprovalNode first = nodes.get(0);
        String resolvedTitle = title != null && !title.isBlank() ? title.trim() : type + " " + id;

        ApprovalInstance existing = instanceRepository.findByBizTypeAndBizId(type, id).orElse(null);
        if (existing != null) {
            if (STATUS_PENDING.equals(existing.getStatus()) || STATUS_APPROVED.equals(existing.getStatus())) {
                return;
            }
            // REJECTED / CANCELLED → restart for resubmit
            ApprovalInstance locked = instanceRepository.findByIdForUpdate(existing.getInstanceId()).orElseThrow();
            skipOpenTasks(locked.getInstanceId());
            locked.setDefId(def.getDefId());
            locked.setTitle(resolvedTitle);
            locked.setStatus(STATUS_PENDING);
            locked.setSubmitterId(submitterId);
            locked.setCurrentNodeSeq(first.getSeq());
            locked.setRemark(null);
            locked.setFinishedAt(null);
            instanceRepository.save(locked);
            createTasksForNode(locked, first);
            return;
        }

        ApprovalInstance instance = new ApprovalInstance();
        instance.setDefId(def.getDefId());
        instance.setBizType(type);
        instance.setBizId(id);
        instance.setTitle(resolvedTitle);
        instance.setStatus(STATUS_PENDING);
        instance.setSubmitterId(submitterId);
        instance.setCurrentNodeSeq(first.getSeq());
        instance = instanceRepository.save(instance);

        createTasksForNode(instance, first);
    }

    @Transactional
    public void completeApproved(Long actorUserId, String bizType, String bizId, String remark) {
        ApprovalInstance pending = instanceRepository.findPendingByBizTypeAndBizId(bizType, bizId)
                .orElse(null);
        if (pending == null) {
            return;
        }
        ApprovalInstance instance = instanceRepository.findByIdForUpdate(pending.getInstanceId()).orElseThrow();
        if (!STATUS_PENDING.equals(instance.getStatus())) {
            return;
        }
        List<ApprovalNode> nodes = nodeRepository.findByDefIdOrderBySeqAsc(instance.getDefId());
        int currentSeq = instance.getCurrentNodeSeq();
        ApprovalNode current = nodes.stream()
                .filter(n -> n.getSeq().equals(currentSeq))
                .findFirst()
                .orElse(nodes.get(nodes.size() - 1));

        List<ApprovalTask> currentTasks = taskRepository.findByInstanceIdAndNodeSeq(
                instance.getInstanceId(), current.getSeq());
        boolean actorHasPending = currentTasks.stream()
                .anyMatch(t -> STATUS_PENDING.equals(t.getStatus())
                        && actorUserId != null
                        && actorUserId.equals(t.getAssigneeUserId()));
        if (!actorHasPending) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "您不是当前审批节点的处理人");
        }

        Instant now = Instant.now();
        for (ApprovalTask task : currentTasks) {
            if (STATUS_PENDING.equals(task.getStatus())) {
                if (actorUserId.equals(task.getAssigneeUserId())) {
                    task.setStatus(STATUS_APPROVED);
                    task.setActedAt(now);
                    task.setRemark(trim(remark));
                } else {
                    task.setStatus(STATUS_SKIPPED);
                    task.setActedAt(now);
                }
                taskRepository.save(task);
            }
        }

        int nextSeq = instance.getCurrentNodeSeq() + 1;
        ApprovalNode next = nodes.stream().filter(n -> n.getSeq() == nextSeq).findFirst().orElse(null);
        if (next != null) {
            instance.setCurrentNodeSeq(nextSeq);
            instanceRepository.save(instance);
            createTasksForNode(instance, next);
            return;
        }

        instance.setStatus(STATUS_APPROVED);
        instance.setRemark(trim(remark));
        instance.setFinishedAt(now);
        instanceRepository.save(instance);
    }

    @Transactional
    public void completeRejected(Long actorUserId, String bizType, String bizId, String remark) {
        ApprovalInstance pending = instanceRepository.findPendingByBizTypeAndBizId(bizType, bizId)
                .orElse(null);
        if (pending == null) {
            return;
        }
        ApprovalInstance instance = instanceRepository.findByIdForUpdate(pending.getInstanceId()).orElseThrow();
        if (!STATUS_PENDING.equals(instance.getStatus())) {
            return;
        }
        List<ApprovalTask> currentTasks = taskRepository.findByInstanceIdAndNodeSeq(
                instance.getInstanceId(), instance.getCurrentNodeSeq());
        boolean actorHasPending = currentTasks.stream()
                .anyMatch(t -> STATUS_PENDING.equals(t.getStatus())
                        && actorUserId != null
                        && actorUserId.equals(t.getAssigneeUserId()));
        if (!actorHasPending) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "您不是当前审批节点的处理人");
        }
        Instant now = Instant.now();
        for (ApprovalTask task : currentTasks) {
            if (STATUS_PENDING.equals(task.getStatus())) {
                if (actorUserId.equals(task.getAssigneeUserId())) {
                    task.setStatus("REJECTED");
                    task.setActedAt(now);
                    task.setRemark(trim(remark));
                } else {
                    task.setStatus(STATUS_SKIPPED);
                    task.setActedAt(now);
                }
                taskRepository.save(task);
            }
        }
        instance.setStatus("REJECTED");
        instance.setRemark(trim(remark));
        instance.setFinishedAt(now);
        instanceRepository.save(instance);
    }

    @Transactional(readOnly = true)
    public ApprovalInboxDto inbox(Long userId, int limit) {
        List<ApprovalTaskDto> tasks = self.listPendingTasks(userId, limit);
        long pendingCount = taskRepository.countPendingByAssigneeUserId(userId);
        List<NotificationDto> messages = notificationService.opsNotifications(userId, limit);
        long unreadMessages = notificationService.opsUnreadCount(userId);
        return new ApprovalInboxDto(pendingCount, unreadMessages, tasks, messages);
    }

    @Transactional(readOnly = true)
    public List<ApprovalTaskDto> listPendingTasks(Long userId, int limit) {
        return taskRepository.findPendingByAssigneeUserId(userId, limit).stream()
                .map(this::toTaskDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public long countPendingTasks(Long userId) {
        return taskRepository.countPendingByAssigneeUserId(userId);
    }

    @Transactional(readOnly = true)
    public Long findAnyPendingAssignee(String bizType, String bizId) {
        ApprovalInstance instance = instanceRepository.findPendingByBizTypeAndBizId(bizType, bizId)
                .orElse(null);
        if (instance == null) {
            return null;
        }
        return taskRepository.findByInstanceIdAndNodeSeq(instance.getInstanceId(), instance.getCurrentNodeSeq())
                .stream()
                .filter(t -> STATUS_PENDING.equals(t.getStatus()))
                .map(ApprovalTask::getAssigneeUserId)
                .findFirst()
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public java.util.Optional<String> instanceStatus(String bizType, String bizId) {
        return instanceRepository.findByBizTypeAndBizId(bizType, bizId)
                .map(ApprovalInstance::getStatus);
    }

    @Transactional(readOnly = true)
    public boolean isInstanceApproved(String bizType, String bizId) {
        return self.instanceStatus(bizType, bizId).map(STATUS_APPROVED::equals).orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean isDefinitionEnabled(String bizType) {
        if (bizType == null || bizType.isBlank()) {
            return false;
        }
        return definitionRepository.findByBizType(bizType.trim())
                .map(d -> !Boolean.FALSE.equals(d.getEnabled()))
                .orElse(false);
    }

    @Transactional
    public void markTaskRead(Long userId, Long taskId) {
        ApprovalTask task = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "待办不存在"));
        if (!userId.equals(task.getAssigneeUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作该待办");
        }
        if (task.getReadAt() == null) {
            task.setReadAt(Instant.now());
            taskRepository.save(task);
        }
    }

    @Transactional(readOnly = true)
    public List<ApprovalDefinitionDto> listDefinitions(Long operatorId) {
        permissionService.requirePermission(operatorId, PERM_OPS_APPROVAL_CONFIG);
        return definitionRepository.findAllOrderByBizType().stream()
                .map(this::toDefinitionDto)
                .toList();
    }

    @Transactional
    public ApprovalDefinitionDto createDefinition(Long operatorId, CreateApprovalDefinitionRequest req) {
        permissionService.requirePermission(operatorId, PERM_OPS_APPROVAL_CONFIG);
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        String bizType = req.bizType() == null ? "" : req.bizType().trim().toUpperCase(Locale.ROOT);
        if (bizType.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "业务类型不能为空");
        }
        if (definitionRepository.findByBizType(bizType).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "业务类型已存在审批流: " + bizType);
        }
        ApprovalDefinition def = new ApprovalDefinition();
        def.setBizType(bizType);
        def.setDefName(req.defName().trim());
        def.setEnabled(req.enabled() == null || Boolean.TRUE.equals(req.enabled()));
        def.setRemark(trim(req.remark()));
        def.setCreatedAt(Instant.now());
        definitionRepository.insert(def);

        List<ApprovalNodeDto> nodes = req.nodes();
        if (nodes == null || nodes.isEmpty()) {
            nodes = List.of(new ApprovalNodeDto(null, 1, "运营审核", "PERM", "ops:approval:list", "ANY"));
        }
        replaceNodes(def.getDefId(), nodes);
        auditService.record(operatorId, "APPROVAL_DEF_CREATE", APPROVAL_DEF,
                String.valueOf(def.getDefId()), bizType);
        return toDefinitionDto(definitionRepository.selectById(def.getDefId()));
    }

    @Transactional
    public ApprovalDefinitionDto updateDefinition(Long operatorId, Long defId,
                                                    UpsertApprovalDefinitionRequest req) {
        permissionService.requirePermission(operatorId, PERM_OPS_APPROVAL_CONFIG);
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        ApprovalDefinition def = definitionRepository.selectById(defId);
        if (def == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "审批定义不存在");
        }
        if (req.defName() != null && !req.defName().isBlank()) {
            def.setDefName(req.defName().trim());
        }
        if (req.enabled() != null) {
            def.setEnabled(req.enabled());
        }
        if (req.remark() != null) {
            def.setRemark(trim(req.remark()));
        }
        definitionRepository.updateById(def);

        if (req.nodes() != null) {
            replaceNodes(defId, req.nodes());
        }
        auditService.record(operatorId, "APPROVAL_DEF_UPDATE", APPROVAL_DEF,
                String.valueOf(defId), def.getBizType());
        return toDefinitionDto(definitionRepository.selectById(defId));
    }

    @Transactional
    public void deleteDefinition(Long operatorId, Long defId) {
        permissionService.requirePermission(operatorId, PERM_OPS_APPROVAL_CONFIG);
        ApprovalDefinition def = definitionRepository.selectById(defId);
        if (def == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "审批定义不存在");
        }
        if (instanceRepository.countByDefId(defId) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "已有审批实例，不可删除");
        }
        nodeRepository.deleteByDefId(defId);
        definitionRepository.deleteById(defId);
        auditService.record(operatorId, "APPROVAL_DEF_DELETE", APPROVAL_DEF,
                String.valueOf(defId), def.getBizType());
    }

    private void replaceNodes(Long defId, List<ApprovalNodeDto> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "至少配置一个审批节点");
        }
        List<ApprovalNodeDto> sorted = new ArrayList<>(nodes);
        sorted.sort(Comparator.comparing(n -> n.seq() == null ? Integer.MAX_VALUE : n.seq()));
        nodeRepository.deleteByDefId(defId);
        int autoSeq = 1;
        for (ApprovalNodeDto n : sorted) {
            if (n == null || n.nodeName() == null || n.nodeName().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "节点名称不能为空");
            }
            String assigneeType = n.assigneeType() == null ? "" : n.assigneeType().trim().toUpperCase(Locale.ROOT);
            if (!ASSIGNEE_TYPES.contains(assigneeType)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "assigneeType 仅支持 PERM/ROLE/DEPT/USER");
            }
            String assigneeValue = n.assigneeValue() == null ? "" : n.assigneeValue().trim();
            if (assigneeValue.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "审批人取值不能为空");
            }
            String passRule = n.passRule() == null || n.passRule().isBlank()
                    ? "ANY" : n.passRule().trim().toUpperCase(Locale.ROOT);
            if (!PASS_RULES.contains(passRule)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "passRule 仅支持 ANY/ALL");
            }
            ApprovalNode node = new ApprovalNode();
            node.setDefId(defId);
            node.setSeq(n.seq() == null ? autoSeq : n.seq());
            node.setNodeName(n.nodeName().trim());
            node.setAssigneeType(assigneeType);
            node.setAssigneeValue("DEPT".equals(assigneeType) || "ROLE".equals(assigneeType) || "PERM".equals(assigneeType)
                    ? assigneeValue.toUpperCase(Locale.ROOT)
                    : assigneeValue);
            node.setPassRule(passRule);
            nodeRepository.insert(node);
            autoSeq = Math.max(autoSeq, node.getSeq()) + 1;
        }
    }

    private ApprovalDefinitionDto toDefinitionDto(ApprovalDefinition def) {
        List<ApprovalNodeDto> nodes = nodeRepository.findByDefIdOrderBySeqAsc(def.getDefId()).stream()
                .map(n -> new ApprovalNodeDto(
                        n.getNodeId(),
                        n.getSeq(),
                        n.getNodeName(),
                        n.getAssigneeType(),
                        n.getAssigneeValue(),
                        n.getPassRule()))
                .toList();
        return new ApprovalDefinitionDto(
                def.getDefId(),
                def.getBizType(),
                def.getDefName(),
                !Boolean.FALSE.equals(def.getEnabled()),
                def.getRemark(),
                nodes);
    }

    private void createTasksForNode(ApprovalInstance instance, ApprovalNode node) {
        Set<Long> assignees = resolveAssignees(node);
        if (assignees.isEmpty()) {
            log.warn("approval node has no assignees bizType={} node={}", instance.getBizType(), node.getNodeName());
            return;
        }
        String body = "节点「" + node.getNodeName() + "」待您处理";
        for (Long assigneeId : assignees) {
            ApprovalTask task = new ApprovalTask();
            task.setInstanceId(instance.getInstanceId());
            task.setNodeSeq(node.getSeq());
            task.setNodeName(node.getNodeName());
            task.setAssigneeUserId(assigneeId);
            task.setStatus(STATUS_PENDING);
            taskRepository.save(task);
            notificationService.notifyOpsInApp(
                    assigneeId,
                    "待审批：" + instance.getTitle(),
                    body,
                    instance.getBizType(),
                    instance.getBizId());
        }
    }

    private Set<Long> resolveAssignees(ApprovalNode node) {
        Set<Long> ids = new LinkedHashSet<>();
        String type = node.getAssigneeType() != null ? node.getAssigneeType().trim().toUpperCase(Locale.ROOT) : "";
        String value = node.getAssigneeValue() != null ? node.getAssigneeValue().trim() : "";
        if (value.isBlank()) {
            return ids;
        }
        if ("PERM".equals(type)) {
            ids.addAll(permissionRepository._findUserIdsByPermCode(value));
        } else if ("ROLE".equals(type)) {
            ids.addAll(permissionRepository._findUserIdsByRoleKey(value));
        } else if ("DEPT".equals(type)) {
            ids.addAll(userDepartmentRepository.findUserIdsByDeptKey(value.toUpperCase(Locale.ROOT)));
        } else if ("USER".equals(type)) {
            try {
                ids.add(Long.parseLong(value));
            } catch (NumberFormatException ignored) {
                // ignore invalid user id
            }
        }
        return ids;
    }

    private void skipOpenTasks(Long instanceId) {
        List<ApprovalTask> tasks = taskRepository.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<ApprovalTask>lambdaQuery()
                        .eq(ApprovalTask::getInstanceId, instanceId)
                        .eq(ApprovalTask::getStatus, STATUS_PENDING));
        Instant now = Instant.now();
        for (ApprovalTask task : tasks) {
            task.setStatus(STATUS_SKIPPED);
            task.setActedAt(now);
            taskRepository.save(task);
        }
    }

    private ApprovalTaskDto toTaskDto(ApprovalTask task) {
        ApprovalInstance instance = instanceRepository.findById(task.getInstanceId()).orElse(null);
        String bizType = instance != null ? instance.getBizType() : "";
        String bizId = instance != null ? instance.getBizId() : "";
        String title = instance != null ? instance.getTitle() : "";
        return new ApprovalTaskDto(
                task.getTaskId(),
                task.getInstanceId(),
                bizType,
                bizId,
                title,
                task.getNodeName(),
                task.getNodeSeq(),
                task.getStatus(),
                actionPath(bizType, bizId),
                task.getCreatedAt(),
                task.getReadAt());
    }

    static String actionPath(String bizType, String bizId) {
        if (bizType == null) {
            return "/approvals";
        }
        return switch (bizType) {
            case "MERCHANT_REPLEN_REQUEST" -> "/replenishment?tab=requests";
            case "PURCHASE_ORDER" -> "/warehouse?tab=purchase";
            case "MERCHANT_WITHDRAW" -> "/merchant-withdraw";
            case "LINE_WITHDRAW" -> "/line-managers?tab=withdraws";
            case "BALANCE_REFUND" -> "/balance-refunds";
            case "MERCHANT_WALLET_ADJUST" -> "/merchant-withdraw";
            case "MERCHANT_ONBOARD" -> "/merchant-onboarding";
            default -> "/approvals";
        };
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
