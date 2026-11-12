package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.CabinetOrder;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@Mapper
public interface CabinetOrderMapper extends BaseTradeMapper<CabinetOrder> {

    CabinetOrder findByIdForUpdateRaw(@Param("orderId") String orderId);

    default Optional<CabinetOrder> findByIdForUpdate(String orderId) {
        return Optional.ofNullable(findByIdForUpdateRaw(orderId));
    }

    /** 仅当 pay_trade_no 为空时写入，避免并发回填覆盖。 */
    int backfillPayTradeNoIfAbsent(@Param("orderId") String orderId, @Param("tradeNo") String tradeNo);

    /** 仅当 payment_operation_id 为空时写入，避免并发回填覆盖。 */
    int backfillPaymentOperationIdIfAbsent(@Param("orderId") String orderId, @Param("operationId") String operationId);

    /**
     * MA-067：显式把订单的券字段摘干净。MP 的 {@code updateById} **跳过 null 字段**，
     * 所以 {@code order.setCouponId(null) + save()} 对**已 insert 过**的订单根本落不到库
     * （2026-11-02 活栈实测：扣款失败转 PENDING 的单仍挂着 coupon_id，而那张券在
     * {@code user_coupon} 里仍是 UNUSED ⇒ 「券已绑单但未核销」的悬空态；后续按
     * {@code order.getCouponId()} 重算折扣的链路会对一张没收的券再折一次 = 少收钱）。
     */
    @Update("""
        UPDATE cabinet_order
        SET coupon_id = NULL,
            coupon_discount_cents = 0,
            total_amount_cents = #{totalAmountCents},
            updated_at = #{updatedAt}
        WHERE order_id = #{orderId}
        """)
    int clearOrderCouponSelection(@Param("orderId") String orderId,
                                  @Param("totalAmountCents") int totalAmountCents,
                                  @Param("updatedAt") Instant updatedAt);

    /**
     * MA-147：全额退返还券后清订单头的券列，但<b>不动应付</b>（退款场景的 total 是既成事实，
     * 脏的只有券列）。
     *
     * <p>为什么必须显式 SQL：{@code updateById} 跳过 null 字段——{@code setCouponId(null) + save()}
     * 看着对，库里仍挂旧 {@code coupon_id}。Docker 组合矩阵 E2E 实测抓到：券已回 UNUSED、
     * 订单头却仍 {@code coupon_id=6} ⇒ 读面显示「这张单用过券且已退还」，两头都对不上账。
     */
    @Update("""
        UPDATE cabinet_order
        SET coupon_id = NULL,
            coupon_discount_cents = 0,
            updated_at = #{updatedAt}
        WHERE order_id = #{orderId}
        """)
    int clearOrderCouponColumns(@Param("orderId") String orderId, @Param("updatedAt") Instant updatedAt);

    default Optional<CabinetOrder> findBySessionId(String sessionId) {
    return Optional.ofNullable(selectOne(Wrappers.<CabinetOrder>lambdaQuery().eq(CabinetOrder::getSessionId, sessionId)));
    }

