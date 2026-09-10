import { describe, expect, it } from 'vitest';
import { permissionsAfterSoftFail } from './rbac-cache-policy';

describe('permissionsAfterSoftFail', () => {
  it('未服务端同步时失败则清空，不沿用可能被篡改的缓存', () => {
    expect(permissionsAfterSoftFail(false, ['ops:order:list'])).toEqual([]);
  });

  it('已服务端同步后软失败则保留内存权限', () => {
    expect(permissionsAfterSoftFail(true, ['ops:order:list'])).toEqual(['ops:order:list']);
  });
});
