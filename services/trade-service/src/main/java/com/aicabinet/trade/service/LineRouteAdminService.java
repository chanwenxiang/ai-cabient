package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CommissionClawback;
import com.aicabinet.trade.domain.LineCommissionDaily;
import com.aicabinet.trade.domain.LineRoute;
import com.aicabinet.trade.domain.LineRouteMember;
import com.aicabinet.trade.domain.StaffCommissionDaily;
import com.aicabinet.trade.mapper.CommissionClawbackMapper;
import com.aicabinet.trade.mapper.LineCommissionDailyMapper;
import com.aicabinet.trade.mapper.LineRouteMapper;
import com.aicabinet.trade.mapper.LineRouteMemberMapper;
import com.aicabinet.trade.mapper.LineManagerMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.mapper.StaffCommissionDailyMapper;
import com.aicabinet.trade.support.CommissionParamBounds;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 线路组织（V358）业务逻辑：线路 CRUD/成员/岗位月合计/总负责人读取。
 * 2026-10-14 自 OpsLineRouteController 下沉（架构门禁：Controller 禁直调 Mapper）。
 */
@Service
public class LineRouteAdminService {

    /** V358 列注释即词表：role=MANAGER/STAFF，basis=GMV/RESTOCK_QTY。 */
    private static final java.util.Set<String> MEMBER_ROLES = java.util.Set.of("MANAGER", "STAFF");
    private static final java.util.Set<String> MEMBER_BASES = java.util.Set.of("GMV", "RESTOCK_QTY");
    private static final int ROUTE_NAME_MAX = 64;
    private static final int ROUTE_REMARK_MAX = 200;

    private final LineRouteMapper routeMapper;
    private final LineRouteMemberMapper memberMapper;
    private final SystemConfigService systemConfigService;
    private final LineCommissionDailyMapper lineCommissionDailyMapper;
    private final StaffCommissionDailyMapper staffCommissionDailyMapper;
    private final CommissionClawbackMapper clawbackMapper;
    private final LineManagerMapper lineManagerMapper;
    private final UserInfoMapper userInfoMapper;
    private final AdminAuditService auditService;

    public LineRouteAdminService(LineRouteMapper routeMapper,
                                 LineRouteMemberMapper memberMapper,
                                 SystemConfigService systemConfigService,
                                 LineCommissionDailyMapper lineCommissionDailyMapper,
                                 StaffCommissionDailyMapper staffCommissionDailyMapper,
                                 CommissionClawbackMapper clawbackMapper,
                                 LineManagerMapper lineManagerMapper,
                                 UserInfoMapper userInfoMapper,
                                 AdminAuditService auditService) {
        this.routeMapper = routeMapper;
        this.memberMapper = memberMapper;
        this.systemConfigService = systemConfigService;
        this.lineCommissionDailyMapper = lineCommissionDailyMapper;
        this.staffCommissionDailyMapper = staffCommissionDailyMapper;
        this.clawbackMapper = clawbackMapper;
        this.lineManagerMapper = lineManagerMapper;
        this.userInfoMapper = userInfoMapper;
        this.auditService = auditService;
    }

    /** 线路列表（含归档）。 */
    public List<LineRoute> list() {
        return routeMapper.selectList(Wrappers.<LineRoute>lambdaQuery().orderByDesc(LineRoute::getId));
    }

    /** 新建线路。 */
    public LineRoute create(LineRoute route) {
        route.setId(null);
        applyRouteFields(route, route);
        routeMapper.insert(route);
        return route;
    }

