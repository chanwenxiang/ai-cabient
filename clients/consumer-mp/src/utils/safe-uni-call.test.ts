import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  safeChooseImageWithReason,
  safeMakePhoneCall,
  safeScanCode,
  safeScanCodeWithReason,
  safeSetClipboardData,
  chooseImageErrorMessage,
  scanCodeErrorMessage
} from '@aicabinet/shared-uni/safe-uni-call';

type MutableUni = Record<string, ((...a: never[]) => void) | undefined>;

describe('safe-uni-call（C9 平台守卫）', () => {
  let fakeUni: MutableUni;
  const prevUni = (globalThis as { uni?: unknown }).uni;

  beforeEach(() => {
    fakeUni = {};
    (globalThis as { uni?: unknown }).uni = fakeUni;
  });
  afterEach(() => {
    (globalThis as { uni?: unknown }).uni = prevUni;
  });

  it('API 缺失时拨号/复制/扫码返回降级结果且不抛错', async () => {
    expect(safeMakePhoneCall('13800138000')).toBe(false);
    expect(safeSetClipboardData('x')).toBe(false);
    await expect(safeScanCode()).resolves.toBeNull();
  });

  it('API 存在时拨号/复制透传参数并返回 true', () => {
    const call = vi.fn();
    const clip = vi.fn();
    fakeUni.makePhoneCall = call;
    fakeUni.setClipboardData = clip;
    expect(safeMakePhoneCall('13800138000')).toBe(true);
    expect(call).toHaveBeenCalledWith({ phoneNumber: '13800138000' });
    expect(safeSetClipboardData('hello')).toBe(true);
    expect(clip).toHaveBeenCalledWith(expect.objectContaining({ data: 'hello' }));
  });

  it('复制成功回调触发 onCopied', () => {
    let captured: { success?: () => void } = {};
    fakeUni.setClipboardData = (o: { data: string; success?: () => void }) => {
      captured = o;
    };
    const onCopied = vi.fn();
    expect(safeSetClipboardData('data', onCopied)).toBe(true);
    captured.success?.();
    expect(onCopied).toHaveBeenCalledTimes(1);
  });

  it('扫码成功返回码文本，取消/失败返回 null', async () => {
    fakeUni.scanCode = (o: { success?: (r: { result: string }) => void }) =>
      o.success?.({ result: 'CAB-123' });
    await expect(safeScanCode()).resolves.toBe('CAB-123');
    fakeUni.scanCode = (o: { fail?: (e: unknown) => void }) => o.fail?.({});
    await expect(safeScanCode()).resolves.toBeNull();
  });

  it('选图区分成功、取消、拒权、失败与不支持', async () => {
    fakeUni.chooseImage = (o: {
      count?: number;
      sourceType?: string[];
      success?: (r: { tempFilePaths: string[] }) => void;
      fail?: (e: unknown) => void;
    }) => o.success?.({ tempFilePaths: ['tmp://a'] });
    await expect(safeChooseImageWithReason(2, { sourceType: ['camera'] })).resolves.toEqual({
      paths: ['tmp://a'],
      denied: false,
      status: 'success'
    });

    fakeUni.chooseImage = (o: { fail?: (e: unknown) => void }) =>
      o.fail?.({ errMsg: 'chooseImage:fail cancel' });
    await expect(safeChooseImageWithReason()).resolves.toMatchObject({ status: 'cancelled' });

    fakeUni.chooseImage = (o: { fail?: (e: unknown) => void }) =>
      o.fail?.({ errMsg: 'chooseImage:fail auth deny' });
    const denied = await safeChooseImageWithReason();
    expect(denied).toMatchObject({ status: 'denied', denied: true });
    expect(chooseImageErrorMessage(denied, '拍照')).toContain('权限');

    fakeUni.chooseImage = (o: { fail?: (e: unknown) => void }) => o.fail?.({});
    const failed = await safeChooseImageWithReason();
    expect(failed).toMatchObject({ status: 'failed', denied: false });
    expect(chooseImageErrorMessage(failed)).toContain('重试');

    delete fakeUni.chooseImage;
    await expect(safeChooseImageWithReason()).resolves.toMatchObject({ status: 'unsupported' });
  });

  it('扫码保留拒权/失败原因，兼容入口仍只返回码文本', async () => {
    fakeUni.scanCode = (o: { fail?: (e: unknown) => void }) =>
      o.fail?.({ errMsg: 'scanCode:fail auth deny' });
    const denied = await safeScanCodeWithReason();
    expect(denied.status).toBe('denied');
    expect(scanCodeErrorMessage(denied)).toContain('相机权限');
    await expect(safeScanCode()).resolves.toBeNull();

    fakeUni.scanCode = (o: { success?: (r: { result: string }) => void }) =>
      o.success?.({ result: ' CAB-1 ' });
    await expect(safeScanCodeWithReason()).resolves.toEqual({ result: 'CAB-1', status: 'success' });
  });

  it('API 实现同步抛错时吞掉并降级', () => {
    fakeUni.makePhoneCall = () => {
      throw new Error('not implemented');
    };
    expect(safeMakePhoneCall('x')).toBe(false);
  });
});
