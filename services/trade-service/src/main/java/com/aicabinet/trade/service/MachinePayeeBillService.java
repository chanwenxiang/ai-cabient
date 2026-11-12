package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.MachinePayeeBill;
import com.aicabinet.trade.domain.MachinePositionConfig;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.MachinePayeeBillMapper;
import com.aicabinet.trade.mapper.MachinePositionConfigMapper;
import com.aicabinet.trade.support.CommissionParamBounds;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

/**
 * 机器账单（付场地方，V356）：对齐老系统 MachinePositionBaseInfo 点位台账并补齐出账闭环。
 * 每月出上月账：电费（点位配置固定额）+ 销售分成（share_bps × 上月该柜已支付 GMV）。
 * 线下结算：生成 PENDING，线下付款后运营标记 SETTLED；费用项（电费额/分成比例/收款人）
 * 随时可改（machine_position_config），改动只影响后续账期。
 *
 * <p>CB-043（2026-10-15 拍板方案 A）：<b>点位收款人刻意维持单组，不扩多收款人子表</b>——
 * 老系统 5 组收款人存量实盘 1027 点位：93% ≤1 组、99.6% ≤2 组，合作2（1%）/租金收款人（1.6%）
 * 为死字段；线长佣金走独立链路（LineCommissionJob，退款冲正 CB-040）。若未来签多方分成合同，
 * machine_payee_bill 天然多行（device×month×fee_type），届时按需加 fee_type 变体或再评估，
 * 不要「好心」预扩表。
 */
@Service
public class MachinePayeeBillService {
    private static final Logger log = LoggerFactory.getLogger(MachinePayeeBillService.class);
    private static final Set<String> PAID_STATUSES = Set.of("PAID", "COMPLETED");
    /** 电费类型只认这两个值（V356 列注释 + V429 表注释同源；按量档因无计量暂缓）。 */
    private static final Set<String> ELECTRIC_TYPES = Set.of("NONE", "FIXED");
    /** 月电费上限（分）= ¥10,000：数量级防线，口径同 {@link CommissionParamBounds#FIXED_CENTS_MAX}。 */
    private static final long ELECTRIC_AMOUNT_MAX_CENTS = 1_000_000L;
    private static final int PAYEE_NAME_MAX = 64;
    private static final int PAYEE_PHONE_MAX = 20;
    private static final int WELFARE_INFO_MAX = 200;

    private final MachinePositionConfigMapper configMapper;
    private final MachinePayeeBillMapper billMapper;
    private final CabinetOrderMapper orderMapper;
    private final AdminAuditService auditService;
    private final MerchantScopeService merchantScopeService;

    public MachinePayeeBillService(MachinePositionConfigMapper configMapper,
                                   MachinePayeeBillMapper billMapper,
                                   CabinetOrderMapper orderMapper,
                                   AdminAuditService auditService,
                                   MerchantScopeService merchantScopeService) {
        this.configMapper = configMapper;
        this.billMapper = billMapper;
        this.orderMapper = orderMapper;
        this.auditService = auditService;
        this.merchantScopeService = merchantScopeService;
    }

    /** 出某账期（上月）机器账单，返回生成笔数。唯一键幂等，重复执行只补缺失项。 */
    public int generateMonthly(YearMonth month) {
        List<MachinePositionConfig> configs = configMapper.selectList(
                Wrappers.<MachinePositionConfig>lambdaQuery()
                        .eq(MachinePositionConfig::getEnabled, 1));
        String billMonth = month.toString();
        int created = 0;
        for (MachinePositionConfig cfg : configs) {
            created += upsertElectricBill(cfg, billMonth);
            created += upsertShareBill(cfg, month, billMonth);
        }
        log.info("machine payee bills generated month={} count={}", billMonth, created);
        return created;
    }

    private int upsertElectricBill(MachinePositionConfig cfg, String billMonth) {
        if (!"FIXED".equalsIgnoreCase(cfg.getElectricType()) || nvl(cfg.getElectricAmountCents()) <= 0) {
            return 0;
        }
        if (exists(cfg.getDeviceId(), billMonth, "ELECTRIC")) {
            return 0;
        }
        billMapper.insert(bill(cfg.getDeviceId(), billMonth, "ELECTRIC",
                cfg.getElectricAmountCents(), null, cfg));
        return 1;
    }

