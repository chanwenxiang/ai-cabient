import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('element-plus', () => ({
  ElMessage: { warning: vi.fn() }
}));

import { ElMessage } from 'element-plus';
import { createSoftFailCollector, softFallback } from './soft-fallback';

describe('softFallback', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('成功时原样返回且不 toast', async () => {
    await expect(softFallback(Promise.resolve(42), 0, '测试')).resolves.toBe(42);
    expect(ElMessage.warning).not.toHaveBeenCalled();
  });

  it('失败时回退并 warning', async () => {
    await expect(softFallback(Promise.reject(new Error('boom')), [], '设备列表')).resolves.toEqual(
      []
    );
    expect(ElMessage.warning).toHaveBeenCalledWith('设备列表加载失败：boom');
  });

  it('会话失效不 toast', async () => {
    await expect(
      softFallback(Promise.reject(new Error('请先登录')), null, '统计')
    ).resolves.toBeNull();
    expect(ElMessage.warning).not.toHaveBeenCalled();
  });
});

describe('createSoftFailCollector', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('多路失败汇总一条 warning', async () => {
    const { soft, flush } = createSoftFailCollector();
    const [a, b] = await Promise.all([
      soft(Promise.reject(new Error('a')), null, '统计'),
      soft(Promise.reject(new Error('b')), [], '地图')
    ]);
    expect(a).toBeNull();
    expect(b).toEqual([]);
    flush();
    expect(ElMessage.warning).toHaveBeenCalledTimes(1);
    expect(ElMessage.warning).toHaveBeenCalledWith('部分数据加载失败：统计、地图');
  });
});
