package com.aicabinet.trade.service;

import com.aicabinet.trade.config.WeChatMiniAppProperties;
import com.aicabinet.trade.mapper.MerchantNotifyLogMapper;
import com.aicabinet.trade.mapper.MerchantSubscribePrefMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.support.MerchantPortalGuard;
import com.aicabinet.trade.wechat.WeChatMiniAppClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantNotifyConcurrencyTest {

    @Mock private MerchantPortalGuard merchantPortalGuard;
    @Mock private PermissionService permissionService;
    @Mock private MerchantPortalService merchantPortalService;
    @Mock private MerchantFeaturePackService merchantFeaturePackService;
    @Mock private OpsExceptionService opsExceptionService;
    @Mock private UserInfoMapper userInfoRepository;
    @Mock private MerchantSubscribePrefMapper subscribePrefRepository;
    @Mock private MerchantNotifyLogMapper notifyLogRepository;
    @Mock private WeChatMiniAppClient weChatMiniAppClient;
    @Mock private WeChatMiniAppProperties weChatMiniAppProperties;
    @Mock private DistributedLockService distributedLockService;

    private MerchantNotifyService service;

    @BeforeEach
    void setUp() {
        service = new MerchantNotifyService(merchantPortalGuard, permissionService, merchantPortalService,
                merchantFeaturePackService, opsExceptionService, userInfoRepository, subscribePrefRepository,
                notifyLogRepository, weChatMiniAppClient, weChatMiniAppProperties, distributedLockService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    void maybeNotifyUser_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                MerchantNotifyService.merchantNotifyUserLockKey(800L), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.maybeNotifyUser(800L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void bindWxOpenId_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                AccountService.userAccountLockKey(801L), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.bindWxOpenId(801L, "wx-code"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }
}
