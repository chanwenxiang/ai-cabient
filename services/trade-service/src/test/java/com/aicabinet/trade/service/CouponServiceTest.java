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
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.UserCouponMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
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
    /** V319：范围判定需要柜机所属商户。 */
    @Mock private DeviceInfoMapper deviceInfoMapper;
    @Mock private DistributedLockService distributedLockService;
    @Mock private ScheduledTaskService taskService;
    @Mock private PromotionService promotionService;

    private CouponService couponService;

    @BeforeEach
    void setUp() {
        couponService = new CouponService(
                taskService, definitionRepository, userCouponRepository, userInfoRepository, orderRepository,
                orderLineRepository, distributedLockService, promotionService, deviceInfoMapper, null);
        org.springframework.test.util.ReflectionTestUtils.setField(couponService, "self", couponService);
        lenient().when(taskService.tryBegin(anyString(), anyLong())).thenReturn(true);
        lenient().when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
    }

    @Test
    void createDefinition_shouldSaveAndReturn() {
        var req = new CreateCouponRequest("测试券", "AMOUNT_OFF", 500, 0, null, 30, 100, "测试");
        when(definitionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var result = couponService.createDefinition(req);

        assertNotNull(result);
        assertEquals("测试券", result.couponName());
        assertEquals(500, result.denominationCents());
        assertNull(result.discountPercent());
        assertNull(result.activityId());
        assertEquals("ACTIVE", result.status());
        verify(definitionRepository, times(1)).save(any());
        verify(promotionService, never()).requireExists(any());
    }

    @Test
    void createDefinition_shouldBindActivityId() {
        var req = new CreateCouponRequest("预算券", "AMOUNT_OFF", 100, 100, null, 7, 10, "T-102", 1L);
        when(definitionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var result = couponService.createDefinition(req);

        assertEquals(1L, result.activityId());
        verify(promotionService, times(1)).requireExists(1L);
        verify(definitionRepository, times(1)).save(argThat(def ->
                Long.valueOf(1L).equals(def.getActivityId())));
    }

    @Test
    void createDefinition_shouldRejectBlankName() {
        var req = new CreateCouponRequest("  ", "AMOUNT_OFF", 500, 0, null, 30, 100, "测试");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> couponService.createDefinition(req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void createDefinition_shouldRejectAmountOffWithoutDenomination() {
        var req = new CreateCouponRequest("坏券", "AMOUNT_OFF", 0, 0, null, 30, 100, "测试");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> couponService.createDefinition(req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void createDefinition_shouldRejectPercentOffWithoutRate() {
        var req = new CreateCouponRequest("坏折扣", "PERCENT_OFF", 0, 0, null, 30, 100, "测试");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> couponService.createDefinition(req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
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

        var req = new UpdateCouponRequest("新券", "PERCENT_OFF", 0, 1000, 20, 60, 200, "更新说明", 9L);
        when(definitionRepository.findById(1L)).thenReturn(Optional.of(def));
        when(definitionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var result = couponService.updateDefinition(1L, req);

        assertEquals("新券", result.couponName());
        assertEquals("PERCENT_OFF", result.couponType());
        assertEquals(20, result.discountPercent());
        assertEquals(9L, result.activityId());
        assertEquals("INACTIVE", result.status());
        assertEquals(10, result.issuedCount());
        verify(promotionService, times(1)).requireExists(9L);
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
        when(definitionRepository.findAllById(anyCollection())).thenReturn(List.of(def));

        var result = couponService.listUserCoupons(10001L, "UNUSED");

        assertEquals(1, result.size());
        assertEquals("测试券", result.get(0).couponName());
        verify(definitionRepository, times(1)).findAllById(anyCollection());
        verify(definitionRepository, never()).findById(any());
    }

    @Test
    @DisplayName("MA-026：日批没跑时，过期券不得在读面冒充「未使用/可用」")
    void listUserCoupons_excludesExpiredButStillUnusedRows() {
        UserCoupon stale = userCoupon(1L, "UNUSED", Instant.now().minus(5, ChronoUnit.DAYS));
        UserCoupon fresh = userCoupon(2L, "UNUSED", Instant.now().plus(5, ChronoUnit.DAYS));
        when(userCouponRepository.findByUserIdAndStatus(10001L, "UNUSED"))
                .thenReturn(List.of(stale, fresh));
        when(definitionRepository.findAllById(anyCollection())).thenReturn(List.of());

        var listed = couponService.listUserCoupons(10001L, "UNUSED");
        assertEquals(List.of(2L), listed.stream().map(c -> c.couponId()).toList(),
                "库里还是 UNUSED 但已过 expire_at 的券不该留在「未使用」页签里");

        // 不带筛选时仍要出现，但状态必须是有效状态 EXPIRED（前端据此禁掉「设为优先使用」）
        when(userCouponRepository.findByUserIdOrderByCreatedAtDesc(10001L)).thenReturn(List.of(stale));
        var all = couponService.listUserCoupons(10001L, null);
        assertEquals("EXPIRED", all.get(0).status(), "读面下发的必须是有效状态，与 useCoupon/selectBestCoupon 同判据");

        // 可用张数走「UNUSED 且未过期」，不再用裸 status 计数
        when(userCouponRepository.countUnusedNotExpired(anyLong(), any())).thenReturn(1L);
        assertEquals(1L, couponService.countAvailable(10001L));
        verify(userCouponRepository, never()).countByUserIdAndStatus(anyLong(), anyString());
    }

    @Test
    @DisplayName("MA-026：effectiveStatus 只把「UNUSED 且已过期」翻成 EXPIRED，其它状态原样")
    void effectiveStatus_onlyFlipsUnusedPastExpiry() {
        Instant now = Instant.now();
        assertEquals("EXPIRED", CouponService.effectiveStatus(userCoupon(1L, "UNUSED", now.minusSeconds(1)), now));
        assertEquals("UNUSED", CouponService.effectiveStatus(userCoupon(2L, "UNUSED", now.plusSeconds(1)), now));
        assertEquals("UNUSED", CouponService.effectiveStatus(userCoupon(3L, "UNUSED", null), now));
        assertEquals("USED", CouponService.effectiveStatus(userCoupon(4L, "USED", now.minusSeconds(1)), now));
    }

    @Test
    @DisplayName("MA-028：券名里的「满 N 减」必须等于最低消费，不含数字门槛的名字不受约束")
    void validateNameMatchesThreshold_rejectsContradictingNames() {
        // 名字承诺与门槛不符 ⇒ 拒（这正是 dev 库里 id 4/5/6 三张演示券的形态）
        assertThrows(ResponseStatusException.class,
                () -> CouponService.validateNameMatchesThreshold("满 100 减 20 券", 5000));
        assertThrows(ResponseStatusException.class,
                () -> CouponService.validateNameMatchesThreshold("满 20 减 5 券", 0));
        // 对齐后放行
        CouponService.validateNameMatchesThreshold("满 50 减 20 券", 5000);
        CouponService.validateNameMatchesThreshold("无门槛减 5 券", 0);
        CouponService.validateNameMatchesThreshold("满 99.9 减 10 券", 9990);
        // 「满减券 ¥5」没有数字门槛承诺，不该被误拦
        CouponService.validateNameMatchesThreshold("满减券 ¥5", 2000);
        CouponService.validateNameMatchesThreshold("新人立减 ¥2", 0);
    }

    private static UserCoupon userCoupon(Long id, String status, Instant expireAt) {
        UserCoupon uc = new UserCoupon();
        uc.setCouponId(id);
        uc.setUserId(10001L);
        uc.setCouponDefId(1L);
        uc.setCouponCode("TEST" + id);
        uc.setStatus(status);
        uc.setExpireAt(expireAt);
        return uc;
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
        def.setStatus("ACTIVE");

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
        def.setStatus("ACTIVE");

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

    /** C07：争议订单禁止手动核销用券（原来只降总额会造成退款差额口径不一致）。 */
    @Test
    void useCoupon_shouldReject_whenOrderDisputed() {
        var uc = new UserCoupon();
        uc.setCouponId(1L);
        uc.setUserId(10001L);
        uc.setCouponDefId(1L);
        uc.setStatus("UNUSED");
        uc.setExpireAt(Instant.now().plus(30, ChronoUnit.DAYS));

        var order = new CabinetOrder();
        order.setOrderId("O-DISPUTED");
        order.setUserId(10001L);
        order.setStatus("DISPUTED");
        order.setTotalAmountCents(2000);

        when(userCouponRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(uc));
        when(orderRepository.findByIdForUpdate("O-DISPUTED")).thenReturn(Optional.of(order));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> couponService.useCoupon(10001L, 1L, "O-DISPUTED", "CAB-1"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("争议处理中的订单不支持使用优惠券", ex.getReason());
        verify(userCouponRepository, never()).save(any());
        verify(orderRepository, never()).save(any());
    }

    /** M23：手动核销路径校验券定义状态——INACTIVE 定义不可核销。 */
    @Test
    void useCoupon_shouldReject_whenDefinitionInactive() {
        var uc = new UserCoupon();
        uc.setCouponId(1L);
        uc.setUserId(10001L);
        uc.setCouponDefId(1L);
        uc.setStatus("UNUSED");
        uc.setExpireAt(Instant.now().plus(30, ChronoUnit.DAYS));

        var def = new CouponDefinition();
        def.setCouponDefId(1L);
        def.setCouponType("AMOUNT_OFF");
        def.setDenominationCents(500);
        def.setStatus("INACTIVE");

        var order = new CabinetOrder();
        order.setOrderId("O-INACT");
        order.setUserId(10001L);
        order.setStatus("PENDING");
        order.setTotalAmountCents(2000);

        when(userCouponRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(uc));
        when(orderRepository.findByIdForUpdate("O-INACT")).thenReturn(Optional.of(order));
        when(definitionRepository.findById(1L)).thenReturn(Optional.of(def));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> couponService.useCoupon(10001L, 1L, "O-INACT", "CAB-1"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("优惠券已停用", ex.getReason());
        verify(userCouponRepository, never()).save(any());
        verify(orderRepository, never()).save(any());
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
        when(definitionRepository.findAllById(anyCollection())).thenReturn(List.of(def));

        couponService.expireOverdueCoupons();

        assertEquals("EXPIRED", expired.getStatus());
        verify(promotionService).releaseBudget(99L, 400);
        verify(userCouponRepository).saveAll(anyList());
    }

    // ── AU-110 负向验证：手动核销必须过 DEVICE/MERCHANT 范围 ──

    /** UNUSED 券 + ACTIVE 定义 + PENDING 订单（orderDevice 指定）全套装配。 */
    private void stubUsablePair(CouponDefinition def, String orderDeviceId) {
        var uc = new UserCoupon();
        uc.setCouponId(1L);
        uc.setUserId(10001L);
        uc.setCouponDefId(1L);
        uc.setCouponCode("TEST1234ABCD");
        uc.setStatus("UNUSED");
        uc.setExpireAt(Instant.now().plus(30, ChronoUnit.DAYS));
        var order = new CabinetOrder();
        order.setOrderId("O-SCOPE-1");
        order.setUserId(10001L);
        order.setStatus("PENDING");
        order.setTotalAmountCents(2000);
        order.setDeviceId(orderDeviceId);
        when(userCouponRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(uc));
        when(definitionRepository.findById(1L)).thenReturn(Optional.of(def));
        when(orderRepository.findByIdForUpdate("O-SCOPE-1")).thenReturn(Optional.of(order));
        // 拒用用例在范围判定处即抛，触不到 save/明细查询 ⇒ 这三处用 lenient 供两用例族共用
        lenient().when(orderRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(userCouponRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(userCouponRepository.findByOrderIdAndStatus("O-SCOPE-1", "USED")).thenReturn(List.of());
        lenient().when(orderLineRepository.findByOrderId("O-SCOPE-1")).thenReturn(List.of());
    }

    private static CouponDefinition amountOffDef(String scopeType, String[] deviceIds, String merchantId) {
        var def = new CouponDefinition();
        def.setCouponDefId(1L);
        def.setCouponName("定向券");
        def.setCouponType("AMOUNT_OFF");
        def.setDenominationCents(500);
        def.setStatus("ACTIVE");
        def.setScopeType(scopeType);
        def.setScopeDeviceIds(deviceIds);
        def.setScopeMerchantId(merchantId);
        return def;
    }

    @Test
    void useCoupon_deviceScopedCoupon_rejectsOrderOnForeignDevice() {
        stubUsablePair(amountOffDef("DEVICE", new String[]{"CAB-001"}, null), "CAB-999");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> couponService.useCoupon(10001L, 1L, "O-SCOPE-1", "CAB-999"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void useCoupon_merchantScopedCoupon_rejectsOrderOnForeignMerchantDevice() {
        var device = new com.aicabinet.trade.domain.DeviceInfo();
        device.setDeviceId("CAB-999");
        device.setMerchantId("M2");
        when(deviceInfoMapper.findByIdForUpdateRaw("CAB-999")).thenReturn(device);
        stubUsablePair(amountOffDef("MERCHANT", null, "M1"), "CAB-999");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> couponService.useCoupon(10001L, 1L, "O-SCOPE-1", "CAB-999"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void useCoupon_deviceScopedCoupon_allowsOrderOnOwnDevice() {
        stubUsablePair(amountOffDef("DEVICE", new String[]{"CAB-999"}, null), "CAB-999");

        var result = couponService.useCoupon(10001L, 1L, "O-SCOPE-1", "CAB-999");

        assertEquals("USED", result.status());
    }
}
