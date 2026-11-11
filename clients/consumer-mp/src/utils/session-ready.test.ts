import { describe, expect, it, vi } from 'vitest';
import { createSessionReadyBus } from './session-ready';

describe('createSessionReadyBus', () => {
  it('emit 依次通知全部监听器', () => {
    const bus = createSessionReadyBus();
    const a = vi.fn();
    const b = vi.fn();
    bus.on(a);
    bus.on(b);

    bus.emit();

    expect(a).toHaveBeenCalledTimes(1);
    expect(b).toHaveBeenCalledTimes(1);
  });

  it('取消函数摘掉监听器，且重复调用安全（幂等）', () => {
    const bus = createSessionReadyBus();
    const fn = vi.fn();
    const off = bus.on(fn);

    off();
    off();
    bus.emit();

    expect(fn).not.toHaveBeenCalled();
    expect(bus.size()).toBe(0);
  });

  it('同一函数注册两次算两个监听器，取消一个另一个仍生效', () => {
    const bus = createSessionReadyBus();
    const fn = vi.fn();
    const off1 = bus.on(fn);
    bus.on(fn);

    off1();
    bus.emit();

    expect(fn).toHaveBeenCalledTimes(1);
    expect(bus.size()).toBe(1);
  });

  it('单个监听器抛错不影响其他监听器', () => {
    const bus = createSessionReadyBus();
    const after = vi.fn();
    bus.on(() => {
      throw new Error('boom');
    });
    bus.on(after);

    expect(() => bus.emit()).not.toThrow();
    expect(after).toHaveBeenCalledTimes(1);
  });

  it('监听器内再次 on 不会在本次 emit 里被触发（避免一轮广播无限扩散）', () => {
    const bus = createSessionReadyBus();
    const late = vi.fn();
    bus.on(() => {
      bus.on(late);
    });

    bus.emit();

    expect(late).not.toHaveBeenCalled();
    expect(bus.size()).toBe(2);

    bus.emit();
    expect(late).toHaveBeenCalledTimes(1);
  });

  it('监听器内自摘不会影响本轮其他监听器', () => {
    const bus = createSessionReadyBus();
    const other = vi.fn();
    const offSelf = bus.on(() => offSelf());
    bus.on(other);

    bus.emit();

    expect(other).toHaveBeenCalledTimes(1);
    expect(bus.size()).toBe(1);
  });
});
