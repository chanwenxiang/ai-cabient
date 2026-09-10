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
});
