import { describe, expect, it } from 'vitest';
import { resolveSessionBill } from './session-bill';

/**
 * MA-321：查询成功但订单/金额缺失 ⇒ 金额未知（null），不得兜成 0 说「本次未消费」；
 * 只有明确的 0 才是真 0。
 */
describe('resolveSessionBill', () => {
  it('订单为 null/undefined ⇒ 金额未知（修前 `?? 0` 会误报 0 元）', () => {
    expect(resolveSessionBill(null).totalCents).toBeNull();
    expect(resolveSessionBill(undefined).totalCents).toBeNull();
  });

  it('订单存在但金额字段缺失/空串 ⇒ 金额未知', () => {
    expect(resolveSessionBill({}).totalCents).toBeNull();
    expect(resolveSessionBill({ totalAmountCents: null }).totalCents).toBeNull();
    expect(resolveSessionBill({ totalAmountCents: '' }).totalCents).toBeNull();
    expect(resolveSessionBill({ totalAmountCents: 'not-a-number' }).totalCents).toBeNull();
  });

  it('明确的 0 元 ⇒ 0（不是未知）', () => {
    expect(resolveSessionBill({ totalAmountCents: 0 }).totalCents).toBe(0);
    expect(resolveSessionBill({ totalAmountCents: '0' }).totalCents).toBe(0);
  });

  it('正常金额与订单状态原样带出', () => {
    expect(resolveSessionBill({ totalAmountCents: 1234, status: 'PAID' })).toEqual({
      totalCents: 1234,
      status: 'PAID'
    });
    expect(resolveSessionBill({ totalAmountCents: '99', status: 'PENDING' })).toEqual({
      totalCents: 99,
      status: 'PENDING'
    });
  });
});
