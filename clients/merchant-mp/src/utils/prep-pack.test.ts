import { describe, expect, it } from 'vitest';
import { prepPackText } from './prep-pack';

describe('prepPackText', () => {
  it('整件 + 拆零', () => {
    expect(prepPackText({ cases: 2, remainUnits: 3, unit: '罐' })).toBe('2 件 + 3 罐');
  });

  it('只有整件时不输出 0 单位', () => {
    expect(prepPackText({ cases: 1, remainUnits: 0, unit: '罐' })).toBe('1 件');
  });

  it('只有拆零时不输出 0 件', () => {
    expect(prepPackText({ cases: 0, remainUnits: 6, unit: '罐' })).toBe('6 罐');
  });

  it('全 0 回落 0 单位而不是空白', () => {
    expect(prepPackText({ cases: 0, remainUnits: 0, unit: '罐' })).toBe('0 罐');
  });

  it('缺字段按 0 处理，单位缺省为支', () => {
    expect(prepPackText({})).toBe('0 支');
  });
});
