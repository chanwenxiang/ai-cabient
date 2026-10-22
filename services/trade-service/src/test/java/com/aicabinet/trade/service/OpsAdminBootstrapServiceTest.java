package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.trade.config.OpsAdminBootstrapProperties;
import com.aicabinet.trade.domain.OpsRole;
import com.aicabinet.trade.domain.OpsUserRole;
import com.aicabinet.trade.domain.UserAccount;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.OpsRoleMapper;
import com.aicabinet.trade.mapper.OpsUserRoleMapper;
import com.aicabinet.trade.mapper.UserAccountMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpsAdminBootstrapServiceTest {

    private static final String PHONE = "13800138000";
    private static final String PASSWORD = "Bootstrap2026..";

    @Mock private UserInfoMapper userInfoRepository;
    @Mock private UserAccountMapper userAccountRepository;
    @Mock private OpsRoleMapper roleRepository;
    @Mock private OpsUserRoleMapper userRoleRepository;
    @Mock private OperatorUserIdAllocator operatorUserIdAllocator;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private OpsAdminBootstrapService service;

    @BeforeEach
    void setUp() {
        service = new OpsAdminBootstrapService(userInfoRepository, userAccountRepository,
                roleRepository, userRoleRepository, operatorUserIdAllocator, passwordEncoder);
    }

    @Test
    void createsAdminWithRoleAndAccountWhenNoOperatorExists() {
        OpsRole admin = new OpsRole();
        admin.setRoleId(1L);
        admin.setRoleKey("admin");
        when(userInfoRepository.selectCount(any())).thenReturn(0L);
        when(userInfoRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.empty());
        when(roleRepository.findByRoleKey("admin")).thenReturn(Optional.of(admin));
        when(operatorUserIdAllocator.nextId()).thenReturn(100_000_001L);

        assertTrue(service.ensureInitialAdmin(PHONE, PASSWORD, "cwx"));

        ArgumentCaptor<UserInfo> userCaptor = ArgumentCaptor.forClass(UserInfo.class);
        verify(userInfoRepository).insert(userCaptor.capture());
        UserInfo created = userCaptor.getValue();
        assertEquals(100_000_001L, created.getUserId());
        assertEquals(PHONE, created.getPhoneNumber());
        assertEquals("cwx", created.getName());
        assertEquals(CabinetConstants.ACCOUNT_TYPE_OPERATOR, created.getAccountType());
        assertEquals("ACTIVE", created.getStatus());
        assertTrue(created.isVerified());
        assertTrue(passwordEncoder.matches(PASSWORD, created.getPasswordHash()));

        ArgumentCaptor<UserAccount> accountCaptor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountRepository).insert(accountCaptor.capture());
        assertEquals(100_000_001L, accountCaptor.getValue().getUserId());
        assertEquals(0, accountCaptor.getValue().getBalanceCents());

        ArgumentCaptor<OpsUserRole> roleCaptor = ArgumentCaptor.forClass(OpsUserRole.class);
        verify(userRoleRepository).insert(roleCaptor.capture());
        assertEquals(100_000_001L, roleCaptor.getValue().getUserId());
        assertEquals(1L, roleCaptor.getValue().getRoleId());
    }

    @Test
    void usesDefaultNameWhenBlank() {
        OpsRole admin = new OpsRole();
        admin.setRoleId(1L);
        when(userInfoRepository.selectCount(any())).thenReturn(0L);
        when(userInfoRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.empty());
        when(roleRepository.findByRoleKey("admin")).thenReturn(Optional.of(admin));
        when(operatorUserIdAllocator.nextId()).thenReturn(100_000_002L);

        service.ensureInitialAdmin(PHONE, PASSWORD, "  ");

        ArgumentCaptor<UserInfo> userCaptor = ArgumentCaptor.forClass(UserInfo.class);
        verify(userInfoRepository).insert(userCaptor.capture());
        assertEquals("运营管理员", userCaptor.getValue().getName());
    }

    @Test
    void skipsWhenAnyOperatorAlreadyExists() {
        when(userInfoRepository.selectCount(any())).thenReturn(1L);

        assertFalse(service.ensureInitialAdmin(PHONE, PASSWORD, "cwx"));

        verify(userInfoRepository, never()).insert(any());
        verifyNoInteractions(userAccountRepository, userRoleRepository, operatorUserIdAllocator);
    }

    @Test
    void rejectsPhoneOccupiedByConsumerAccount() {
        when(userInfoRepository.selectCount(any())).thenReturn(0L);
        when(userInfoRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.of(new UserInfo()));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.ensureInitialAdmin(PHONE, PASSWORD, "cwx"));

        assertTrue(error.getMessage().contains("已被非运营账号占用"));
        verify(userInfoRepository, never()).insert(any());
    }

    @Test
    void rejectsWeakPasswordBeforeTouchingDatabase() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.ensureInitialAdmin(PHONE, "123456", "cwx"));

        assertTrue(error.getMessage().contains("密码策略"));
        verifyNoInteractions(userInfoRepository, userAccountRepository, roleRepository,
                userRoleRepository, operatorUserIdAllocator);
    }

    @Test
    void rejectsInvalidPhoneBeforeTouchingDatabase() {
        assertThrows(IllegalStateException.class,
                () -> service.ensureInitialAdmin("12345", PASSWORD, "cwx"));

        verifyNoInteractions(userInfoRepository, userAccountRepository, roleRepository,
                userRoleRepository, operatorUserIdAllocator);
    }

    @Test
    void propertiesRequireBothPhoneAndPassword() {
        assertFalse(new OpsAdminBootstrapProperties("", PASSWORD, "cwx").configured());
        assertFalse(new OpsAdminBootstrapProperties(PHONE, " ", "cwx").configured());
        assertFalse(new OpsAdminBootstrapProperties(null, null, "cwx").configured());
        assertTrue(new OpsAdminBootstrapProperties(PHONE, PASSWORD, "cwx").configured());
    }
}
