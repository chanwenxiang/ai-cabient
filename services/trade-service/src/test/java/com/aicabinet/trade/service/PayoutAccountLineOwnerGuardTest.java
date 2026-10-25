package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.LineManager;
import com.aicabinet.trade.domain.PayoutAccount;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.LineManagerMapper;
import com.aicabinet.trade.mapper.LineWithdrawRequestMapper;
import com.aicabinet.trade.mapper.MerchantWithdrawRequestMapper;
import com.aicabinet.trade.mapper.PayoutAccountMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.payout.PayoutChannel;
import com.aicabinet.trade.payout.PayoutChannelRegistry;
import com.aicabinet.trade.payout.PayoutFieldCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CB-066 持有人一致性守卫的回归钉（G2-13 发现「收款账户无入口+户名任意填」的守卫半）：
 * LINE_MANAGER 主体新增收款账户必须——绑定用户、完成身份核验（identity_verified_at）、
 * 对私户名 == 实名姓名（user.name，C 端/运营侧二要素通过后覆写）。MERCHANT 主体本守卫不拦（法人一致性待拍板另接）。
 */
@ExtendWith(MockitoExtension.class)
class PayoutAccountLineOwnerGuardTest {

    private static final long MANAGER_ID = 23L;
    private static final long USER_ID = 100000060L;

    @Mock private PayoutAccountMapper accountMapper;
    @Mock private PayoutFieldCipher cipher;
    @Mock private PayoutChannelRegistry channelRegistry;
    @Mock private MerchantWithdrawRequestMapper withdrawMapper;
    @Mock private LineWithdrawRequestMapper lineWithdrawMapper;
    @Mock private LineManagerMapper lineManagerMapper;
    @Mock private UserInfoMapper userInfoMapper;

    private PayoutAccountService service;

    @BeforeEach
    void setUp() {
        service = new PayoutAccountService(accountMapper, cipher, channelRegistry,
                withdrawMapper, lineWithdrawMapper, lineManagerMapper, userInfoMapper);
        PayoutChannel channel = org.mockito.Mockito.mock(PayoutChannel.class);
        lenient().when(channelRegistry.find(anyString())).thenReturn(Optional.of(channel));
        lenient().when(cipher.encrypt(anyString())).thenReturn("CIPHER");
        lenient().when(cipher.mask(anyString())).thenReturn("6222****0000");
        lenient().when(accountMapper.findActiveByOwner(anyString(), anyString())).thenReturn(List.of());
        lenient().when(accountMapper.insert(any(PayoutAccount.class))).thenAnswer(inv -> {
            PayoutAccount a = inv.getArgument(0);
            a.setAccountId(99L); // 生产由 MyBatis-Plus 回填主键；mock 需手动回填（switchDefault 要用）
            return 1;
        });
    }

    private void stubManager(Long userId, boolean identityVerified) {
        LineManager manager = new LineManager();
        manager.setManagerId(MANAGER_ID);
        manager.setUserId(userId);
        manager.setIdentityVerifiedAt(identityVerified ? java.time.Instant.now() : null);
        lenient().when(lineManagerMapper.selectById(MANAGER_ID)).thenReturn(manager);
    }

    private void stubUser(String realName, boolean verified) {
        UserInfo user = new UserInfo();
        user.setUserId(USER_ID);
        user.setName(realName);
        user.setVerified(verified);
        lenient().when(userInfoMapper.selectById(USER_ID)).thenReturn(user);
    }

    private ResponseStatusException createPersonal(String accountName) {
        return assertThrows(ResponseStatusException.class, () -> service.create(
                "LINE_MANAGER", String.valueOf(MANAGER_ID),
                "PAYEE_TYPE_PERSONAL", "BANK", accountName, "6222021234567890",
                null, null, null, true, 1L));
    }

    @Test
    void unboundUser_rejected403() {
        stubManager(null, true);
        ResponseStatusException ex = createPersonal("张三");
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertTrue(ex.getReason().contains("未绑定商户小程序用户"), ex.getReason());
    }

    @Test
    void notIdentityVerified_rejected403() {
        stubManager(USER_ID, false);
        stubUser("张三", true);
        ResponseStatusException ex = createPersonal("张三");
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertTrue(ex.getReason().contains("身份核验"), ex.getReason());
    }

    @Test
    void userNotVerified_rejected403() {
        stubManager(USER_ID, true);
        stubUser("张三", false);
        ResponseStatusException ex = createPersonal("张三");
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertTrue(ex.getReason().contains("尚未完成实名"), ex.getReason());
    }

    @Test
    void personalNameMismatch_rejected403() {
        stubManager(USER_ID, true);
        stubUser("张三", true);
        ResponseStatusException ex = createPersonal("李四");
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertTrue(ex.getReason().contains("户名须与实名姓名一致"), ex.getReason());
        verify(accountMapper, never()).insert(any(PayoutAccount.class));
    }

    @Test
    void personalNameMatch_passes() {
        stubManager(USER_ID, true);
        stubUser("张三", true);

        PayoutAccount account = service.create(
                "LINE_MANAGER", String.valueOf(MANAGER_ID),
                "PAYEE_TYPE_PERSONAL", "BANK", "张三", "6222021234567890",
                null, null, null, true, 1L);

        assertEquals("6222****0000", account.getAccountNoMask());
        verify(accountMapper).insert(any(PayoutAccount.class));
    }

    @Test
    void companyAccountOnLineManager_notNameChecked() {
        stubManager(USER_ID, true);
        stubUser("张三", true);

        PayoutAccount account = service.create(
                "LINE_MANAGER", String.valueOf(MANAGER_ID),
                "PAYEE_TYPE_COMPANY", "BANK", "某某服务商有限公司", "6222021234567890",
                "招商银行深圳分行", "深圳分行", "91440300MA5DA0000X", true, 1L);

        verify(accountMapper).insert(any(PayoutAccount.class));
    }

    @Test
    void chineseBankName_accepted_afterRegexFix() {
        // 2026-10-25 修 BANK_NAME_RE 四重转义（中文开户行恒 400——对公 create 路径首踩）
        stubManager(USER_ID, true);
        stubUser("张三", true);

        PayoutAccount account = service.create(
                "LINE_MANAGER", String.valueOf(MANAGER_ID),
                "PAYEE_TYPE_COMPANY", "BANK", "某某服务商有限公司", "6222021234567890",
                "中国工商银行杭州西湖支行", "杭州", "91440300MA5DA0000X", true, 1L);

        assertEquals("中国工商银行杭州西湖支行", account.getBankName());
    }

    @Test
    void merchantOwner_notGuarded() {
        // MERCHANT 主体的法人一致性待拍板另接——本守卫不拦（保持既有行为）
        lenient().when(accountMapper.findActiveByOwner(eq("MERCHANT"), eq("MCH-DEFAULT"))).thenReturn(List.of());

        PayoutAccount account = service.create(
                "MERCHANT", "MCH-DEFAULT",
                "PAYEE_TYPE_COMPANY", "BANK", "任意名称", "6222021234567890",
                "招商银行深圳分行", "深圳分行", "91440300MA5DA0000X", true, 1L);

        verify(lineManagerMapper, never()).selectById(any());
        verify(accountMapper).insert(any(PayoutAccount.class));
    }
}
