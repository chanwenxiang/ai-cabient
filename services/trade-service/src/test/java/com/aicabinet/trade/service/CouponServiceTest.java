package com.aicabinet.trade.service;

import com.aicabinet.common.dto.CreateCouponRequest;
import com.aicabinet.common.dto.UpdateCouponRequest;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.CabinetOrderLine;
import com.aicabinet.trade.domain.CouponDefinition;
import com.aicabinet.trade.domain.UserCoupon;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
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
    @Mock private CabinetOrderLineMapper orderLineRepository;
    @Mock private DistributedLockService distributedLockService;
    @Mock private ScheduledTaskService taskService;
    @Mock private PromotionService promotionService;

    private CouponService couponService;

    @BeforeEach
    void setUp() {
        couponService = new CouponService(
                taskService, definitionRepository, userCouponRepository, userInfoRepository, orderRepository,
                orderLineRepository, distributedLockService, promotionService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(couponService, "self", couponService);
        lenient().when(taskService.tryBegin(anyString(), anyLong())).thenReturn(true);
        lenient().when(distributedLockService.tryLock(anyString(), eq(60L), eq(5L))).thenReturn(true);
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

        when(definitionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(def));
        when(userInfoRepository.findById(10001L)).thenReturn(Optional.of(user));
        when(userCouponRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(userCouponRepository.countByCouponDefId(1L)).thenReturn(1L);

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
        when(definitionRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(def));

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

        when(userCouponRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(uc));
        when(definitionRepository.findById(1L)).thenReturn(Optional.of(def));
        when(userCouponRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        var order = new CabinetOrder();
        order.setOrderId("O-TEST-001");
        order.setUserId(10001L);
        order.setStatus("PENDING");
        order.setTotalAmountCents(2000);
        when(orderRepository.findByIdForUpdate("O-TEST-001")).thenReturn(Optional.of(order));
        when(orderRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(userCouponRepository.findByOrderIdAndStatus("O-TEST-001", "USED")).thenReturn(List.of());
        when(orderLineRepository.findByOrderId("O-TEST-001")).thenReturn(List.of());

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

        when(userCouponRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(uc));

        assertThrows(ResponseStatusException.class,
                () -> couponService.useCoupon(10001L, 1L, "O-TEST", "CAB-001"));
    }

    @Test
    void issueToUser_shouldReservePromotionBudgetWhenLinked() {
        var def = new CouponDefinition();
        def.setCouponDefId(10L);
        def.setStatus("ACTIVE");
        def.setActivityId(99L);
        def.setDenominationCents(300);
        def.setValidityDays(7);
        def.setMaxIssueCount(0);

        when(definitionRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(def));
        when(userInfoRepository.findById(10001L)).thenReturn(Optional.of(new com.aicabinet.trade.domain.UserInfo()));
        when(userCouponRepository.countByCouponDefId(10L)).thenReturn(0L);
        when(userCouponRepository.save(any())).thenAnswer(i -> {
            UserCoupon uc = i.getArgument(0);
            uc.setCouponId(1L);
            return uc;
        });
        when(definitionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        couponService.issueToUser(10001L, 10L);

        verify(promotionService).reserveBudgetOnClaim(99L, 300);
    }

    @Test
    void markUsed_shouldReleaseUnusedPromotionBudget() {
        var uc = new UserCoupon();
        uc.setCouponId(1L);
        uc.setUserId(10001L);
        uc.setCouponDefId(10L);
        uc.setStatus("UNUSED");
        uc.setExpireAt(Instant.now().plus(7, ChronoUnit.DAYS));

        var def = new CouponDefinition();
        def.setCouponDefId(10L);
        def.setActivityId(99L);
        def.setDenominationCents(500);

        when(userCouponRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(uc));
        when(definitionRepository.findById(10L)).thenReturn(Optional.of(def));
        when(userCouponRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(userCouponRepository.findByOrderIdAndStatus("O-1", "USED")).thenReturn(List.of());

        couponService.markUsed(10001L, 1L, "O-1", "CAB-1", 200);

        verify(promotionService).releaseBudget(99L, 300);
    }

    @Test
    void markUsed_shouldReject_whenOtherCouponAlreadyUsedOnOrder() {
        var existing = new UserCoupon();
        existing.setCouponId(2L);
        existing.setUserId(10001L);
        existing.setStatus("USED");
        existing.setOrderId("O-1");

        var uc = new UserCoupon();
        uc.setCouponId(1L);
        uc.setUserId(10001L);
        uc.setStatus("UNUSED");
        uc.setExpireAt(Instant.now().plus(7, ChronoUnit.DAYS));

        when(userCouponRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(uc));
        when(userCouponRepository.findByOrderIdAndStatus("O-1", "USED")).thenReturn(List.of(existing));

        assertThrows(ResponseStatusException.class,
                () -> couponService.markUsed(10001L, 1L, "O-1", "CAB-1", 200));
    }

    @Test
    void useCoupon_shouldUseLineSubtotalNotDiscountedHeader() {
        var uc = new UserCoupon();
        uc.setCouponId(1L);
        uc.setUserId(10001L);
        uc.setCouponDefId(1L);
        uc.setStatus("UNUSED");
        uc.setExpireAt(Instant.now().plus(30, ChronoUnit.DAYS));

        var def = new CouponDefinition();
        def.setCouponDefId(1L);
        def.setCouponType("AMOUNT_OFF");
        def.setDenominationCents(200);
        def.setMinSpendCents(0);

        var line = new CabinetOrderLine();
        line.setLineAmountCents(2000);
        var order = new CabinetOrder();
        order.setOrderId("O-LINE");
        order.setUserId(10001L);
        order.setStatus("PENDING");
        order.setTotalAmountCents(150);

        when(userCouponRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(uc));
        when(definitionRepository.findById(1L)).thenReturn(Optional.of(def));
        when(userCouponRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(orderRepository.findByIdForUpdate("O-LINE")).thenReturn(Optional.of(order));
        when(orderRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(userCouponRepository.findByOrderIdAndStatus("O-LINE", "USED")).thenReturn(List.of());
        when(orderLineRepository.findByOrderId("O-LINE")).thenReturn(List.of(line));

        couponService.useCoupon(10001L, 1L, "O-LINE", "CAB-001");

        assertEquals(1800, order.getTotalAmountCents());
        assertEquals(200, order.getCouponDiscountCents());
    }

    @Test
    void useCoupon_shouldReject_whenOtherCouponAlreadyUsedOnOrder() {
        var existing = new UserCoupon();
        existing.setCouponId(2L);
        existing.setUserId(10001L);
        existing.setStatus("USED");
        existing.setOrderId("O-1");

        var uc = new UserCoupon();
        uc.setCouponId(1L);
        uc.setUserId(10001L);
        uc.setStatus("UNUSED");
        uc.setExpireAt(Instant.now().plus(7, ChronoUnit.DAYS));

        var order = new CabinetOrder();
        order.setOrderId("O-1");
        order.setUserId(10001L);
        order.setStatus("PENDING");
        order.setTotalAmountCents(2000);

        when(userCouponRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(uc));
        when(orderRepository.findByIdForUpdate("O-1")).thenReturn(Optional.of(order));
        when(userCouponRepository.findByOrderIdAndStatus("O-1", "USED")).thenReturn(List.of(existing));

        assertThrows(ResponseStatusException.class,
                () -> couponService.useCoupon(10001L, 1L, "O-1", "CAB-1"));
    }

    @Test
    void expireOverdueCoupons_shouldReleasePromotionBudget() {
        var expired = new UserCoupon();
        expired.setCouponId(1L);
        expired.setUserId(10001L);
        expired.setCouponDefId(10L);
        expired.setStatus("UNUSED");
        expired.setExpireAt(Instant.now().minus(1, ChronoUnit.DAYS));

        var def = new CouponDefinition();
        def.setCouponDefId(10L);
        def.setActivityId(99L);
        def.setDenominationCents(400);

        when(userCouponRepository.findByStatusAndExpireAtBefore(eq("UNUSED"), any()))
                .thenReturn(List.of(expired));
        when(definitionRepository.findById(10L)).thenReturn(Optional.of(def));

        couponService.expireOverdueCoupons();

        assertEquals("EXPIRED", expired.getStatus());
        verify(promotionService).releaseBudget(99L, 400);
        verify(userCouponRepository).saveAll(anyList());
    }
}
