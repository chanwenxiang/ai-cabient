import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { showLongError } from './notify';

/**
 * 审计 2026-10-23 M3：≤20 字走 toast；>20 字必须走 modal 全文（toast 被平台截 19 字）。
 */
const toast = vi.fn();
const modal = vi.fn();

beforeEach(() => {
  vi.stubGlobal('uni', { showToast: toast, showModal: modal });
});
afterEach(() => {
  vi.unstubAllGlobals();
  toast.mockClear();
  modal.mockClear();
});

describe('showLongError', () => {
  it('短文案（≤20 字）⇒ toast，不弹 modal', () => {
    showLongError(new Error('无补货操作权限'), '提交失败');
    expect(toast).toHaveBeenCalledTimes(1);
    expect(toast.mock.calls[0][0].title).toBe('无补货操作权限');
    expect(modal).not.toHaveBeenCalled();
  });

  it('恰 20 字仍是 toast、21 字起必须 modal（边界负向对照）', () => {
    const exactly20 = '一'.repeat(20);
    showLongError(exactly20, 'f');
    expect(toast).toHaveBeenCalledTimes(1);
    expect(modal).not.toHaveBeenCalled();
    showLongError('一'.repeat(21), 'f');
    expect(modal).toHaveBeenCalledTimes(1);
    expect(modal.mock.calls[0][0].content).toBe('一'.repeat(21));
    expect(modal.mock.calls[0][0].showCancel).toBe(false);
  });

  it('长文案 Error 实例 ⇒ modal 取 e.message 全文', () => {
    const long = '该争议已进入平台仲裁阶段，请联系平台运营协助处理，商户侧无法继续操作';
    showLongError(new Error(long), '认领失败');
    expect(toast).not.toHaveBeenCalled();
    expect(modal.mock.calls[0][0].content).toBe(long);
  });

  it('空值/空串 ⇒ 回落 fallback 走 toast', () => {
    showLongError(undefined, '开门失败');
    expect(toast).toHaveBeenCalledTimes(1);
    expect(toast.mock.calls[0][0].title).toBe('开门失败');
    showLongError(new Error(''), '签到失败');
    expect(toast).toHaveBeenCalledTimes(2);
    expect(toast.mock.calls[1][0].title).toBe('签到失败');
  });
});
