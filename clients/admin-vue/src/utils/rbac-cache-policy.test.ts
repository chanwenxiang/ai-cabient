import { describe, expect, it } from 'vitest';
import { isNavMenuActiveFor, permissionsAfterSoftFail } from './rbac-cache-policy';

describe('permissionsAfterSoftFail', () => {
  it('未服务端同步时失败则清空，不沿用可能被篡改的缓存', () => {
    expect(permissionsAfterSoftFail(false, ['ops:order:list'])).toEqual([]);
  });

  it('已服务端同步后软失败则保留内存权限', () => {
    expect(permissionsAfterSoftFail(true, ['ops:order:list'])).toEqual(['ops:order:list']);
  });
});

describe('isNavMenuActiveFor', () => {
  it('无 perm 视为不受 ACTIVE 菜单约束', () => {
    expect(isNavMenuActiveFor(null, false, [])).toBe(true);
    expect(isNavMenuActiveFor(undefined, true, [])).toBe(true);
  });

  it('ACTIVE 列表未加载时 fail-closed', () => {
    expect(isNavMenuActiveFor('ops:order:list', false, ['ops:order:list'])).toBe(false);
  });

  it('已加载后仅 ACTIVE 列表内放行', () => {
    expect(isNavMenuActiveFor('ops:order:list', true, ['ops:order:list'])).toBe(true);
    expect(isNavMenuActiveFor('ops:order:list', true, ['ops:device:list'])).toBe(false);
  });
});
