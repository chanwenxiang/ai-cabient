/**
 * MA-321（2026-11-17 对抗验证轮）：会话结束提示的金额解析。
 *
 * <p>修前形态（`pages/index/index.vue` 的 `finishSession`）：
 * `totalCents = order?.totalAmountCents ?? 0` —— 查询**成功**但订单尚未落单
 * （接口返回 null/空对象）或金额字段缺失时，被兜成 0，随后 `showBillToast(0)` 弹
 * 「本次未消费」，与资金事实相反（旁边注释自己都写着「null = 金额未知，不能当 0 元说本次未消费」）。</p>
 *
 * <p>这里把「未知」与「真实 0 元」分开：缺字段 / 空串 / 非有限数 ⇒ `null`
 * （toast 走「账单已生成，正在打开…」），只有明确解析出 0 才返回 0。</p>
 */

export type SessionBillSource =
  { totalAmountCents?: number | string | null; status?: string | null } | null | undefined;

export interface SessionBill {
  /** null = 金额未知（不能当作 0 元）。 */
  totalCents: number | null;
  status: string | null;
}

export function resolveSessionBill(source: SessionBillSource): SessionBill {
  const raw = source?.totalAmountCents;
  const parsed = raw == null || raw === '' ? Number.NaN : Number(raw);
  return {
    totalCents: Number.isFinite(parsed) ? parsed : null,
    status: source?.status ?? null
  };
}
