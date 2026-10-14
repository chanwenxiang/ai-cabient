import { describe, expect, it } from 'vitest';
import {
  buildUpdateLineBody,
  diffDirectionLabel,
  diffReasonLabel,
  diffReasonOptionsFor,
  isLineEditable,
  stocktakeLineStateClass,
  stocktakeModeLabel,
  stocktakeStatusLabel
} from './warehouse-stocktake';

describe('diffReasonOptionsFor', () => {
  it('盘亏行只出盘亏类 + 其他', () => {
    const options = diffReasonOptionsFor(-3);
    expect(options.map((o) => o.value)).toEqual([
      'NORMAL_SHRINKAGE',
      'MISENTRY',
      'HANDLING_DAMAGE',
      'SUSPECTED_THEFT',
      'OTHER'
    ]);
  });

  it('盘盈行只出盘盈类 + 其他（方向填反在 UI 不可选，服务端 400 兜底）', () => {
    const options = diffReasonOptionsFor(2);
    expect(options.map((o) => o.value)).toEqual([
      'MISENTRY_GAIN',
      'UNRECORDED_RETURN',
      'SKU_MIXUP',
      'OTHER'
    ]);
  });

  it('差异为 0（一致）也给出盘盈侧集合（不可达路径兜底）', () => {
    expect(diffReasonOptionsFor(0).length).toBe(4);
  });
});

describe('labels', () => {
  it('diffReasonLabel：null = 未分类（与后端语义一致，非 OTHER）', () => {
    expect(diffReasonLabel(null)).toBe('未分类');
    expect(diffReasonLabel('')).toBe('未分类');
    expect(diffReasonLabel('NORMAL_SHRINKAGE')).toBe('正常损耗');
    expect(diffReasonLabel('UNKNOWN_X')).toBe('UNKNOWN_X');
  });

  it('diffDirectionLabel', () => {
    expect(diffDirectionLabel(-3)).toBe('盘亏');
    expect(diffDirectionLabel(2)).toBe('盘盈');
    expect(diffDirectionLabel(0)).toBe('一致');
    expect(diffDirectionLabel(null)).toBe('一致');
  });

  it('status/mode 文案', () => {
    expect(stocktakeStatusLabel('IN_PROGRESS')).toBe('录入中');
    expect(stocktakeStatusLabel('WEIRD')).toBe('WEIRD');
    expect(stocktakeModeLabel('BLIND')).toBe('盲盘');
    expect(stocktakeModeLabel('OPEN')).toBe('明盘');
    expect(stocktakeLineStateClass('DIFF')).toBe('diff');
    expect(stocktakeLineStateClass(undefined)).toBe('pending');
  });
});

describe('buildUpdateLineBody', () => {
  it('负数/非整数拒绝', () => {
    expect(buildUpdateLineBody(-1, '', null)).toMatchObject({ ok: false });
    expect(buildUpdateLineBody(1.5, '', null)).toMatchObject({ ok: false });
    expect(buildUpdateLineBody(null, '', null)).toMatchObject({ ok: false });
  });

  it('空备注/空原因归一为 undefined（= 未分类不传）', () => {
    const r = buildUpdateLineBody(7, '  ', '');
    expect(r).toEqual({
      ok: true,
      body: { countedQty: 7, notes: undefined, diffReason: undefined }
    });
  });

  it('合法值透传且备注去空白', () => {
    const r = buildUpdateLineBody(0, ' 摇机 2 件 ', 'NORMAL_SHRINKAGE');
    expect(r).toEqual({
      ok: true,
      body: { countedQty: 0, notes: '摇机 2 件', diffReason: 'NORMAL_SHRINKAGE' }
    });
  });

  it('超长备注拒绝', () => {
    expect(buildUpdateLineBody(1, 'x'.repeat(81), null)).toMatchObject({ ok: false });
  });
});

describe('isLineEditable', () => {
  it('仅 DRAFT/IN_PROGRESS 且未过账的行可编辑', () => {
    expect(isLineEditable('DRAFT', 'PENDING')).toBe(true);
    expect(isLineEditable('IN_PROGRESS', 'DIFF')).toBe(true);
    expect(isLineEditable('COMPLETED', 'PENDING')).toBe(false);
    expect(isLineEditable('ADJUSTED', 'MATCHED')).toBe(false);
    expect(isLineEditable('CANCELLED', 'PENDING')).toBe(false);
    expect(isLineEditable('IN_PROGRESS', 'ADJUSTED')).toBe(false);
  });
});
