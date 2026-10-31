import { describe, expect, it } from 'vitest';
import { displayBizNo } from '@aicabinet/shared-uni/format';

/**
 * 盘点单号 2026-10-28 起改为可读格式 `PDyyyyMMdd-NNN`。
 * 回归点：displayBizNo 的「剥前缀」规则会把 `PD20261028-001` 剥成纯数字 `20261028001`，
 * 后台仓库盘点列表就会显示成一串没有意义的数字，所以必须原样放行。
 */
describe('displayBizNo / 盘点单可读单号', () => {
  it('PDyyyyMMdd-NNN 原样展示（不许被剥成 20261028001）', () => {
    expect(displayBizNo('PD20261028-001')).toBe('PD20261028-001');
  });

  it('流水超过三位也原样展示', () => {
    expect(displayBizNo('PD20261028-1024')).toBe('PD20261028-1024');
  });

  it('纯数字业务号仍原样（新规则不误伤旧单号）', () => {
    expect(displayBizNo('1793190291682519060459')).toBe('1793190291682519060459');
  });
});

/**
 * MA-006（2026-10-31 R2）：`displayBizNo` 末段曾兜底「取全部数字」，
 * 于是列表显示的单号既搜不回来（后台按显示值 q=20001 → 0 条），
 * 又与同行「复制单号」给出的真值不一致。修复＝走到末段的可读编号一律原样。
 */
describe('displayBizNo / 混合字母编号不得被剥成假短号（MA-006）', () => {
  it('E2E-TAIL-0001 原样（修复前显示成 20001，按 20001 搜不到）', () => {
    expect(displayBizNo('E2E-TAIL-0001')).toBe('E2E-TAIL-0001');
  });

  it('AUDIT-R1-0001 原样（修复前剥成 10001，与 SKU 编号撞形）', () => {
    expect(displayBizNo('AUDIT-R1-0001')).toBe('AUDIT-R1-0001');
  });

  it('既有约定不动：台账/流水前缀剥完已是纯数字的，仍显示数字段', () => {
    expect(displayBizNo('PO-12')).toBe('12');
  });
});
