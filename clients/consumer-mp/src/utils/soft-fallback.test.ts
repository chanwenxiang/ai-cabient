import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { softFallback } from './soft-fallback';

vi.mock('@/utils/notify', () => ({
  showError: vi.fn()
}));

import { showError } from '@/utils/notify';

describe('softFallback', () => {
  beforeEach(() => {
    vi.mocked(showError).mockClear();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('成功时返回原值且不 toast', async () => {
    await expect(softFallback(Promise.resolve(42), 0, '测试')).resolves.toBe(42);
    expect(showError).not.toHaveBeenCalled();
  });

  it('失败时回退并 toast', async () => {
    await expect(
      softFallback(Promise.reject(new Error('网络错误')), [], '优惠券')
    ).resolves.toEqual([]);
    expect(showError).toHaveBeenCalledWith('网络错误');
  });

  it('无 message 时用 label', async () => {
    await expect(softFallback(Promise.reject({}), null, '公开配置')).resolves.toBeNull();
    expect(showError).toHaveBeenCalledWith('公开配置加载失败');
  });

  it('鉴权失败不 toast', async () => {
    const err = Object.assign(new Error('登录已失效'), { status: 401, code: 'UNAUTHORIZED' });
    await expect(softFallback(Promise.reject(err), 0, '优惠券')).resolves.toBe(0);
    expect(showError).not.toHaveBeenCalled();
  });
});
