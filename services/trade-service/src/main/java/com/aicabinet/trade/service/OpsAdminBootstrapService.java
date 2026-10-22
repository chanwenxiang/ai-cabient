package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.util.PhoneMask;
import com.aicabinet.trade.domain.OpsRole;
import com.aicabinet.trade.domain.OpsUserRole;
import com.aicabinet.trade.domain.UserAccount;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.OpsRoleMapper;
import com.aicabinet.trade.mapper.OpsUserRoleMapper;
import com.aicabinet.trade.mapper.UserAccountMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.support.PasswordPolicy;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.regex.Pattern;

/**
 * 首次部署引导超管：只在库中没有任何运营账号时创建（空库语义），
 * 已存在账号一律不碰——不改密码、不提升权限，重复启动幂等。
 */
@Service
public class OpsAdminBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(OpsAdminBootstrapService.class);

    private static final Pattern PHONE = Pattern.compile("^1\\d{10}$");
    private static final String ADMIN_ROLE_KEY = "admin";
    private static final String DEFAULT_NAME = "运营管理员";

    private final UserInfoMapper userInfoRepository;
    private final UserAccountMapper userAccountRepository;
    private final OpsRoleMapper roleRepository;
    private final OpsUserRoleMapper userRoleRepository;
    private final OperatorUserIdAllocator operatorUserIdAllocator;
    private final PasswordEncoder passwordEncoder;

    public OpsAdminBootstrapService(UserInfoMapper userInfoRepository,
                                    UserAccountMapper userAccountRepository,
                                    OpsRoleMapper roleRepository,
                                    OpsUserRoleMapper userRoleRepository,
                                    OperatorUserIdAllocator operatorUserIdAllocator,
                                    PasswordEncoder passwordEncoder) {
        this.userInfoRepository = userInfoRepository;
        this.userAccountRepository = userAccountRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.operatorUserIdAllocator = operatorUserIdAllocator;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * @return true=本次创建了初始超管；false=已有运营账号，跳过
     */
    @Transactional
    public boolean ensureInitialAdmin(String rawPhone, String password, String name) {
        String phone = rawPhone == null ? "" : rawPhone.replaceAll("\\s", "");
        if (!PHONE.matcher(phone).matches()) {
            throw new IllegalStateException("AICABINET_OPS_ADMIN_PHONE 必须是 11 位手机号");
        }
        try {
            PasswordPolicy.validate(password);
        } catch (ResponseStatusException e) {
            throw new IllegalStateException("AICABINET_OPS_ADMIN_PASSWORD 不符合密码策略：" + e.getReason(), e);
        }

        if (hasAnyOperator()) {
            log.info("初始运营超管跳过：库中已存在运营账号");
            return false;
        }
        if (userInfoRepository.findByPhoneNumber(phone).isPresent()) {
            throw new IllegalStateException(
                    "初始运营超管手机号已被非运营账号占用：" + PhoneMask.mask(phone));
        }
        OpsRole adminRole = roleRepository.findByRoleKey(ADMIN_ROLE_KEY)
                .orElseThrow(() -> new IllegalStateException("缺少 admin 角色种子，无法绑定超管"));

        long userId = operatorUserIdAllocator.nextId();
        UserInfo user = new UserInfo();
        user.setUserId(userId);
        user.setAccountType(CabinetConstants.ACCOUNT_TYPE_OPERATOR);
        user.setPhoneNumber(phone);
        user.setName(name == null || name.isBlank() ? DEFAULT_NAME : name.trim());
        user.setVerified(true);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setStatus("ACTIVE");
        userInfoRepository.insert(user);

        UserAccount account = new UserAccount();
        account.setUserId(userId);
        account.setBalanceCents(0);
        userAccountRepository.insert(account);

        userRoleRepository.insert(new OpsUserRole(userId, adminRole.getRoleId()));

        log.info("初始运营超管已创建：userId={} phone={} role={}",
                userId, PhoneMask.mask(phone), ADMIN_ROLE_KEY);
        return true;
    }

    private boolean hasAnyOperator() {
        Long count = userInfoRepository.selectCount(Wrappers.<UserInfo>lambdaQuery()
                .and(w -> w.eq(UserInfo::getAccountType, CabinetConstants.ACCOUNT_TYPE_OPERATOR)
                        .or(legacy -> legacy.isNull(UserInfo::getAccountType)
                                .ge(UserInfo::getUserId, CabinetConstants.OPERATOR_USER_ID_START))));
        return count != null && count > 0;
    }
}
