package com.aicabinet.common.dto;

/**
 * V309：标记场地租金账单「已付」时的留痕请求体。
 *
 * <p>🔴 <b>为什么加这个</b>：原接口 {@code POST /site-rent-bills/{billId}/pay} <b>无请求体</b>，
 * 运营点一下就把账单标成 PAID —— 谁付的、凭什么付的、凭证在哪，**系统里一概没有**。
 * 场地租金是对外付款，属于必须可审计的费用；一个不可复核的「已付」状态在财务审计里等于「这笔钱说不清」。
 *
 * <p><b>三个字段都非必填</b>（凭证号可为 null）：为了兼容<b>存量前端与既有调用方</b>，
 * 不强制要求补录。但<b>强烈建议前端把它做成可填项</b> —— 一旦填了就进入审计链。
 *
 * @param voucherNo 付款凭证号（银行流水号/发票号/线下支付单号）
 * @param remark 付款备注（如「XX银行 2026-09 月租，流水号 xxxx」）
 */
public record MarkSiteRentBillPaidRequest(
        String voucherNo,
        String remark
) {}