package com.aicabinet.trade.service;

import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.WriteOffDto;
import com.aicabinet.trade.domain.InventoryWriteOff;
import com.aicabinet.trade.mapper.InventoryWriteOffMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * V311 配套：**待索赔台账**（缺口 #4 方案 C）。
 *
 * <p>🔴 <b>为什么是台账而不是「自动冲减供应商应付」</b>：
 * 盘亏**不等于**供应商赔 —— 过期报损、搬运破损、用户拿走，责任方各不相同。
 * 在责任归属没认定完之前自动冲供应商应付，**本身就是错的**。
 *
 * <p>而「精确冲减」在业务上也不成立：用户已确认**同一 SKU 来自多个供应商**
 * ⇒ 一个批次可能横跨多张采购单 ⇒无法确定该冲减哪一家。
 * （老系统 `ego-automat` 更是压根没有采购模块 —— 它的供货商是
 * **按手机号**记的 `user_contect_mobile`，没有「SKU→供应商」概念。）
 *
 * <p>⇒ 本台账是**方案 C**：先把「谁该赔、赔多少」记清楚，
 * **等追偿实际发生再走支付流程**，不预先动应付账。
 */
@Service
public class WriteOffClaimLedgerService {

    private final InventoryWriteOffMapper writeOffMapper;

    public WriteOffClaimLedgerService(InventoryWriteOffMapper writeOffMapper) {
        this.writeOffMapper = writeOffMapper;
    }

    /**
     * 待索赔台账：有责任方且索赔额 > 0 的报损记录。
     *
     * <p>🔴 筛选条件为什么是「责任方非空 **且** 索赔额 > 0」：
     * 只有这两项都填了，才构成一笔**可追偿的**主张。
     * 缺任一项的记录仍留在 {@code inventory_write_off} 里（可查、可补），
     * 但不该出现在「要向人追钱」的清单里 ——
     * 混进来会让清单虚高、真正要追的钱被淹没。
     *
     * @param party 责任方过滤（null = 全部）
     * @param from/to 日期区间（含端点），null =不限
     */
    @Transactional(readOnly = true)
    public PageResult<WriteOffDto> listClaims(String party, LocalDate from, LocalDate to, int page, int size) {
        var q = Wrappers.<InventoryWriteOff>lambdaQuery()
                .isNotNull(InventoryWriteOff::getResponsibleParty)
                .isNotNull(InventoryWriteOff::getClaimAmountCents)
                .gt(InventoryWriteOff::getClaimAmountCents, 0L)
                .eq(party != null && !party.isBlank(), InventoryWriteOff::getResponsibleParty, party)
                .ge(from != null, InventoryWriteOff::getCreatedAt, from == null ? null : from.atStartOfDay(java.time.ZoneOffset.UTC).toInstant())
                .le(to != null, InventoryWriteOff::getCreatedAt, to == null ? null : to.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant())
                .orderByDesc(InventoryWriteOff::getCreatedAt);
        Page<InventoryWriteOff> p = writeOffMapper.selectPage(new Page<>(page, size), q);
        List<WriteOffDto> rows = p.getRecords().stream().map(this::toDto).toList();
        // ⚠️ 两处坑：
        // 1) PageResult 字段顺序是 (items, page, size, total)，不是 (items, total, ...)
        // 2) page/size 传**方法入参的 int**（与本仓其他 Service 一致）——
        //    MyBatis-Plus 的 getCurrent()/getSize() 返回 **long**，直接传会编译不过。
        return new PageResult<>(rows, page, size, p.getTotal());
    }

    /**
     * 台账汇总：按责任方聚合索赔额。
     *
     * <p>🔴 <b>金额求和交给 SQL</b>：跨 90 天 × 多责任方行数上千，
     * 在 Java 里求和会引入 long 溢出与「部分行丢失」的静默风险。
     */
    @Transactional(readOnly = true)
    public List<java.util.Map<String, Object>> sumByParty(LocalDate from, LocalDate to) {
        return writeOffMapper.selectMaps(Wrappers.<InventoryWriteOff>query()
                .select("responsible_party",
                        "SUM(claim_amount_cents) AS claim_cents",
                        "COUNT(*) AS claim_count")
                .isNotNull("responsible_party")
                .isNotNull("claim_amount_cents")
                .gt("claim_amount_cents", 0L)
                .ge(from != null, "created_at", from == null ? null : from.atStartOfDay(java.time.ZoneOffset.UTC).toInstant())
                .le(to != null, "created_at", to == null ? null : to.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant())
                .groupBy("responsible_party")
                .orderByAsc("responsible_party"));
    }

    private WriteOffDto toDto(InventoryWriteOff e) {
        return new WriteOffDto(
                e.getWriteOffId(),
                e.getDeviceId(),
                e.getWarehouseId(),
                e.getSkuId(),
                e.getBatchNo(),
                e.getQuantity(),
                e.getReason(),
                e.getReasonCategory(),
                e.getResponsibleParty(),
                e.getClaimNo(),
                e.getClaimAmountCents(),
                e.getCostCents(),
                e.getOperatorId(),
                e.getCreatedAt());
    }
}
