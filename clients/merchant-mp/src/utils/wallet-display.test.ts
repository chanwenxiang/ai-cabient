import { describe, expect, it } from 'vitest';
import { walletLedgerRefText, walletLedgerRemark, walletLedgerTitle } from './wallet-display';

describe('walletLedgerTitle', () => {
  it('分账入账/冲正不再显示未知', () => {
    expect(walletLedgerTitle('SPLIT_CREDIT')).toBe('分账入账');
    expect(walletLedgerTitle('SPLIT_PARTIAL_CREDIT')).toBe('分账入账');
    expect(walletLedgerTitle('SPLIT_REVERSE')).toBe('分账冲正');
    expect(walletLedgerTitle('SPLIT_PARTIAL_REVERSE')).toBe('分账冲正');
  });

  it('字典没有时用备注中文头', () => {
    expect(walletLedgerTitle('NO_SUCH_TYPE', '分账冲正 17906')).toBe('分账冲正');
  });
});

describe('walletLedgerRefText', () => {
  it('关联类型中文，不露 SPLIT_REV', () => {
    expect(walletLedgerRefText('SPLIT_REV', '1790688195291986926106')).toMatch(/^分账冲正 /);
    expect(walletLedgerRefText('SPLIT_REV', '1790688195291986926106')).not.toContain('SPLIT');
  });
});

describe('walletLedgerRemark', () => {
  it('与标题重复的备注不展示', () => {
    expect(walletLedgerRemark('分账冲正 17906', '分账冲正')).toBe('');
  });
});
