import { describe, expect, it } from 'vitest';
import { consumerDisputeReviewCopy } from './dispute-copy';

/**
 * Phase 2.1（2026-11-03）C 端降噪钉测：后端 reason 会拼「｜设备原文：…」运维尾巴，
 * 消费端卡片必须只显示人话段（完整记录在争议详情/运营后台可见）。
 */
describe('consumerDisputeReviewCopy 设备原文剥离', () => {
  it('剥掉「｜设备原文：」及之后的运维细节（非 timeout 类 reason 兜底原样返回场景）', () => {
    const copy = consumerDisputeReviewCopy({
      reason:
        '识别上报的 classId 无法解析到我方 SKU（1 条）｜设备原文：再次确认｜开门后回调未确认:cam=1,reason=settle_budget;原图证据不足，保留复核',
      status: 'OPEN',
      billedAmountCents: 243,
      refundedAmountCents: 0,
      consumerReviewTitle: null,
      consumerReviewDetail: null
    } as never);
    expect(copy.detail).toBe('识别上报的 classId 无法解析到我方 SKU（1 条）');
    expect(copy.detail).not.toContain('设备原文');
    expect(copy.detail).not.toContain('cam=1');
  });

  it('timeout 类 reason 统一为固定用户文案（既有行为顺带钉住）', () => {
    const copy = consumerDisputeReviewCopy({
      reason:
        '识别超时（设备上报异常订单），已转人工审核，本次暂未扣款｜设备原文：cam=1',
      status: 'OPEN',
      billedAmountCents: 150,
      refundedAmountCents: 0,
      consumerReviewTitle: null,
      consumerReviewDetail: null
    } as never);
    expect(copy.detail).toBe('识别超时，本次暂未扣款，工作人员正在核对账单。');
    expect(copy.detail).not.toContain('设备原文');
  });

  it('后端派生字段优先级不受剥离影响', () => {
    const copy = consumerDisputeReviewCopy({
      reason: 'whatever',
      status: 'OPEN',
      billedAmountCents: 100,
      refundedAmountCents: 0,
      consumerReviewTitle: '账单审核中',
      consumerReviewDetail: '工作人员正在核对，完成后会通知你'
    } as never);
    expect(copy.title).toBe('账单审核中');
    expect(copy.detail).toBe('工作人员正在核对，完成后会通知你');
  });
});
