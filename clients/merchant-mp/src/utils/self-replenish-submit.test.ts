import { describe, expect, it } from 'vitest';
import type { ReplenishDraftLine } from './self-replenish-draft';
import {
  applyAdjustDraftQty,
  applyToggleDraftLine,
  buildSelfReplenishBody,
  canStartSelfReplenish,
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
        lines: [{ skuId: 'S1', qty: 1 }]
      })
    ).toEqual({
      deviceId: 'D1',
      notes: undefined,
      lines: [{ skuId: 'S1', qty: 1 }]
    });
    expect(buildSelfReplenishBody({ deviceId: 'D1', notes: ' 优先可乐 ', lines: [] }).notes).toBe(
      '优先可乐'
    );
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
});
