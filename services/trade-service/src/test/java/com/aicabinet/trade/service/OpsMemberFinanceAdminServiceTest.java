package com.aicabinet.trade.service;

import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.RechargeOrderDto;
import com.aicabinet.trade.domain.RechargeOrder;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.MemberMapper;
import com.aicabinet.trade.mapper.RechargeOrderMapper;
import com.aicabinet.trade.mapper.UserAccountMapper;
import com.aicabinet.trade.mapper.UserBlacklistMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpsMemberFinanceAdminServiceTest {

    @Mock PermissionService permissionService;
    @Mock UserInfoMapper userInfoRepository;
    @Mock UserAccountMapper userAccountRepository;
    @Mock MemberMapper memberRepository;
    @Mock UserBlacklistMapper blacklistRepository;
    @Mock BalanceLedgerService balanceLedgerService;
    @Mock AdminAuditService auditService;
    @Mock PaymentService paymentService;
    @Mock RechargeOrderMapper rechargeOrderRepository;
    @Mock DistributedLockService distributedLockService;

    private OpsMemberFinanceAdminService service;

    @BeforeEach
    void setUp() {
        service = new OpsMemberFinanceAdminService(
                permissionService, userInfoRepository, userAccountRepository,
                memberRepository, blacklistRepository, balanceLedgerService, auditService,
                paymentService, rechargeOrderRepository, distributedLockService);
    }

    @Test
    void listUsers_masksPhoneNumber() {
        UserInfo user = new UserInfo();
        user.setUserId(10001L);
        user.setPhoneNumber("13900000001");
        user.setVerified(true);
        user.setCreatedAt(Instant.parse("2026-09-01T00:00:00Z"));
        Page<UserInfo> page = new PageImpl<>(List.of(user));
        when(userInfoRepository.searchForAdmin(
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(page);
        when(memberRepository.findByUserIds(anyCollection())).thenReturn(List.of());
        when(blacklistRepository.findActiveUserIds(anyCollection())).thenReturn(Set.of());
        when(userAccountRepository.findByUserIds(anyCollection())).thenReturn(List.of());

        var result = service.listUsers(1L, 0, 20, null, null, null, null, null);

        assertEquals(1, result.total());
        assertEquals("139****0001", result.items().get(0).phoneNumber());
    }

    @Test
    void listRecharges_mapsDtoFields() {
        RechargeOrder order = new RechargeOrder();
        order.setOrderId("R-1");
        order.setUserId(10001L);
        order.setAmountCents(500);
        order.setChannel("WECHAT");
        order.setStatus("PAID");
        order.setCreatedAt(Instant.parse("2026-09-01T00:00:00Z"));
        Page<RechargeOrder> page = new PageImpl<>(List.of(order));
        when(rechargeOrderRepository.search(isNull(), eq(10001L), any(Pageable.class))).thenReturn(page);

        PageResult<RechargeOrderDto> result = service.listRecharges(1L, 0, 20, null, 10001L);

        assertEquals(1, result.total());
        assertEquals("R-1", result.items().get(0).orderId());
        assertEquals(500, result.items().get(0).amountCents());
        assertEquals("PAID", result.items().get(0).status());
    }
}
