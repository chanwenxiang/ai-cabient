import { describe, expect, it } from 'vitest';
import { BoundedFifoCache } from './bounded-fifo-cache';

/**
 * 2026-10-23 审计残留：dispute-evidence 本地缓存只进不出 → 抽 BoundedFifoCache 直测。
 * 负向对照：容量 1 的缓存必须真的淘汰旧键（若判据写错，size 恒 1 也会「看起来对」）。
 */
describe('BoundedFifoCache', () => {
  it('未超上限时全部保留', () => {
    const c = new BoundedFifoCache(3);
    c.set('a', '1');
    c.set('b', '2');
    expect(c.get('a')).toBe('1');
    expect(c.get('b')).toBe('2');
  });

  it('超上限按最早写入淘汰（FIFO）', () => {
    const c = new BoundedFifoCache(2);
    c.set('a', '1');
    c.set('b', '2');
    c.set('c', '3');
    expect(c.get('a')).toBeUndefined();
    expect(c.get('b')).toBe('2');
    expect(c.get('c')).toBe('3');
  });

  it('同键重写刷新位置，不被淘汰（负向对照：覆盖语义）', () => {
    const c = new BoundedFifoCache(2);
    c.set('a', '1');
    c.set('b', '2');
    c.set('a', '1x');
    c.set('c', '3');
    expect(c.get('a')).toBe('1x');
    expect(c.get('b')).toBeUndefined();
  });

  it('容量 1：恒只保留最新一条', () => {
    const c = new BoundedFifoCache(1);
    c.set('a', '1');
    c.set('b', '2');
    expect(c.get('a')).toBeUndefined();
    expect(c.get('b')).toBe('2');
  });

  it('max < 1 拒绝构造', () => {
    expect(() => new BoundedFifoCache(0)).toThrow();
  });
});
