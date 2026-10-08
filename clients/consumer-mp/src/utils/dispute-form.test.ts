import { describe, expect, it } from 'vitest';
import {
  applyChipToReason,
  DEFAULT_DISPUTE_CATEGORY,
  DISPUTE_REASON_CHIPS,
  inferRestoreInventory
} from './dispute-form';

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

  it('兜底分类 DEFAULT_DISPUTE_CATEGORY 在后端白名单内', () => {
    expect(ALLOWED_CATEGORIES).toContain(DEFAULT_DISPUTE_CATEGORY);
  });
});

describe('applyChipToReason（单选快填 + 可点掉，2026-10-08 用户定案）', () => {
  const chip = DISPUTE_REASON_CHIPS[0];

  it('未选中 → 选中：说明整体替换为 chip 文案（不再「；」追加）', () => {
    expect(applyChipToReason('', chip, false)).toBe(chip.text);
    expect(applyChipToReason('我自己写的说明', chip, false)).toBe(chip.text);
    expect(applyChipToReason('我没拿这个商品，请核对识别结果；数量不对', chip, false)).toBe(
      chip.text
    );
  });

  it('已选中 → 取消：说明仍是 chip 原文（未编辑）则清空', () => {
    expect(applyChipToReason(chip.text, chip, true)).toBe('');
    expect(applyChipToReason(` ${chip.text} `, chip, true)).toBe('');
  });

  it('已选中 → 取消：说明被用户编辑过则保留', () => {
    expect(applyChipToReason(`${chip.text}，麻烦尽快核实`, chip, true)).toBe(
      `${chip.text}，麻烦尽快核实`
    );
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
