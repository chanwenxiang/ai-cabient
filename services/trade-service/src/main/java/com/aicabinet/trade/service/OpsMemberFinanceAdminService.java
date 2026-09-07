package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.dto.AdjustBalanceRequest;
import com.aicabinet.common.dto.AdminUserDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.RechargeOrderDto;
import com.aicabinet.common.dto.VerifyUserRequest;
import com.aicabinet.trade.domain.Member;
import com.aicabinet.trade.domain.RechargeOrder;
import com.aicabinet.trade.domain.UserAccount;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.MemberMapper;
import com.aicabinet.trade.mapper.RechargeOrderMapper;
import com.aicabinet.trade.mapper.UserAccountMapper;
import com.aicabinet.trade.mapper.UserBlacklistMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.support.ApiMessages;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 运营会员/余额/充值管理（原 AdminDashboardService member finance 簇）。
 * Pass 3F：从神类拆出；消费者余额 ≠ 商户钱包（G4）。
 */
@Service
public class OpsMemberFinanceAdminService {

    private static final String CREATEDAT = "createdAt";

    private final PermissionService permissionService;
    private final UserInfoMapper userInfoRepository;
    private final UserAccountMapper userAccountRepository;
    private final MemberMapper memberRepository;
    private final UserBlacklistMapper blacklistRepository;
    private final BalanceLedgerService balanceLedgerService;
    private final AdminAuditService auditService;
    private final PaymentService paymentService;
    private final RechargeOrderMapper rechargeOrderRepository;
    private final DistributedLockService distributedLockService;

    public OpsMemberFinanceAdminService(PermissionService permissionService,
                                        UserInfoMapper userInfoRepository,
                                        UserAccountMapper userAccountRepository,
                                        MemberMapper memberRepository,
                                        UserBlacklistMapper blacklistRepository,
                                        BalanceLedgerService balanceLedgerService,
                                        AdminAuditService auditService,
                                        PaymentService paymentService,
                                        RechargeOrderMapper rechargeOrderRepository,
                                        DistributedLockService distributedLockService) {
        this.permissionService = permissionService;
        this.userInfoRepository = userInfoRepository;
        this.userAccountRepository = userAccountRepository;
        this.memberRepository = memberRepository;
        this.blacklistRepository = blacklistRepository;
        this.balanceLedgerService = balanceLedgerService;
        this.auditService = auditService;
        this.paymentService = paymentService;
        this.rechargeOrderRepository = rechargeOrderRepository;
        this.distributedLockService = distributedLockService;
    }

