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
