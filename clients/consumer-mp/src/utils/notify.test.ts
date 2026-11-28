import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  markSubscribeAck,
  readSubscribeAck,
  requestMerchantSubscribe,
  requestOrderSubscribe,
  showSubscribeFeedback,
  subscribeFeedbackText,
  type SubscribeRequestResult
} from '@aicabinet/shared-uni/notify';

type SubscribeOptions = {
  tmplIds: string[];
  success?: (res: Record<string, string>) => void;
  fail?: (err: unknown) => void;
};

const prevUni = (globalThis as { uni?: unknown }).uni;

function result(status: SubscribeRequestResult['items'][number]['status']): SubscribeRequestResult {
  const summary = status === 'accept' ? 'ok' : status === 'reject' ? 'rejected' : status;
  return { status: summary, items: [{ tmplId: 'tpl-a', status }] };
}

describe('微信订阅逐模板回执', () => {
  let fakeUni: Record<string, unknown>;

  beforeEach(() => {
    fakeUni = { showToast: vi.fn() };
    (globalThis as { uni?: unknown }).uni = fakeUni;
  });

  afterEach(() => {
    (globalThis as { uni?: unknown }).uni = prevUni;
    vi.unstubAllEnvs();
  });

  it('success 中按模板 ID 读取 accept/reject，缺失回执不得伪装成 accept', async () => {
    const request = vi.fn((options: SubscribeOptions) => {
      options.success?.({ 'tpl-a': 'accept', 'tpl-b': 'reject' });
    });
    fakeUni.requestSubscribeMessage = request;

    const received = await requestOrderSubscribe(['tpl-a', 'tpl-b', 'tpl-a', 'tpl-c']);

    expect(request).toHaveBeenCalledWith(
      expect.objectContaining({ tmplIds: ['tpl-a', 'tpl-b', 'tpl-c'] })
    );
    expect(received).toEqual({
      status: 'partial',
      items: [
        { tmplId: 'tpl-a', status: 'accept' },
        { tmplId: 'tpl-b', status: 'reject' },
        { tmplId: 'tpl-c', status: 'failed' }
      ]
    });
  });

  it('商户端请求失败时逐模板返回 failed', async () => {
    fakeUni.requestSubscribeMessage = (options: SubscribeOptions) => options.fail?.({});
    await expect(requestMerchantSubscribe(['tpl-a', 'tpl-b'])).resolves.toEqual({
      status: 'failed',
      items: [
        { tmplId: 'tpl-a', status: 'failed' },
        { tmplId: 'tpl-b', status: 'failed' }
      ]
    });
  });

  it('API 缺失时逐模板 skipped，调用方仍可给出可见反馈', async () => {
    await expect(requestOrderSubscribe(['tpl-a'])).resolves.toEqual({
      status: 'skipped',
      items: [{ tmplId: 'tpl-a', status: 'skipped' }]
    });
    expect(subscribeFeedbackText(result('skipped'))).toContain('环境或模板不可用');
  });

  it('无模板 ID 时返回空 skipped 结果', async () => {
    vi.resetModules();
    vi.stubEnv('VITE_WX_SUBSCRIBE_TMPL_IDS', '');
    const fresh = await import('@aicabinet/shared-uni/notify');
    await expect(fresh.requestOrderSubscribe()).resolves.toEqual({ status: 'skipped', items: [] });
  });

  it('授权回执按模板 ID 持久化，换模板不继承旧 ack', () => {
    const storage = new Map<string, unknown>();
    fakeUni.getStorageSync = (key: string) => storage.get(key);
    fakeUni.setStorageSync = (key: string, value: unknown) => storage.set(key, value);

    expect(readSubscribeAck('tpl-a')).toBe(false);
    markSubscribeAck('tpl-a');
    expect(readSubscribeAck('tpl-a')).toBe(true);
    expect(readSubscribeAck('tpl-b')).toBe(false);
    markSubscribeAck('   ');
    expect(storage.size).toBe(1);
  });

  it('四种终态都有对应反馈，部分同意不冒充全部成功', () => {
    expect(subscribeFeedbackText(result('accept'))).toBe('消息提醒已开启');
    expect(subscribeFeedbackText(result('reject'))).toContain('已拒绝');
    expect(subscribeFeedbackText(result('failed'))).toContain('失败');
    expect(
      subscribeFeedbackText({
        status: 'partial',
        items: [
          { tmplId: 'tpl-a', status: 'accept' },
          { tmplId: 'tpl-b', status: 'reject' }
        ]
      })
    ).toBe('部分授权：同意1项，拒绝1项，跳过0项，失败0项');
  });

  it('混合回执逐类计数，长反馈走完整弹窗而不是 toast 截断', () => {
    const showModal = vi.fn();
    fakeUni.showModal = showModal;
    showSubscribeFeedback({
      status: 'partial',
      items: [
        { tmplId: 'tpl-a', status: 'accept' },
        { tmplId: 'tpl-b', status: 'reject' },
        { tmplId: 'tpl-c', status: 'skipped' },
        { tmplId: 'tpl-d', status: 'failed' }
      ]
    });
    expect(showModal).toHaveBeenCalledWith(
      expect.objectContaining({
        title: '操作未完成',
        content: '部分授权：同意1项，拒绝1项，跳过1项，失败1项'
      })
    );
  });

  it('showSubscribeFeedback 对非 accept 使用错误图标，对全部 accept 使用成功图标', () => {
    const showToast = fakeUni.showToast as ReturnType<typeof vi.fn>;
    showSubscribeFeedback(result('accept'));
    expect(showToast).toHaveBeenLastCalledWith(expect.objectContaining({ icon: 'success' }));
    showSubscribeFeedback(result('reject'));
    expect(showToast).toHaveBeenLastCalledWith(expect.objectContaining({ icon: 'none' }));
  });
});