    public PageResult<AdminUserDto> listUsers(Long operatorId, int page, int size, Long userId,
                                              String phone, String name, String role, Boolean verified) {
        permissionService.requirePermission(operatorId, "ops:user:list");
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Long minUserId = null;
        Long maxUserId = null;
        if (role != null && !role.isBlank()) {
            if ("OPERATOR".equalsIgnoreCase(role.trim())) {
                minUserId = CabinetConstants.OPERATOR_USER_ID_START;
            } else if ("CONSUMER".equalsIgnoreCase(role.trim())) {
                maxUserId = CabinetConstants.OPERATOR_USER_ID_START - 1;
            }
        }
        Page<UserInfo> result = userInfoRepository.searchForAdmin(
                userId,
                trimToNull(phone),
                trimToNull(name),
                verified,
                minUserId,
                maxUserId,
                pageable);
        List<Long> userIds = result.getContent().stream().map(UserInfo::getUserId).toList();
        Map<Long, Member> memberByUser = memberRepository.findByUserIds(userIds).stream()
                .collect(Collectors.toMap(Member::getUserId, m -> m, (a, b) -> a));
        Set<Long> blacklistedUsers = blacklistRepository.findActiveUserIds(userIds);
        Map<Long, Integer> balanceByUser = userAccountRepository.findByUserIds(userIds).stream()
                .collect(Collectors.toMap(UserAccount::getUserId, UserAccount::getBalanceCents, (a, b) -> a));
        return new PageResult<>(
                result.getContent().stream()
                        .map(u -> toUserDto(u,
                                balanceByUser.getOrDefault(u.getUserId(), 0),
                                memberByUser.get(u.getUserId()),
                                blacklistedUsers.contains(u.getUserId())))
                        .toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements()
        );
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Transactional
    public AdminUserDto adjustBalance(Long operatorId, Long userId, AdjustBalanceRequest request) {
        permissionService.requirePermission(operatorId, "ops:user:balance");
        if (userId >= CabinetConstants.OPERATOR_USER_ID_START) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.CANNOT_ADJUST_OPERATOR_BALANCE);
        }
        return runWithUserBalanceLock(userId, () -> {
            UserInfo user = userInfoRepository.findById(userId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.USER_NOT_FOUND));
            var ledger = balanceLedgerService.change(userId, request.deltaCents(), "ADMIN_ADJUST",
                    "ADMIN-" + userId, "ADMIN:" + request.idempotencyKey().trim(), request.reason());
            auditService.appendLog(operatorId, "BALANCE_ADJUST", "USER", String.valueOf(userId),
                    "delta=" + request.deltaCents() + " balance=" + ledger.getBalanceAfterCents()
                            + " reason=" + request.reason().trim());
            return toUserDto(user);
        });
    }

    public static String userBalanceLockKey(long userId) {
        return "user:balance:" + userId;
    }

    private <T> T runWithUserBalanceLock(long userId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(userBalanceLockKey(userId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "余额处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(userBalanceLockKey(userId));
        }
    }

    @Transactional
    public AdminUserDto setUserVerified(Long operatorId, Long userId, VerifyUserRequest request) {
        permissionService.requirePermission(operatorId, "ops:user:verify");
        if (userId >= CabinetConstants.OPERATOR_USER_ID_START) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.INVALID_REQUEST);
        }
        UserInfo user = userInfoRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.USER_NOT_FOUND));
        user.setVerified(request.verified());
        if (request.realName() != null && !request.realName().isBlank()) {
            user.setName(request.realName().trim());
        }
        userInfoRepository.save(user);
        auditService.appendLog(operatorId, request.verified() ? "USER_VERIFY" : "USER_UNVERIFY", "USER",
                String.valueOf(userId), "verified=" + request.verified());
        return toUserDto(user);
    }

    @Transactional(readOnly = true)
    public PageResult<RechargeOrderDto> listRecharges(Long operatorId, int page, int size,
                                                      String status, Long userId) {
        permissionService.requirePermission(operatorId, "ops:recharge:list");
        Pageable pageable = PageRequest.of(page, Math.min(size, 100),
                Sort.by(Sort.Direction.DESC, CREATEDAT));
        String st = (status == null || status.isBlank()) ? null : status.trim();
        Page<RechargeOrder> result = rechargeOrderRepository.search(st, userId, pageable);
        return new PageResult<>(
                result.getContent().stream().map(this::toRechargeDto).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements()
        );
    }

    @Transactional
    public RechargeOrderDto refundRecharge(Long operatorId, String orderId, String reason) {
        permissionService.requirePermission(operatorId, "ops:recharge:edit");
        RechargeOrderDto result = paymentService.refundRecharge(orderId, reason);
        auditService.appendLog(operatorId, "RECHARGE_REFUND", "RECHARGE", orderId,
                "userId=" + result.userId() + " amount=" + result.amountCents());
        return result;
    }

    private RechargeOrderDto toRechargeDto(RechargeOrder order) {
        return new RechargeOrderDto(
                order.getOrderId(),
                order.getUserId(),
                order.getAmountCents(),
                order.getChannel(),
                order.getStatus(),
                order.getWxPrepayId(),
                order.getWxTransactionId(),
                order.getAlipayTradeNo(),
                order.getCreatedAt(),
                order.getPaidAt(),
                order.getRefundedAt()
        );
    }

    private AdminUserDto toUserDto(UserInfo u) {
        int balance = userAccountRepository.findById(u.getUserId())
                .map(a -> a.getBalanceCents()).orElse(0);
        Member member = memberRepository.findByUserId(u.getUserId()).orElse(null);
        boolean blacklisted = blacklistRepository.findActiveByUserId(u.getUserId()).isPresent();
        return toUserDto(u, balance, member, blacklisted);
    }

    private AdminUserDto toUserDto(UserInfo u, int balance, Member member, boolean blacklisted) {
        String role = u.getUserId() >= CabinetConstants.OPERATOR_USER_ID_START ? "OPERATOR" : "CONSUMER";
        return new AdminUserDto(
                u.getUserId(), u.getPhoneNumber(), resolveUserDisplayName(u), u.isVerified(),
                balance, role, u.getCreatedAt(),
                member != null ? member.getMemberLevel() : "NORMAL",
                member != null && member.getAvailablePoints() != null ? member.getAvailablePoints() : 0,
                blacklisted
        );
    }

    /** 列表展示名：优先实名/昵称，空则留 null 由前端显示「暂无」。 */
    private static String resolveUserDisplayName(UserInfo u) {
        if (u.getName() != null && !u.getName().isBlank()) {
            return u.getName().trim();
        }
        return null;
    }
}
