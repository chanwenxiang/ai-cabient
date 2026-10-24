import { describe, expect, it } from 'vitest';
import type { ReplenishDraftLine } from './self-replenish-draft';
import {
  applyAdjustDraftQty,
  applyToggleDraftLine,
  buildSelfReplenishBody,
  canStartSelfReplenish,
  newSelfReplenishRequestNo,
  nextRequestNoAfterSubmit,
  requestActionErrorMessage,
  selectedReplenishLines
} from './self-replenish-submit';

function line(partial: Partial<ReplenishDraftLine>): ReplenishDraftLine {
  return {
    skuId: 'S1',
    skuName: '可乐',
    currentQty: 0,
    capacity: 10,
    suggestQty: 2,
    soldQty7d: 0,
    suggestReason: '',
    qty: 0,
    selected: false,
    ...partial
  };
}

describe('self-replenish-submit', () => {
  it('选中行 → 提交明细', () => {
    expect(
      selectedReplenishLines([
        line({ selected: true, qty: 3 }),
        line({ skuId: 'S2', selected: true, qty: 0 }),
        line({ skuId: 'S3', selected: false, qty: 5 })
      ])
    ).toEqual([{ skuId: 'S1', qty: 3 }]);
  });

  it('提交 body 与门闩', () => {
    expect(
      buildSelfReplenishBody({
        deviceId: 'D1',
        notes: '  ',
        lines: [{ skuId: 'S1', qty: 1 }],
        clientRequestNo: 'self-abc-00000001'
      })
    ).toEqual({
      deviceId: 'D1',
      notes: undefined,
      lines: [{ skuId: 'S1', qty: 1 }],
      clientRequestNo: 'self-abc-00000001'
    });
    expect(
      buildSelfReplenishBody({
        deviceId: 'D1',
        notes: ' 优先可乐 ',
        lines: [],
        clientRequestNo: 'self-abc-00000002'
      }).notes
    ).toBe('优先可乐');
    expect(canStartSelfReplenish({ canSubmit: true, submitting: false, lineCount: 1 })).toBe('ok');
    expect(canStartSelfReplenish({ canSubmit: true, submitting: false, lineCount: 0 })).toBe(
      'no_lines'
    );
    expect(canStartSelfReplenish({ canSubmit: false, submitting: true, lineCount: 1 })).toBe(
      'blocked'
    );
  });

  it('切换/调数量', () => {
    const a = line({ suggestQty: 4 });
    expect(applyToggleDraftLine(a)).toBeNull();
    expect(a.selected).toBe(true);
    expect(a.qty).toBe(4);
    const b = line({ suggestQty: 0 });
    expect(applyToggleDraftLine(b)).toBe('请填写补货数量');
    applyAdjustDraftQty(a, -10);
    expect(a.qty).toBe(0);
    expect(a.selected).toBe(false);
    expect(requestActionErrorMessage(new Error('x'), 'f')).toBe('x');
  });

  it('幂等键：生成 self- 前缀、随机尾确定性注入', () => {
    let seq = 0;
    const rand = () => (seq === 0 ? 0 : 0.5);
    const no1 = newSelfReplenishRequestNo(1730000000000, rand);
    expect(no1.startsWith('self-')).toBe(true);
    expect(no1.endsWith('00000000')).toBe(true);
    // 同参数确定性：同 rand ⇒ 同键；不同 rand ⇒ 不同尾
    seq = 1;
    const no2 = newSelfReplenishRequestNo(1730000000000, rand);
    expect(no2).not.toBe(no1);
    expect(newSelfReplenishRequestNo(1730000000000, rand)).toBe(no2);
    // 不同时刻 ⇒ 不同键
    seq = 0;
    expect(newSelfReplenishRequestNo(1730000000001, rand)).not.toBe(no1);
    // 长度远小于后端 64 上限
    expect(no1.length).toBeLessThan(64);
  });

  it('幂等键重试语义：仅传输类错误保留键（重放），成功/业务失败清空（新意图）', () => {
    expect(nextRequestNoAfterSubmit('self-abc-00000001', 'transport_error')).toBe(
      'self-abc-00000001'
    );
    expect(nextRequestNoAfterSubmit('self-abc-00000001', 'success')).toBe('');
    expect(nextRequestNoAfterSubmit('self-abc-00000001', 'business_error')).toBe('');
  });
});