    default Page<CabinetOrder> findAllByOrderByCreatedAtDesc(Pageable pageable) {
    var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<CabinetOrder>(
            pageable.getPageNumber() + 1L, pageable.getPageSize());
    var result = selectPage(mpPage, Wrappers.<CabinetOrder>lambdaQuery().orderByDesc(CabinetOrder::getCreatedAt));
    return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    default Page<CabinetOrder> findByDeviceIdOrderByCreatedAtDesc(String deviceId, Pageable pageable) {
    var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<CabinetOrder>(
            pageable.getPageNumber() + 1L, pageable.getPageSize());
    var result = selectPage(mpPage, Wrappers.<CabinetOrder>lambdaQuery().eq(CabinetOrder::getDeviceId, deviceId).orderByDesc(CabinetOrder::getCreatedAt));
    return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    record OrderFilterCriteria(
            String deviceId,
            Collection<String> deviceIds,
            String status,
            Instant createdBefore,
            Instant createdFrom,
            Instant createdTo,
            String orderId,
            Long userId,
            String sessionId,
            String payTradeNo,
            String payChannel,
            String keyword,
            boolean excludeZeroAmount) {}

    default Page<CabinetOrder> findByFiltersOrderByCreatedAtDesc(
            String deviceId, Collection<String> deviceIds, String status, Pageable pageable) {
        return findByFiltersOrderByCreatedAtDesc(
                new OrderFilterCriteria(deviceId, deviceIds, status, null, null, null,
                        null, null, null, null, null, null, false), pageable);
    }

    default Page<CabinetOrder> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable) {
    var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<CabinetOrder>(
            pageable.getPageNumber() + 1L, pageable.getPageSize());
    var result = selectPage(mpPage, Wrappers.<CabinetOrder>lambdaQuery().eq(CabinetOrder::getUserId, userId).orderByDesc(CabinetOrder::getCreatedAt));
    return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    /** C-P2-9：消息中心待补缴角标，避免拉全页订单再 filter。 */
    default long countByUserIdAndStatusIn(Long userId, java.util.Collection<String> statuses) {
        if (userId == null || statuses == null || statuses.isEmpty()) {
            return 0L;
        }
        Long c = selectCount(Wrappers.<CabinetOrder>lambdaQuery()
                .eq(CabinetOrder::getUserId, userId)
                .in(CabinetOrder::getStatus, statuses));
        return c == null ? 0L : c;
    }

    default java.util.List<CabinetOrder> findByCreatedAtAfter(Instant since) {
    return selectList(Wrappers.<CabinetOrder>lambdaQuery().gt(CabinetOrder::getCreatedAt, since));
    }

    default long countByCreatedAtAfter(Instant since) {
    Long c = selectCount(Wrappers.<CabinetOrder>lambdaQuery().gt(CabinetOrder::getCreatedAt, since));
    return c == null ? 0 : c;
    }

    long sumTotalAmountSince(@Param("since") Instant since);

    long sumTotalAmount();

    default long countByDeviceId(String deviceId) {
    Long c = selectCount(Wrappers.<CabinetOrder>lambdaQuery().eq(CabinetOrder::getDeviceId, deviceId));
    return c == null ? 0 : c;
    }

    default long countByDeviceIdAndCreatedAtAfter(String deviceId, Instant since) {
    Long c = selectCount(Wrappers.<CabinetOrder>lambdaQuery().eq(CabinetOrder::getDeviceId, deviceId).gt(CabinetOrder::getCreatedAt, since));
    return c == null ? 0 : c;
    }

        long sumAmountByDeviceId(@Param("deviceId") String deviceId);


        long sumAmountByDeviceIdSince(@Param("deviceId") String deviceId, @Param("since") Instant since);

    /**
     * CB-018①：柜机×日营收聚合，返回行 [c0=device_id, c1=Asia/Shanghai 日(java.sql.Date),
     * c2=营收分, c3=订单数]。口径与 {@link #sumAmountByDeviceId} 一致（SUM(total_amount_cents)
     * 不过滤状态），保证日序列求和可与报表「累计营收」对平；{@code deviceIds} 为 null=全部（scope 由调用方裁剪）。
     */
    List<java.util.LinkedHashMap<String, Object>> selectRevenueDailyByDeviceSince(
            @Param("deviceIds") Collection<String> deviceIds, @Param("since") Instant since);


        long sumTotalAmountBetween(@Param("start") Instant start, @Param("end") Instant end);


        java.util.List<String> findOrderIdsBetween(@Param("start") Instant start, @Param("end") Instant end);


        java.util.List<CabinetOrder> findByCreatedAtBetween(@Param("start") Instant start, @Param("end") Instant end);

    /**
     * 按柜机 + 时间窗拉订单（线长日佣等）；禁止在 Java 侧对全日全量订单再 filter deviceId。
     */
    default List<CabinetOrder> findByDeviceIdAndCreatedAtBetween(
            String deviceId, Instant start, Instant end) {
        if (deviceId == null || deviceId.isBlank()) {
            return List.of();
        }
        return selectList(Wrappers.<CabinetOrder>lambdaQuery()
                .eq(CabinetOrder::getDeviceId, deviceId)
                .ge(CabinetOrder::getCreatedAt, start)
                .lt(CabinetOrder::getCreatedAt, end));
    }

    /**
     * 已支付（非 PENDING）订单按用户聚合：userId / orderCount / revenueCents / firstAt / lastAt。
     * 供用户行为分析；禁止用 {@link #findAll()} 物化全表。
     */
    java.util.List<java.util.LinkedHashMap<String, Object>> selectUserPaidOrderAgg();

    default List<Object[]> aggregatePaidOrdersByUser() {
        return ColumnMapRows.toObjectRows(selectUserPaidOrderAgg(), 5);
    }


    default Page<CabinetOrder> findByDeviceIdInOrderByCreatedAtDesc(Collection<String> deviceIds, Pageable pageable) {
    var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<CabinetOrder>(
            pageable.getPageNumber() + 1L, pageable.getPageSize());
    var result = selectPage(mpPage, Wrappers.<CabinetOrder>lambdaQuery().in(CabinetOrder::getDeviceId, deviceIds).orderByDesc(CabinetOrder::getCreatedAt));
    return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    default long countByDeviceIdIn(Collection<String> deviceIds) {
    Long c = selectCount(Wrappers.<CabinetOrder>lambdaQuery().in(CabinetOrder::getDeviceId, deviceIds));
    return c == null ? 0 : c;
    }

    default long countByDeviceIdInAndCreatedAtAfter(Collection<String> deviceIds, Instant since) {
    Long c = selectCount(Wrappers.<CabinetOrder>lambdaQuery().in(CabinetOrder::getDeviceId, deviceIds).gt(CabinetOrder::getCreatedAt, since));
    return c == null ? 0 : c;
    }

    default long countByDeviceIdInAndCreatedAtBetween(
            Collection<String> deviceIds, Instant start, Instant end) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            return 0;
        }
        Long c = selectCount(Wrappers.<CabinetOrder>lambdaQuery()
                .in(CabinetOrder::getDeviceId, deviceIds)
                .ge(CabinetOrder::getCreatedAt, start)
                .lt(CabinetOrder::getCreatedAt, end));
        return c == null ? 0 : c;
    }

    /**
     * 经营分析成交单数：排除开门未取货的零元单。
     * 全额退款后 total=0 但 refundedCents>0，仍计入（与运营列表 excludeZeroAmount 同口径）。
     */
    default long countBillableByDeviceIdInAndCreatedAtBetween(
            Collection<String> deviceIds, Instant start, Instant end) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            return 0;
        }
        Long c = selectCount(Wrappers.<CabinetOrder>lambdaQuery()
                .in(CabinetOrder::getDeviceId, deviceIds)
                .ge(CabinetOrder::getCreatedAt, start)
                .lt(CabinetOrder::getCreatedAt, end)
                .and(w -> w.gt(CabinetOrder::getTotalAmountCents, 0)
                        .or()
                        .gt(CabinetOrder::getRefundedCents, 0)));
        return c == null ? 0 : c;
    }

    default List<CabinetOrder> findByDeviceIdInAndCreatedAtAfter(Collection<String> deviceIds, Instant since) {
    return selectList(Wrappers.<CabinetOrder>lambdaQuery().in(CabinetOrder::getDeviceId, deviceIds).gt(CabinetOrder::getCreatedAt, since));
    }

    default List<CabinetOrder> findByStatusAndCreatedAtBefore(String status, Instant cutoff) {
        return selectList(Wrappers.<CabinetOrder>lambdaQuery()
                .eq(CabinetOrder::getStatus, status)
                .lt(CabinetOrder::getCreatedAt, cutoff)
                .orderByAsc(CabinetOrder::getCreatedAt));
    }

    /** 超时关单等批处理：限制单次条数。 */
    default List<CabinetOrder> findByStatusAndCreatedAtBefore(String status, Instant cutoff, int limit) {
        int lim = Math.max(1, Math.min(limit, 1000));
        return selectList(Wrappers.<CabinetOrder>lambdaQuery()
                .eq(CabinetOrder::getStatus, status)
                .lt(CabinetOrder::getCreatedAt, cutoff)
                .orderByAsc(CabinetOrder::getCreatedAt)
                .last("LIMIT " + lim));
    }

    default long countByStatusAndCreatedAtBefore(String status, Instant cutoff) {
        Long c = selectCount(Wrappers.<CabinetOrder>lambdaQuery()
                .eq(CabinetOrder::getStatus, status)
                .lt(CabinetOrder::getCreatedAt, cutoff));
        return c == null ? 0 : c;
    }

    default Page<CabinetOrder> findByFiltersOrderByCreatedAtDesc(
            String deviceId, Collection<String> deviceIds, String status,
            Instant createdBefore, Pageable pageable) {
        return findByFiltersOrderByCreatedAtDesc(
                new OrderFilterCriteria(deviceId, deviceIds, status, createdBefore, null, null,
                        null, null, null, null, null, null, false), pageable);
    }

    /**
     * 运营订单列表筛选：设备范围 + 状态/账龄 + 精确字段 + 关键词（订单号/设备/会话/用户/流水）。
     */
    default Page<CabinetOrder> findByFiltersOrderByCreatedAtDesc(
            OrderFilterCriteria criteria, Pageable pageable) {
        var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<CabinetOrder>(
                pageable.getPageNumber() + 1L, pageable.getPageSize());
        var q = Wrappers.<CabinetOrder>lambdaQuery();
        if (!applyOrderDeviceScope(q, criteria.deviceId(), criteria.deviceIds())) {
            return new org.springframework.data.domain.PageImpl<>(List.of(), pageable, 0);
        }
        applyOrderExactFilters(q, criteria);
        applyOrderKeywordFilter(q, criteria.keyword());
        q.orderByDesc(CabinetOrder::getCreatedAt);
        var result = selectPage(mpPage, q);
        return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    private static boolean applyOrderDeviceScope(
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CabinetOrder> q,
            String deviceId, Collection<String> deviceIds) {
        if (deviceId != null && !deviceId.isBlank()) {
            q.eq(CabinetOrder::getDeviceId, deviceId.trim());
            return true;
        }
        if (deviceIds == null) {
            return true;
        }
        if (deviceIds.isEmpty()) {
            return false;
        }
        q.in(CabinetOrder::getDeviceId, deviceIds);
        return true;
    }

    private static void applyOrderExactFilters(
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CabinetOrder> q,
            OrderFilterCriteria criteria) {
        if (criteria.status() != null && !criteria.status().isBlank()) {
            q.eq(CabinetOrder::getStatus, criteria.status().trim());
        }
        if (criteria.createdBefore() != null) {
            q.lt(CabinetOrder::getCreatedAt, criteria.createdBefore());
        }
        if (criteria.createdFrom() != null) {
            q.ge(CabinetOrder::getCreatedAt, criteria.createdFrom());
        }
        if (criteria.createdTo() != null) {
            q.le(CabinetOrder::getCreatedAt, criteria.createdTo());
        }
        if (criteria.orderId() != null && !criteria.orderId().isBlank()) {
            q.eq(CabinetOrder::getOrderId, criteria.orderId().trim());
        }
        if (criteria.userId() != null) {
            q.eq(CabinetOrder::getUserId, criteria.userId());
        }
        if (criteria.sessionId() != null && !criteria.sessionId().isBlank()) {
            q.eq(CabinetOrder::getSessionId, criteria.sessionId().trim());
        }
        if (criteria.payTradeNo() != null && !criteria.payTradeNo().isBlank()) {
            String trade = criteria.payTradeNo().trim();
            q.and(w -> w.eq(CabinetOrder::getPayTradeNo, trade)
                    .or()
                    .eq(CabinetOrder::getPaymentOperationId, trade));
        }
        if (criteria.payChannel() != null && !criteria.payChannel().isBlank()) {
            q.eq(CabinetOrder::getPayChannel, criteria.payChannel().trim());
        }
        // 零元单多为开门未取货结案；运营列表可屏蔽以免淹没真实成交。
        // 全额退款后 total=0 但 refundedCents>0，须保留（否则「已退款」Tab 被滤空，#204）。
        if (criteria.excludeZeroAmount()) {
            q.and(w -> w.gt(CabinetOrder::getTotalAmountCents, 0)
                    .or()
                    .gt(CabinetOrder::getRefundedCents, 0));
        }
    }

    private static void applyOrderKeywordFilter(
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CabinetOrder> q,
            String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return;
        }
        String kw = keyword.trim();
        Long kwUserId = parseOptionalUserId(kw);
        Long finalKwUserId = kwUserId;
        q.and(w -> {
            w.like(CabinetOrder::getOrderId, kw)
                    .or().like(CabinetOrder::getDeviceId, kw)
                    .or().like(CabinetOrder::getDeviceName, kw)
                    .or().like(CabinetOrder::getMerchantId, kw)
                    .or().like(CabinetOrder::getMerchantName, kw)
                    .or().like(CabinetOrder::getSessionId, kw)
                    .or().like(CabinetOrder::getPayTradeNo, kw)
                    .or().like(CabinetOrder::getPaymentOperationId, kw);
            if (finalKwUserId != null) {
                w.or().eq(CabinetOrder::getUserId, finalKwUserId);
            }
        });
    }

    private static Long parseOptionalUserId(String kw) {
        try {
            return Long.parseLong(kw);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    default long countByUserIdAndStatus(Long userId, String status) {
        Long c = selectCount(Wrappers.<CabinetOrder>lambdaQuery()
                .eq(CabinetOrder::getUserId, userId)
                .eq(CabinetOrder::getStatus, status));
        return c == null ? 0 : c;
    }

    long sumTotalAmountByDeviceIdIn(@Param("deviceIds") Collection<String> deviceIds);

    long sumTotalAmountByDeviceIdInSince(@Param("deviceIds") Collection<String> deviceIds,
                                         @Param("since") Instant since);

    long sumTotalAmountByDeviceIdInBetween(@Param("deviceIds") Collection<String> deviceIds,
                                           @Param("start") Instant start,
                                           @Param("end") Instant end);

    default List<CabinetOrder> findByCouponIdInAndCreatedAtAfter(
            Collection<Long> couponIds, Instant since) {
        if (couponIds == null || couponIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<CabinetOrder>lambdaQuery()
                .in(CabinetOrder::getCouponId, couponIds)
                .ge(CabinetOrder::getCreatedAt, since)
                .orderByDesc(CabinetOrder::getCreatedAt));
    }

    /**
     * 按**活动**聚合用券订单（R20 P2-15）：单数 / 营收 / 券面额。
     *
     * <p>ROI 表原来「每个活动一次」{@link #findByCouponIdInAndCreatedAtAfter}（先拉该活动全部券实例 id
     * 再 `IN` 查订单），活动多就是 N 次大 IN 查询；这里一条 join + group-by 收口，且不再把订单实体
     * 整个搬进内存做 stream 求和。
     *
     * <p>内层 {@code SELECT DISTINCT} 不是多余：{@code user_coupon} 与 {@code cabinet_order} 是
     * 一对多方向上的两张表，join 后若同一订单匹配到多行（同一券重复登记/历史脏数据），外层直接
     * {@code COUNT(DISTINCT)} + {@code SUM} 会让「单数」与「金额」两列口径分叉（金额被翻倍）。
     * 先按 (活动, 订单, 金额) 去重，外层 {@code COUNT(*)} 与 {@code SUM} 就必然是同一批行。
     *
     * <p>时间窗与旧实现一致：{@code created_at >= since}（含当天起点）。**不过滤订单状态**——
     * 旧实现也不过滤，ROI 的「带动营收」是含退款单的毛额（口径未变，改口径要产品拍板）。
     */
    @org.apache.ibatis.annotations.Select("""
            <script>
            SELECT t.activity_id AS activity_id,
                   COUNT(*) AS order_count,
                   COALESCE(SUM(t.total_amount_cents), 0) AS revenue_cents,
                   COALESCE(SUM(t.coupon_discount_cents), 0) AS discount_cents
            FROM (
              SELECT DISTINCT cd.activity_id, o.order_id, o.total_amount_cents, o.coupon_discount_cents
              FROM user_coupon uc
              JOIN coupon_definition cd ON cd.coupon_def_id = uc.coupon_def_id
              JOIN cabinet_order o ON o.coupon_id = uc.coupon_id
              WHERE cd.activity_id IN
              <foreach collection="activityIds" item="aid" open="(" separator="," close=")">#{aid}</foreach>
                AND o.created_at &gt;= #{since}
            ) t
            GROUP BY t.activity_id
            </script>
            """)
    List<java.util.Map<String, Object>> sumCouponOrdersByActivitySince(
            @Param("activityIds") Collection<Long> activityIds,
            @Param("since") Instant since);

    default List<CabinetOrder> findByStatusAndCreatedAtAfter(String status, Instant since) {
        return selectList(Wrappers.<CabinetOrder>lambdaQuery()
                .eq(CabinetOrder::getStatus, status)
                .gt(CabinetOrder::getCreatedAt, since));
    }

}