    private int upsertShareBill(MachinePositionConfig cfg, YearMonth month, String billMonth) {
        int bps = cfg.getShareBps() == null ? 0 : cfg.getShareBps();
        if (bps <= 0) {
            return 0;
        }
        if (exists(cfg.getDeviceId(), billMonth, "SHARE")) {
            return 0;
        }
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        Instant from = month.atDay(1).atStartOfDay(zone).toInstant();
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
        long gmv = orderMapper.findByDeviceIdAndCreatedAtBetween(cfg.getDeviceId(), from, to)
                .stream()
                .filter(o -> o.getStatus() != null && PAID_STATUSES.contains(o.getStatus().toUpperCase()))
                .mapToLong(CabinetOrder::getTotalAmountCents)
                .sum();
        long share = gmv * bps / 10_000L;
        if (share <= 0) {
            return 0;
        }
        billMapper.insert(bill(cfg.getDeviceId(), billMonth, "SHARE", share, gmv, cfg));
        return 1;
    }

    private boolean exists(String deviceId, String billMonth, String feeType) {
        Long c = billMapper.selectCount(Wrappers.<MachinePayeeBill>lambdaQuery()
                .eq(MachinePayeeBill::getDeviceId, deviceId)
                .eq(MachinePayeeBill::getBillMonth, billMonth)
                .eq(MachinePayeeBill::getFeeType, feeType));
        return c != null && c > 0;
    }

    private MachinePayeeBill bill(String deviceId, String billMonth, String feeType,
                                  long amountCents, Long gmvCents, MachinePositionConfig cfg) {
        MachinePayeeBill b = new MachinePayeeBill();
        b.setDeviceId(deviceId);
        b.setBillMonth(billMonth);
        b.setFeeType(feeType);
        b.setAmountCents(amountCents);
        b.setGmvCents(gmvCents);
        b.setPayeeName(cfg.getPayeeName());
        b.setPayeePhone(cfg.getPayeePhone());
        b.setStatus("PENDING");
        return b;
    }

    // ==== 运营端查询/处置（2026-10-14 自 controller 下沉：架构门禁禁 Controller 直调 Mapper）====

    /** 账单列表（按账期/状态）。
     *
     * <p>R19-3（2026-11-12）：补数据范围。此前只有 {@code ops:device:list} 就能读到**全平台**
     * 场地方账单（含 GMV 与应付金额）；现按操作者的设备范围过滤，全域账号（admin 角色）不过滤。
     * {@link MerchantScopeService#allowedDeviceIds} 的 null = 全部设备、空集 = 无权限。
     */
    public java.util.List<MachinePayeeBill> listBills(Long operatorId, String billMonth, String status) {
        Set<String> allowed = merchantScopeService.allowedDeviceIds(operatorId);
        if (allowed != null && allowed.isEmpty()) {
            return List.of();
        }
        var query = Wrappers.<MachinePayeeBill>lambdaQuery()
                .eq(billMonth != null && !billMonth.isBlank(), MachinePayeeBill::getBillMonth, billMonth)
                .eq(status != null && !status.isBlank(), MachinePayeeBill::getStatus, status)
                .orderByDesc(MachinePayeeBill::getBillId);
        if (allowed != null) {
            query.in(MachinePayeeBill::getDeviceId, allowed);
        }
        return billMapper.selectList(query);
    }

