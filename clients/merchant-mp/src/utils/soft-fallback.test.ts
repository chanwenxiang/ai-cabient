import { describe, expect, it, vi, beforeEach } from 'vitest';
import { softFallback } from './soft-fallback';

vi.mock('@/utils/notify', () => ({
  showError: vi.fn(),
  showSuccess: vi.fn()
}));

import { showError } from '@/utils/notify';

describe('merchant softFallback', () => {
  beforeEach(() => {
    vi.mocked(showError).mockClear();
  });

  it('成功时不 toast', async () => {
    await expect(softFallback(Promise.resolve([1]), [], '柜机列表')).resolves.toEqual([1]);
    expect(showError).not.toHaveBeenCalled();
  });

  it('失败时 toast 并回退', async () => {
    await expect(softFallback(Promise.reject(new Error('超时')), [], '柜机列表')).resolves.toEqual(
      []
    );
    expect(showError).toHaveBeenCalledWith('超时');
  });

  it('401 不 toast', async () => {
    const err = Object.assign(new Error('登录已失效'), { status: 401, code: 'UNAUTHORIZED' });
    await expect(softFallback(Promise.reject(err), null, '柜机列表')).resolves.toBeNull();
    expect(showError).not.toHaveBeenCalled();
  });
});
