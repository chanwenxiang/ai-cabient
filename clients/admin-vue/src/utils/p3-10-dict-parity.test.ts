import { describe, expect, it } from 'vitest';
import { dictOptions, displayLabel } from '@aicabinet/shared-dict-core';

/**
 * P3-10（2026-11-12）：状态字典的词表与业务语义。
 *
 * 🔴 这里最重要的不是「加字典」，而是**把看似不一致的两处 UNPAID 钉成刻意**：
 * - 账单族（device_rental_bill_status / site_rent_bill_status / device_data_fee_bill_status）
 *   是「等客户付款」⇒「待付」；
 * - 应付账款族（supplier_payable_status）是「我方尚未付款」⇒「未付」。
 * 报告把这两处当成文案漂移要求统一，若真统一掉，财务语义就丢了（见报告 §十七.3 回执）。
 * 谁再把它们改成一个词，这组用例先红。
 */
describe('P3-10 / 状态字典词表与业务视角', () => {
  it('账单族的 UNPAID 是「待付」（等对方付款）', () => {
    expect(displayLabel('device_rental_bill_status', 'UNPAID', '未知')).toBe('待付');
    expect(displayLabel('site_rent_bill_status', 'UNPAID', '未知')).toBe('待付');
    expect(displayLabel('device_data_fee_bill_status', 'UNPAID', '未知')).toBe('待付');
  });

  it('应付账款族的 UNPAID 仍是「未付」（我方尚未付款），不得与账单族强行统一', () => {
    expect(displayLabel('supplier_payable_status', 'UNPAID', '未知')).toBe('未付');
  });

  it('盘点方式对齐后端 V453：OPEN=明盘，遗留码 VISIBLE 不再有标签', () => {
    expect(displayLabel('stocktake_mode', 'OPEN', '未知')).toBe('明盘');
    expect(displayLabel('stocktake_mode', 'BLIND', '未知')).toBe('盲盘');
    expect(displayLabel('stocktake_mode', 'VISIBLE', '未知')).toBe('未知');
    expect(
      dictOptions('stocktake_mode')
        .map((o) => o.value)
        .sort()
    ).toEqual(['BLIND', 'OPEN']);
  });

  it('补偿任务状态族补齐（此前客服查 compensation_task 只能看裸码）', () => {
    expect(displayLabel('compensation_task_status', 'PENDING', '未知')).toBe('待执行');
    expect(displayLabel('compensation_task_status', 'PROCESSING', '未知')).toBe('执行中');
    expect(displayLabel('compensation_task_status', 'COMPLETED', '未知')).toBe('已完成');
    expect(displayLabel('compensation_task_status', 'FAILED', '未知')).toBe('失败');
  });

  it('三个账单下拉的字典族各自定义完整，不会渲染出空选项', () => {
    for (const type of [
      'device_rental_bill_status',
      'site_rent_bill_status',
      'supplier_payable_status'
    ]) {
      expect(dictOptions(type).length).toBeGreaterThanOrEqual(3);
      expect(dictOptions(type).every((o) => o.label && o.value)).toBe(true);
    }
  });
});
