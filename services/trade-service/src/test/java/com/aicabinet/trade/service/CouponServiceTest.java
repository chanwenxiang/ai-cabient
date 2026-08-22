package com.aicabinet.trade.service;

import com.aicabinet.common.dto.CreateCouponRequest;
import com.aicabinet.common.dto.UpdateCouponRequest;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.CouponDefinition;
import com.aicabinet.trade.domain.UserCoupon;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.CouponDefinitionMapper;
import com.aicabinet.trade.mapper.UserCouponMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CouponServiceTest {

    @Mock private CouponDefinitionMapper definitionRepository;
    @Mock private UserCouponMapper userCouponRepository;
    @Mock private UserInfoMapper userInfoRepository;
    @Mock private CabinetOrderMapper orderRepository;
    @Mock private ScheduledTaskService taskService;

    private CouponService couponService;

    @BeforeEach
    void setUp() {
        couponService = new CouponService(
                definitionRepository, userCouponRepository, userInfoRepository, orderRepository);
        org.springframework.test.util.ReflectionTestUtils.setField(couponService, "taskService", taskService);
        lenient().when(taskService.tryBegin(anyString(), anyLong())).thenReturn(true);
    }

    @Test
    void createDefinition_shouldSaveAndReturn() {
        var req = new CreateCouponRequest("测试券", "AMOUNT_OFF", 500, 0, null, 30, 100, "测试");
        when(definitionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var result = couponService.createDefinition(req);

        assertNotNull(result);
        assertEquals("测试券", result.couponName());
        assertEquals(500, result.denominationCents());
        assertEquals("ACTIVE", result.status());
        verify(definitionRepository, times(1)).save(any());
    }

    @Test
    void updateDefinition_shouldUpdateFieldsAndPreserveStatus() {
        var def = new CouponDefinition();
        def.setCouponDefId(1L);
        def.setCouponName("旧券");
        def.setCouponType("AMOUNT_OFF");
        def.setDenominationCents(100);
        def.setMinSpendCents(0);
        def.setValidityDays(7);
        def.setMaxIssueCount(50);
        def.setStatus("INACTIVE");
        def.setIssuedCount(10);

        var req = new UpdateCouponRequest("新券", "PERCENT_OFF", 0, 1000, 20, 60, 200, "更新说明");
        when(definitionRepository.findById(1L)).thenReturn(Optional.of(def));
        when(definitionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var result = couponService.updateDefinition(1L, req);

        assertEquals("新券", result.couponName());
        assertEquals("PERCENT_OFF", result.couponType());
        assertEquals(20, result.discountPercent());
        assertEquals("INACTIVE", result.status());
        assertEquals(10, result.issuedCount());
        verify(definitionRepository, times(1)).save(def);
    }

    @Test
    void updateDefinition_shouldThrow_whenNotFound() {
        when(definitionRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class,
                () -> couponService.updateDefinition(99L,
                        new UpdateCouponRequest("x", "AMOUNT_OFF", 100, 0, null, 30, 100, null)));
    }

    @Test
    void issueToUser_shouldCreateUserCoupon() {
        var def = new CouponDefinition();
        def.setCouponDefId(1L);
        def.setCouponName("测试券");
        def.setCouponType("AMOUNT_OFF");
        def.setDenominationCents(500);
        def.setValidityDays(30);
        def.setStatus("ACTIVE");
        def.setMaxIssueCount(100);

        var user = new com.aicabinet.trade.domain.UserInfo();
        user.setUserId(10001L);

        when(definitionRepository.findById(1L)).thenReturn(Optional.of(def));
        when(userInfoRepository.findById(10001L)).thenReturn(Optional.of(user));
        when(userCouponRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var result = couponService.issueToUser(10001L, 1L);

        assertNotNull(result);
        assertEquals("UNUSED", result.status());
        assertNotNull(result.couponCode());
        assertEquals(12, result.couponCode().length());
        assertEquals(1, def.getIssuedCount());
    }

    @Test
    void issueToUser_shouldThrow_whenDefinitionExpired() {
        var def = new CouponDefinition();
        def.setCouponDefId(1L);
        def.setStatus("DISABLED");
        when(definitionRepository.findById(1L)).thenReturn(Optional.of(def));

        assertThrows(ResponseStatusException.class,
                () -> couponService.issueToUser(10001L, 1L));
    }

    @Test
    void listUserCoupons_shouldReturnFilteredList() {
        var uc = new UserCoupon();
        uc.setCouponId(1L);
        uc.setUserId(10001L);
        uc.setCouponDefId(1L);
        uc.setCouponCode("TEST1234ABCD");
        uc.setStatus("UNUSED");
        uc.setExpireAt(Instant.now().plus(30, ChronoUnit.DAYS));

        when(userCouponRepository.findByUserIdAndStatus(10001L, "UNUSED"))
                .thenReturn(List.of(uc));

        var def = new CouponDefinition();
        def.setCouponDefId(1L);
        def.setCouponName("测试券");
        def.setDenominationCents(500);
        when(definitionRepository.findById(1L)).thenReturn(Optional.of(def));

        var result = couponService.listUserCoupons(10001L, "UNUSED");

        assertEquals(1, result.size());
        assertEquals("测试券", result.get(0).couponName());
    }

    @Test
    void useCoupon_shouldMarkUsed() {
        var uc = new UserCoupon();
        uc.setCouponId(1L);
        uc.setUserId(10001L);
        uc.setCouponDefId(1L);
        uc.setCouponCode("TEST1234ABCD");
        uc.setStatus("UNUSED");
        uc.setExpireAt(Instant.now().plus(30, ChronoUnit.DAYS));

        var def = new CouponDefinition();
        def.setCouponDefId(1L);
        def.setCouponName("测试券");
        def.setCouponType("AMOUNT_OFF");
        def.setDenominationCents(500);

        when(userCouponRepository.findById(1L)).thenReturn(Optional.of(uc));
        when(definitionRepository.findById(1L)).thenReturn(Optional.of(def));
        when(userCouponRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        var order = new CabinetOrder();
        order.setOrderId("O-TEST-001");
        order.setUserId(10001L);
        order.setStatus("PENDING");
        order.setTotalAmountCents(2000);
        when(orderRepository.findById("O-TEST-001")).thenReturn(Optional.of(order));
        when(orderRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var result = couponService.useCoupon(10001L, 1L, "O-TEST-001", "CAB-001");

        assertEquals("USED", result.status());
        assertEquals("USED", uc.getStatus());
        assertEquals("O-TEST-001", uc.getOrderId());
    }

    @Test
    void useCoupon_shouldThrow_whenWrongUser() {
        var uc = new UserCoupon();
        uc.setCouponId(1L);
        uc.setUserId(99999L);  // different user
        uc.setStatus("UNUSED");
        uc.setExpireAt(Instant.now().plus(30, ChronoUnit.DAYS));

        when(userCouponRepository.findById(1L)).thenReturn(Optional.of(uc));

        assertThrows(ResponseStatusException.class,
                () -> couponService.useCoupon(10001L, 1L, "O-TEST", "CAB-001"));
    }

    @Test
    void expireOverdueCoupons_shouldUpdateExpired() {
        var expired = new UserCoupon();
        expired.setCouponId(1L);
        expired.setUserId(10001L);
        expired.setCouponDefId(1L);
        expired.setStatus("UNUSED");
        expired.setExpireAt(Instant.now().minus(1, ChronoUnit.DAYS));

        when(userCouponRepository.findByStatusAndExpireAtBefore(eq("UNUSED"), any()))
                .thenReturn(List.of(expired));

        couponService.expireOverdueCoupons();

        assertEquals("EXPIRED", expired.getStatus());
        verify(userCouponRepository).saveAll(anyList());
    }
}