    /** 更新线路（线路名/备注/线长可随时改）。 */
    public LineRoute update(Long routeId, LineRoute body) {
        LineRoute route = routeMapper.selectById(routeId);
        if (route == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "线路不存在");
        }
        if (body.getRouteName() != null) {
            route.setRouteName(body.getRouteName());
        }
        if (body.getManagerId() != null) {
            route.setManagerId(body.getManagerId());
        }
        if (body.getRemark() != null) {
            route.setRemark(body.getRemark());
        }
        applyRouteFields(route, route);
        routeMapper.updateById(route);
        return route;
    }

    /**
     * MA-108：线路行的必填/引用闸。{@code manager_id} 与 {@code route_name} 在 V358 都是
     * NOT NULL 但**无外键**——裸实体直落既允许幽灵线长（指向不存在的 manager_id），
     * 也让缺字段只撞成一个没有字段名的泛化 409。
     */
    private void applyRouteFields(LineRoute source, LineRoute target) {
        String name = source.getRouteName();
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "routeName 必填");
        }
        if (name.trim().length() > ROUTE_NAME_MAX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "routeName 超长（最多 " + ROUTE_NAME_MAX + " 字符）");
        }
        target.setRouteName(name.trim());
        if (source.getRemark() != null && source.getRemark().length() > ROUTE_REMARK_MAX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "remark 超长（最多 " + ROUTE_REMARK_MAX + " 字符）");
        }
        Long managerId = source.getManagerId();
        if (managerId == null || lineManagerMapper.selectById(managerId) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "managerId 不存在（须是已建线长）");
        }
        target.setManagerId(managerId);
    }

    /** 归档（可增减，不物理删除）。 */
    public void archive(Long routeId) {
        LineRoute route = routeMapper.selectById(routeId);
        if (route == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "线路不存在");
        }
        route.setStatus("ARCHIVED");
        routeMapper.updateById(route);
    }

    /** 线路成员列表（MANAGER 线长 / STAFF 补货员，同人可双角色两行）。 */
    public List<LineRouteMember> members(Long routeId) {
        return memberMapper.selectList(Wrappers.<LineRouteMember>lambdaQuery()
                .eq(LineRouteMember::getRouteId, routeId)
                .orderByAsc(LineRouteMember::getId));
    }

    /**
     * 添加/更新成员（同人同角色唯一，存在即更新提成配置）。
     *
     * <p>MA-100：这三列（basis/rateBps/perUnitCents）被 {@code LineCommissionJob} 逐日乘进
     * <b>补货员可提现钱包</b>（{@code staffWalletService.creditIfAbsent}），此前既无限幅也无留痕——
     * 与 MA-069（线长佣金）同族，只是这里连「谁改的」都查不到。
     */
    @Transactional
    public LineRouteMember upsertMember(Long operatorId, Long routeId, LineRouteMember body) {
        String role = requireEnum("role", body.getRole(), MEMBER_ROLES);
        if (body.getUserId() == null || userInfoMapper.selectById(body.getUserId()) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId 不存在，不能挂进线路成员");
        }
        String basis = body.getCommissionBasis() == null || body.getCommissionBasis().isBlank()
                ? "GMV" : requireEnum("commissionBasis", body.getCommissionBasis(), MEMBER_BASES);
        int rateBps = body.getCommissionRateBps() == null
                ? 0 : CommissionParamBounds.requireBps("commissionRateBps", body.getCommissionRateBps());
        long perUnitCents = body.getCommissionPerUnitCents() == null
                ? 0L : CommissionParamBounds.requireCents("commissionPerUnitCents", body.getCommissionPerUnitCents());

        LineRouteMember existing = memberMapper.selectOne(Wrappers.<LineRouteMember>lambdaQuery()
                .eq(LineRouteMember::getRouteId, routeId)
                .eq(LineRouteMember::getUserId, body.getUserId())
                .eq(LineRouteMember::getRole, role));
        String detail = "basis=" + basis + ",rateBps=" + rateBps + ",perUnitCents=" + perUnitCents;
        if (existing != null) {
            String before = "basis=" + existing.getCommissionBasis()
                    + ",rateBps=" + existing.getCommissionRateBps()
                    + ",perUnitCents=" + existing.getCommissionPerUnitCents()
                    + ",status=" + existing.getStatus();
            existing.setCommissionBasis(basis);
            existing.setCommissionRateBps(rateBps);
            existing.setCommissionPerUnitCents(perUnitCents);
            existing.setStatus("ACTIVE");
            memberMapper.updateById(existing);
            auditService.appendLog(operatorId, "LINE_ROUTE_MEMBER_UPDATE", "LINE_ROUTE_MEMBER",
                    String.valueOf(existing.getId()), before + " -> " + detail + ",status=ACTIVE");
            return existing;
        }
        body.setRouteId(routeId);
        body.setRole(role);
        body.setCommissionBasis(basis);
        body.setCommissionRateBps(rateBps);
        body.setCommissionPerUnitCents(perUnitCents);
        body.setStatus("ACTIVE");
        memberMapper.insert(body);
        auditService.appendLog(operatorId, "LINE_ROUTE_MEMBER_CREATE", "LINE_ROUTE_MEMBER",
                String.valueOf(body.getId()),
                "route=" + routeId + ",userId=" + body.getUserId() + ",role=" + role + "," + detail);
        return body;
    }

    /** 移除成员。 */
    @Transactional
    public void removeMember(Long operatorId, Long routeId, Long memberId) {
        LineRouteMember member = memberMapper.selectById(memberId);
        if (member == null || !routeId.equals(member.getRouteId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "成员不存在");
        }
        memberMapper.deleteById(memberId);
        // MA-100：删成员同样切断一条佣金链，留痕口径与增/改一致
        auditService.appendLog(operatorId, "LINE_ROUTE_MEMBER_REMOVE", "LINE_ROUTE_MEMBER",
                String.valueOf(memberId),
                "route=" + routeId + ",userId=" + member.getUserId() + ",role=" + member.getRole());
    }

    /** 枚举值归一 + 白名单闸（V358 列注释即字典，写错值只会让该成员从计佣面静默消失）。 */
    private static String requireEnum(String field, String value, java.util.Set<String> allowed) {
        String upper = value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!allowed.contains(upper)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " 只能是 " + String.join("/", allowed));
        }
        return upper;
    }

    /**
     * 岗位月合计（线长销售提成 + 补货员佣金，供财务对账）。
     * 佣金随退款冲正（CB-040，V361）：佣金列=已发-已冲回（按原归属日净额化），
     * 另列 clawbackCents 保留冲回留痕供财务核对。
     */
    public Map<String, Object> monthlySummary(String month) {
        YearMonth ym = YearMonth.parse(month);
        LocalDate from = ym.atDay(1);
        LocalDate to = ym.plusMonths(1).atDay(1);
        // 线长：按人聚合（line_commission_daily：orderCount=订单数、gmvCents=销售额、commissionCents=佣金）
        Map<Long, long[]> managers = new LinkedHashMap<>();
        for (LineCommissionDaily row : lineCommissionDailyMapper.selectList(
                Wrappers.<LineCommissionDaily>lambdaQuery()
                        .ge(LineCommissionDaily::getBizDate, from)
                        .lt(LineCommissionDaily::getBizDate, to))) {
            long[] agg = managers.computeIfAbsent(row.getManagerId(), k -> new long[3]);
            agg[0] += row.getOrderCount() == null ? 0 : row.getOrderCount();
            agg[1] += row.getGmvCents() == null ? 0 : row.getGmvCents();
            agg[2] += row.getCommissionCents() == null ? 0 : row.getCommissionCents();
        }
        // 补货员：按人聚合（staff_commission_daily：restockQty=补货件数、commissionCents=佣金）
        Map<Long, long[]> staffs = new LinkedHashMap<>();
        for (StaffCommissionDaily row : staffCommissionDailyMapper.selectList(
                Wrappers.<StaffCommissionDaily>lambdaQuery()
                        .ge(StaffCommissionDaily::getBizDate, from)
                        .lt(StaffCommissionDaily::getBizDate, to))) {
            long[] agg = staffs.computeIfAbsent(row.getStaffUserId(), k -> new long[2]);
            agg[0] += row.getRestockQty() == null ? 0 : row.getRestockQty();
            agg[1] += row.getCommissionCents() == null ? 0 : row.getCommissionCents();
        }
        Map<Long, Long> managerClawback =
                clawbackSum(from, to, CommissionClawback.OWNER_MANAGER, CommissionClawback::getOwnerId);
        Map<Long, Long> staffClawback =
                clawbackSum(from, to, CommissionClawback.OWNER_STAFF, CommissionClawback::getOwnerId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("month", month);
        result.put("managers", managers.entrySet().stream()
                .map(e -> Map.of("managerId", e.getKey(), "orderCount", e.getValue()[0],
                        "gmvCents", e.getValue()[1],
                        "commissionCents", e.getValue()[2] - managerClawback.getOrDefault(e.getKey(), 0L),
                        "clawbackCents", managerClawback.getOrDefault(e.getKey(), 0L)))
                .toList());
        result.put("staffs", staffs.entrySet().stream()
                .map(e -> Map.of("staffUserId", e.getKey(), "restockQty", e.getValue()[0],
                        "commissionCents", e.getValue()[1] - staffClawback.getOrDefault(e.getKey(), 0L),
                        "clawbackCents", staffClawback.getOrDefault(e.getKey(), 0L)))
                .toList());
        return result;
    }

    /** 按人聚合月份窗内的累计佣金冲回额（CB-040）。 */
    private Map<Long, Long> clawbackSum(LocalDate from, LocalDate to, String ownerType,
                                        Function<CommissionClawback, Long> key) {
        Map<Long, Long> sum = new HashMap<>();
        for (CommissionClawback row : clawbackMapper.selectList(
                Wrappers.<CommissionClawback>lambdaQuery()
                        .eq(CommissionClawback::getOwnerType, ownerType)
                        .ge(CommissionClawback::getBizDate, from)
                        .lt(CommissionClawback::getBizDate, to))) {
            sum.merge(key.apply(row), row.getClawbackCents() == null ? 0L : row.getClawbackCents(),
                    Long::sum);
        }
        return sum;
    }

    /** 总线路负责人（多人，sys_config line_route.chief_manager_ids 逗号分隔，随时改指）。 */
    public String chiefManagersRaw() {
        String raw = systemConfigService.getValue("line_route.chief_manager_ids", "0");
        return raw == null ? "0" : raw;
    }
}
