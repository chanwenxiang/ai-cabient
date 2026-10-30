import { describe, expect, it } from 'vitest';
import { sortByGeneratedTime, taskTimeMs } from './prep-order';

const A = { deviceId: 'EG-A', lastTaskAt: '2026-10-30T01:00:00Z' };
const B = { deviceId: 'EG-B', lastTaskAt: '2026-10-29T01:00:00Z' };
const C = { deviceId: 'EG-C', lastTaskAt: '2026-10-30T09:30:00Z' };

describe('sortByGeneratedTime', () => {
  it('倒序=最新在前', () => {
    expect(sortByGeneratedTime([B, A, C], 'desc').map((g) => g.deviceId)).toEqual([
      'EG-C',
      'EG-A',
      'EG-B'
    ]);
  });

  it('正序=最早在前', () => {
    expect(sortByGeneratedTime([C, A, B], 'asc').map((g) => g.deviceId)).toEqual([
      'EG-B',
      'EG-A',
      'EG-C'
    ]);
  });

  it('时间相同按编码兜底，两个方向都稳定', () => {
    const same = [
      { deviceId: 'EG-Z', lastTaskAt: '2026-10-30T01:00:00Z' },
      { deviceId: 'EG-Y', lastTaskAt: '2026-10-30T01:00:00Z' }
    ];
    expect(sortByGeneratedTime(same, 'desc').map((g) => g.deviceId)).toEqual(['EG-Y', 'EG-Z']);
    expect(sortByGeneratedTime(same, 'asc').map((g) => g.deviceId)).toEqual(['EG-Y', 'EG-Z']);
  });

  it('后端没带时间按 0 处理，不抛异常', () => {
    expect(taskTimeMs({ deviceId: 'EG-X' })).toBe(0);
    expect(taskTimeMs({ deviceId: 'EG-X', lastTaskAt: '不是时间' })).toBe(0);
    expect(sortByGeneratedTime([{ deviceId: 'EG-X' }, A], 'desc').map((g) => g.deviceId)).toEqual([
      'EG-A',
      'EG-X'
    ]);
  });

  it('不修改传入数组', () => {
    const input = [B, A];
    sortByGeneratedTime(input, 'desc');
    expect(input.map((g) => g.deviceId)).toEqual(['EG-B', 'EG-A']);
  });
});
