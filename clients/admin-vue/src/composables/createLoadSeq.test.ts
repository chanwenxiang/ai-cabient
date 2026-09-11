import { describe, expect, it } from 'vitest';
import { createLoadSeq } from './createLoadSeq';

describe('createLoadSeq', () => {
  it('begin 递增且仅最新 token 为 current', () => {
    const g = createLoadSeq();
    const a = g.begin();
    const b = g.begin();
    expect(a).toBe(1);
    expect(b).toBe(2);
    expect(g.isCurrent(a)).toBe(false);
    expect(g.isCurrent(b)).toBe(true);
  });

  it('新 begin 会使旧 token 失效', () => {
    const g = createLoadSeq();
    const first = g.begin();
    expect(g.isCurrent(first)).toBe(true);
    g.begin();
    expect(g.isCurrent(first)).toBe(false);
  });

  it('不同 channel 互不干扰', () => {
    const g = createLoadSeq();
    const a1 = g.begin('a');
    const b1 = g.begin('b');
    g.begin('a');
    expect(g.isCurrent(a1, 'a')).toBe(false);
    expect(g.isCurrent(b1, 'b')).toBe(true);
  });

  it('模拟乱序响应：慢的旧请求不得写回', async () => {
    const g = createLoadSeq();
    const written: number[] = [];
    const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

    async function load(page: number, delayMs: number) {
      const seq = g.begin();
      await sleep(delayMs);
      if (!g.isCurrent(seq)) return;
      written.push(page);
    }

    await Promise.all([load(0, 80), load(1, 10)]);
    expect(written).toEqual([1]);
  });
});
