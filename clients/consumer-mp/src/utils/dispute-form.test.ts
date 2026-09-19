import { describe, expect, it } from 'vitest';
import { DISPUTE_REASON_CHIPS, appendChipToReason, inferRestoreInventory } from './dispute-form';

/**
 * 后端 DisputeReasonCategory 白名单：新增 chip 必须落在其中，
 * 否则服务端会拒单或落错分类（本用例是新增 chip 时的第一道守卫）。
 */
const ALLOWED_CATEGORIES: string[] = ['RECOGNITION', 'PAYMENT', 'USER_APPEAL'];

describe('DISPUTE_REASON_CHIPS', () => {
  it('每条 chip 的 category 都在后端白名单内', () => {
    for (const chip of DISPUTE_REASON_CHIPS) {
      expect(ALLOWED_CATEGORIES).toContain(chip.category);
    }
  });

  it('label/text 非空，restoreInventory 必须是显式布尔（不允许 undefined 让服务端猜）', () => {
    for (const chip of DISPUTE_REASON_CHIPS) {
      expect(chip.label.trim()).not.toBe('');
      expect(chip.text.trim()).not.toBe('');
      expect(typeof chip.restoreInventory).toBe('boolean');
    }
  });

  it('label 不重复（快捷选项一屏内不会出现两个同名按钮）', () => {
    const labels = DISPUTE_REASON_CHIPS.map((c) => c.label);
    expect(new Set(labels).size).toBe(labels.length);
  });
});

describe('appendChipToReason', () => {
  it('空输入直接落 chip 文案，不加分隔符', () => {
    const chip = DISPUTE_REASON_CHIPS[0];
    expect(appendChipToReason('', chip)).toBe(chip.text);
    expect(appendChipToReason('   ', chip)).toBe(chip.text);
  });

  it('已有内容时用「；」追加', () => {
    const chip = DISPUTE_REASON_CHIPS[1];
    expect(appendChipToReason('我自己写的说明', chip)).toBe(`我自己写的说明；${chip.text}`);
  });

  it('重复点同一条 chip 保持幂等，不重复追加', () => {
    const chip = DISPUTE_REASON_CHIPS[2];
    const once = appendChipToReason('', chip);
    expect(appendChipToReason(once, chip)).toBe(once);
  });

  it('文案已包含在自由文本里时不再追加（避免「；」堆积）', () => {
    const chip = DISPUTE_REASON_CHIPS[0];
    const current = `前缀说明；${chip.text}`;
    expect(appendChipToReason(current, chip)).toBe(current);
  });
});

describe('inferRestoreInventory', () => {
  it('显式 chip 时按 chip 的布尔值回传', () => {
    const restore = DISPUTE_REASON_CHIPS.find((c) => c.restoreInventory);
    const noRestore = DISPUTE_REASON_CHIPS.find((c) => !c.restoreInventory);
    expect(restore).toBeDefined();
    expect(noRestore).toBeDefined();
    expect(inferRestoreInventory(restore?.text ?? '', restore)).toBe(true);
    expect(inferRestoreInventory(noRestore?.text ?? '', noRestore)).toBe(false);
  });

  it('自由文本（无 chip）一律 undefined，交由服务端判定（C-10：客户端不猜库存回补）', () => {
    expect(inferRestoreInventory('我没有拿这个商品，请核对识别结果')).toBeUndefined();
    expect(inferRestoreInventory('我没有拿这个商品，请核对识别结果', null)).toBeUndefined();
    expect(inferRestoreInventory('随便写的理由', undefined)).toBeUndefined();
  });
});
