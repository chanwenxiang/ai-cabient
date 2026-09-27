import { describe, expect, it } from 'vitest';
import { goodsSlotsFromSummary, parseGoodsLines } from './order-line-summary';

describe('parseGoodsLines', () => {
  it('拆分名称、数量与货道，并去掉批次', () => {
    expect(parseGoodsLines('S1纯牛奶 x1 ·货道A1 @STOCKTAKE-2026-09-27')).toEqual([
      { title: 'S1纯牛奶', qty: '1', slot: 'A1' }
    ]);
  });

  it('多行用顿号分隔', () => {
    expect(parseGoodsLines('可乐 x2 ·货道A1、雪碧 x1 ·货道B2')).toEqual([
      { title: '可乐', qty: '2', slot: 'A1' },
      { title: '雪碧', qty: '1', slot: 'B2' }
    ]);
  });

  it('无货道时 slot 为空', () => {
    expect(parseGoodsLines('可口可乐 330ml x1')).toEqual([
      { title: '可口可乐 330ml', qty: '1', slot: '' }
    ]);
  });

  it('截断「等N件/种」后缀', () => {
    expect(parseGoodsLines('A x1 ·货道A1、B x1 ·货道A2 等3件')).toEqual([
      { title: 'A', qty: '1', slot: 'A1' },
      { title: 'B', qty: '1', slot: 'A2' }
    ]);
  });
});

describe('goodsSlotsFromSummary', () => {
  it('只返回有货道的编码', () => {
    expect(goodsSlotsFromSummary('牛奶 x1 ·货道A1、面包 x1')).toEqual(['A1']);
  });
});
