import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createAutoRefresher, isOrderTerminal } from '@aicabinet/shared-uni/auto-refresh';

/**
 * 轮询器的纯逻辑判据。
 *
 * 这几条对应「自动刷新」最容易变成反模式的地方：到终态了还在拉、用户切后台还在拉、
 * 慢接口被叠成雪球、弹层开着把用户打断。全都用假定时器验，不需要任何真机/网络。
 */
describe('createAutoRefresher', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('未到终态：按间隔持续触发 load', async () => {
    const load = vi.fn().mockResolvedValue(undefined);
    const r = createAutoRefresher({ intervalMs: 1000, load });

    r.start();
    expect(r.running()).toBe(true);

    await vi.advanceTimersByTimeAsync(3000);
    expect(load).toHaveBeenCalledTimes(3);
    r.dispose();
  });

  it('shouldContinue 变 false ⇒ 立即停表，且这一拍不再 load', async () => {
    let live = true;
    const load = vi.fn().mockResolvedValue(undefined);
    const r = createAutoRefresher({ intervalMs: 1000, load, shouldContinue: () => live });

    r.start();
    await vi.advanceTimersByTimeAsync(1000);
    expect(load).toHaveBeenCalledTimes(1);

    live = false; // 例如订单进了 PAID 终态
    await vi.advanceTimersByTimeAsync(1000);
    expect(load).toHaveBeenCalledTimes(1);
    expect(r.running()).toBe(false);
    r.dispose();
  });

  it('canRefresh 返回 false ⇒ 跳过本次，但不停表（弹层/提交中不该被轮询打断）', async () => {
    let busy = true;
    const load = vi.fn().mockResolvedValue(undefined);
    const r = createAutoRefresher({ intervalMs: 1000, load, canRefresh: () => !busy });

    r.start();
    await vi.advanceTimersByTimeAsync(2000);
    expect(load).not.toHaveBeenCalled();
    expect(r.running()).toBe(true);

    busy = false;
    await vi.advanceTimersByTimeAsync(1000);
    expect(load).toHaveBeenCalledTimes(1);
    r.dispose();
  });

  it('串行不叠请求：上一次 load 未返回时跳过本拍', async () => {
    let release: (() => void) | undefined;
    const load = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          release = resolve;
        })
    );
    const r = createAutoRefresher({ intervalMs: 1000, load });

    r.start();
    await vi.advanceTimersByTimeAsync(1000);
    expect(load).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(3000); // 首拍还挂着，后面几拍全部跳过
    expect(load).toHaveBeenCalledTimes(1);

    release?.();
    await vi.advanceTimersByTimeAsync(1000);
    expect(load).toHaveBeenCalledTimes(2);
    r.dispose();
  });

  it('load 失败静默：不停表、不抛出，下一拍继续', async () => {
    const load = vi.fn().mockRejectedValue(new Error('boom'));
    const r = createAutoRefresher({ intervalMs: 1000, load });

    r.start();
    await vi.advanceTimersByTimeAsync(2000);
    expect(load).toHaveBeenCalledTimes(2);
    expect(r.running()).toBe(true);
    r.dispose();
  });

  it('maxDurationMs 到点自动停表（用户挂页面也不能无限轮询）', async () => {
    let clock = 0;
    const load = vi.fn().mockResolvedValue(undefined);
    const r = createAutoRefresher({
      intervalMs: 1000,
      load,
      maxDurationMs: 2500,
      now: () => clock
    });

    r.start();
    for (let i = 0; i < 4; i += 1) {
      clock += 1000;
      await vi.advanceTimersByTimeAsync(1000);
    }

    // t=1000/2000 两拍在窗口内，t=3000 起超窗口 ⇒ 停表
    expect(load).toHaveBeenCalledTimes(2);
    expect(r.running()).toBe(false);
    r.dispose();
  });

  it('stop 后不再触发；dispose 后 start 也不再起表', async () => {
    const load = vi.fn().mockResolvedValue(undefined);
    const r = createAutoRefresher({ intervalMs: 1000, load });

    r.start();
    r.stop();
    expect(r.running()).toBe(false);
    await vi.advanceTimersByTimeAsync(3000);
    expect(load).not.toHaveBeenCalled();

    r.dispose();
    r.start();
    expect(r.running()).toBe(false);
  });

  it('intervalMs <= 0 ⇒ 根本不建立定时器', () => {
    const r = createAutoRefresher({ intervalMs: 0, load: vi.fn() });
    r.start();
    expect(r.running()).toBe(false);
    r.dispose();
  });
});

describe('isOrderTerminal', () => {
  it('只有真正终结的状态算终态（DISPUTED 审核中仍要继续等后端）', () => {
    for (const s of ['PAID', 'COMPLETED', 'REFUNDED', 'PARTIAL_REFUNDED', 'CANCELLED', 'FAILED']) {
      expect(isOrderTerminal(s)).toBe(true);
      expect(isOrderTerminal(s.toLowerCase())).toBe(true);
    }
    for (const s of ['DISPUTED', 'PENDING', 'PROCESSING', 'UNPAID', '', undefined, null]) {
      expect(isOrderTerminal(s)).toBe(false);
    }
  });
});