    /**
     * 标记结清（线下收款后）。MA-091：资金处置动作补操作者归因。
     *
     * <p>R16-MA-175：补 @Transactional + FOR UPDATE 行锁 + 状态幂等闸——原实现
     * 「selectById→setStatus→updateById」读后写无锁，①已 SETTLED 的账单重复点结清会
     * 重复落审计行；②updateById 与 appendLog 各自独立提交，半程失败出现「结清但无审计/
     * 有审计未结清」的不一致。</p>
     */
    @Transactional
    public void settle(Long operatorId, Long billId) {
        MachinePayeeBill bill = billMapper.findByIdForUpdateRaw(billId);
        if (bill == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "账单不存在");
        }
        // R19-3：结清是资金处置动作，必须限定在操作者设备范围内
        merchantScopeService.requireDeviceAccess(operatorId, bill.getDeviceId());
        if ("SETTLED".equals(bill.getStatus())) {
            return; // 幂等：已结清不重复落审计（R16-MA-175）
        }
        bill.setStatus("SETTLED");
        bill.setSettledAt(Instant.now());
        billMapper.updateById(bill);
        auditService.appendLog(operatorId, "MACHINE_PAYEE_BILL_SETTLE", "MACHINE_PAYEE_BILL",
                String.valueOf(billId), "billMonth=" + bill.getBillMonth());
    }

    /** 点位费用配置（按柜）。R19-3：读配置同样按设备范围鉴权（含收款人姓名/电话）。 */
    public MachinePositionConfig config(Long operatorId, String deviceId) {
        merchantScopeService.requireDeviceAccess(operatorId, deviceId);
        return configMapper.selectById(deviceId);
    }

    /**
     * 保存点位费用配置（费用项随时可改，只影响后续账期）。
     *
     * <p>MA-090：这份配置按柜<b>长期生效</b>，出账时按 {@code gmv * shareBps / 10000} 直接生成
     * 应付账单——裸实体直落曾允许 shareBps&gt;10000（5 倍 GMV 分成）与 enabled 垃圾值
     * （{@code generateMonthly} 只筛 {@code enabled = 1}，写 2 就等于整柜从出账面消失）。
     * 同仓三处先例都有闸（SiteRentSplitService 400 / SiteRentBillService 钳位 / FundBillService 回落），
     * 本处统一到 {@link CommissionParamBounds}。
     *
     * <p>刻意<b>只校验非 null 字段</b>：MyBatis-Plus 的 {@code updateById} 跳过 null
     * （见 lessons #384），PUT 省略字段语义是「保持原值」，把 null 当 0 会静默清配置。
     */
    @Transactional
    public void saveConfig(Long operatorId, String deviceId, MachinePositionConfig cfg) {
        // R19-3：本接口决定下期出账金额（shareBps × GMV），此前只校验 ops:device:edit、
        // 不收 operatorId、无数据范围 ⇒ 绑定商户 A 的运营可把**他人柜机**的分成改成 0。
        merchantScopeService.requireDeviceAccess(operatorId, deviceId);
        // R16-MA-175：行锁串行化「查无→insert / 有→update」的读后写竞态——双运营并发保存
        // 同一新柜时双 insert 撞 deviceId 主键（一边 500），并发更新则丢失更新。
        // 配置喂 generateMonthly 出账金额（shareBps），丢失更新会改后续账期账单。
        configMapper.findByIdForUpdateRaw(deviceId);
        cfg.setDeviceId(deviceId);
        if (cfg.getShareBps() != null) {
            cfg.setShareBps(CommissionParamBounds.requireBps("shareBps", cfg.getShareBps()));
        }
        if (cfg.getElectricAmountCents() != null) {
            long electric = cfg.getElectricAmountCents();
            if (electric < 0 || electric > ELECTRIC_AMOUNT_MAX_CENTS) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "electricAmountCents 须在 0.." + ELECTRIC_AMOUNT_MAX_CENTS + " 分/月");
            }
        }
        if (cfg.getElectricType() != null && !cfg.getElectricType().isBlank()) {
            String type = cfg.getElectricType().trim().toUpperCase(java.util.Locale.ROOT);
            if (!ELECTRIC_TYPES.contains(type)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "electricType 只能是 NONE/FIXED");
            }
            cfg.setElectricType(type);
        }
        if (cfg.getEnabled() != null && cfg.getEnabled() != 0 && cfg.getEnabled() != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "enabled 只能是 0/1");
        }
        if (cfg.getPayeeName() == null || cfg.getPayeeName().isBlank()) {
            // 列本身 NOT NULL（V356），缺它只会撞成笼统 409 —— 提前 400 指名道姓
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "payeeName 必填（场地方收款人）");
        }
        cfg.setPayeeName(requireMaxLength("payeeName", cfg.getPayeeName().trim(), PAYEE_NAME_MAX));
        cfg.setPayeePhone(requireMaxLength("payeePhone", cfg.getPayeePhone(), PAYEE_PHONE_MAX));
        cfg.setWelfareInfo(requireMaxLength("welfareInfo", cfg.getWelfareInfo(), WELFARE_INFO_MAX));
        cfg.setUpdatedAt(Instant.now());
        if (configMapper.selectById(deviceId) == null) {
            configMapper.insert(cfg);
        } else {
            configMapper.updateById(cfg);
        }
    }

    /** 超长值撞列宽只会得到全局兜底的泛化 409（22001），这里按目标列宽给出 400+字段名。 */
    private static String requireMaxLength(String field, String value, int max) {
        if (value != null && value.length() > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " 超长（最多 " + max + " 字符）");
        }
        return value;
    }

    private long nvl(Long v) {
        return v == null ? 0L : v;
    }
}
