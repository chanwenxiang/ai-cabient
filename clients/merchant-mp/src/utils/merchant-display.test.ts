import { describe, expect, it } from 'vitest';
import { formatMerchantNames, isCorruptedMerchantName } from './merchant-display';

describe('isCorruptedMerchantName', () => {
  it('纯问号名判为损坏（编码丢失的典型形态）', () => {
    expect(isCorruptedMerchantName('?')).toBe(true);
    expect(isCorruptedMerchantName('??')).toBe(true);
    expect(isCorruptedMerchantName('????')).toBe(true);
  });

  it('出现连续三个问号且总数 >= 2 判为损坏', () => {
    expect(isCorruptedMerchantName('正常商户????')).toBe(true);
    expect(isCorruptedMerchantName('a???b')).toBe(true);
  });

  it('零散问号（无连续三问号）不判损坏，避免误伤正常店名', () => {
    expect(isCorruptedMerchantName('a??b')).toBe(false);
    expect(isCorruptedMerchantName('a?b?c?')).toBe(false);
  });

  it('空值/空串不判损坏', () => {
    expect(isCorruptedMerchantName('')).toBe(false);
    expect(isCorruptedMerchantName('   ')).toBe(false);
    expect(isCorruptedMerchantName(null)).toBe(false);
    expect(isCorruptedMerchantName(undefined)).toBe(false);
  });

  it('正常中文/英文店名不判损坏', () => {
    expect(isCorruptedMerchantName('好邻居便利店')).toBe(false);
    expect(isCorruptedMerchantName('7-Eleven')).toBe(false);
  });
});

describe('formatMerchantNames', () => {
  it('多个商户名用顿号连接', () => {
    expect(formatMerchantNames([{ merchantName: '好邻居' }, { merchantName: '全家' }])).toBe(
      '好邻居、全家'
    );
  });

  it('过滤空白名与损坏名，只展示可用名字', () => {
    expect(
      formatMerchantNames([
        { merchantName: '好邻居' },
        { merchantName: '   ' },
        { merchantName: '' },
        { merchantName: '????' },
        { merchantName: undefined }
      ])
    ).toBe('好邻居');
  });

  it('名字两端空白被裁剪', () => {
    expect(formatMerchantNames([{ merchantName: '  好邻居  ' }])).toBe('好邻居');
  });

  it('无可用名字时回落默认文案', () => {
    expect(formatMerchantNames([])).toBe('未绑定商户');
    expect(formatMerchantNames(undefined)).toBe('未绑定商户');
    expect(formatMerchantNames([{ merchantName: '????' }])).toBe('未绑定商户');
  });

  it('允许自定义空态文案', () => {
    expect(formatMerchantNames([], '暂无商户')).toBe('暂无商户');
  });
});
