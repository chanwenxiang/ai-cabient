import { describe, expect, it } from 'vitest';
import {
  canLifecycleAction,
  lifecycleDisabledReason,
  normalizeLifecycleStatus
} from './device-lifecycle-guards';

describe('device-lifecycle-guards', () => {
  it('normalize 去空白并大写', () => {
    expect(normalizeLifecycleStatus('  idle ')).toBe('IDLE');
    expect(normalizeLifecycleStatus(undefined)).toBe('');
  });

  it('状态未加载时全部不可点', () => {
    expect(canLifecycleAction('BIND', '', 'M1')).toBe(false);
    expect(canLifecycleAction('DEPLOY', null, 'M1')).toBe(false);
    expect(lifecycleDisabledReason('BIND', '')).toBe('设备状态加载中');
  });

  it('BIND / DEPLOY / UNBIND 与商户绑定联动', () => {
    expect(canLifecycleAction('BIND', 'INBOUND', null)).toBe(true);
    expect(canLifecycleAction('BIND', 'DEPLOYED', 'M1')).toBe(false);
    expect(canLifecycleAction('DEPLOY', 'IDLE', null)).toBe(false);
    expect(canLifecycleAction('DEPLOY', 'IDLE', 'M1')).toBe(true);
    expect(canLifecycleAction('UNBIND', 'DEPLOYED', 'M1')).toBe(true);
    expect(canLifecycleAction('UNBIND', 'IDLE', null)).toBe(false);
    expect(canLifecycleAction('UNBIND', 'IDLE', 'M1')).toBe(true);
  });

  it('RETIRE 除已退役外均可；RETURNING 可 INBOUND', () => {
    expect(canLifecycleAction('RETIRE', 'DEPLOYED')).toBe(true);
    expect(canLifecycleAction('RETIRE', 'RETIRED')).toBe(false);
    expect(canLifecycleAction('INBOUND', 'RETURNING')).toBe(true);
    expect(canLifecycleAction('INBOUND', 'DEPLOYED')).toBe(false);
  });

  it('disabledReason 覆盖投放中换商户 / 未绑商户再投放', () => {
    expect(lifecycleDisabledReason('BIND', 'DEPLOYED', 'M1')).toBe('已投放，请先解绑再换商户');
    expect(lifecycleDisabledReason('DEPLOY', 'IDLE', null)).toBe('请先绑定商户再投放');
    expect(lifecycleDisabledReason('RETIRE', 'RETIRED')).toBe('已退役，不可再操作生命周期');
  });
});
