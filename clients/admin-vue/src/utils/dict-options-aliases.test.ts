import { describe, expect, it } from 'vitest';
import { dictOptions, displayLabel } from '@aicabinet/shared-dict-core';

/**
 * MA-018（2026-10-31 用户拍板 B 案：选项层收敛）：
 * - 下拉里同一标签只能出现一次（历史别名值不进选项）；
 * - 但历史值的**标签解析**必须保留（老数据不能变成裸码）；
 * - `enable_status` 是刻意的两套词表（账号类 ENABLED/DISABLED、通用类 ACTIVE/INACTIVE），不收敛。
 */
describe('dictOptions / 历史别名值不进下拉（MA-018）', () => {
  it('feedback_status：下拉只剩一个「已回复」，值为 HANDLED', () => {
    const replies = dictOptions('feedback_status').filter((o) => o.label === '已回复');
    expect(replies.map((o) => o.value)).toEqual(['HANDLED']);
  });

  it('feedback_status：REPLIED 已由 V418 迁走，标签回落默认值（不再有历史值）', () => {
    expect(displayLabel('feedback_status', 'REPLIED', '未知')).toBe('未知');
  });

  it('session_kind：SHOPPING（其实是 session_state 的值）不出现在选项里', () => {
    expect(dictOptions('session_kind').some((o) => o.value === 'SHOPPING')).toBe(false);
  });

  it('split_status：纯 FAILED/SUBMITTED 别名不进下拉，WECHAT_* 仍在', () => {
    const values = dictOptions('split_status').map((o) => o.value);
    expect(values).not.toContain('FAILED');
    expect(values).not.toContain('SUBMITTED');
    expect(values).toContain('WECHAT_FAILED');
    expect(values).toContain('WECHAT_SUBMITTED');
  });

  it('enable_status：DISABLED 已拆到 bin_status，下拉只剩一个「停用」', () => {
    const disabled = dictOptions('enable_status').filter((o) => o.label === '停用');
    expect(disabled.map((o) => o.value)).toEqual(['INACTIVE']);
  });

  it('enable_status：一型两义——DRAFT/STOPPED 是营销活动状态在用的键（MA-146 复核订正）', () => {
    // PromotionService 建单即写 DRAFT、stop() 写 STOPPED，而库/种子里 enable_status 只有
    // ACTIVE/INACTIVE/ENDED 三值 ⇒ 这两键的唯一用处是让活动状态仍能被译名。
    // 把它们当「冗余」删掉 ⇒ 活动列表的「草稿/已停止」洗成「未知」（原判定错在只看字典没看消费方）。
    expect(displayLabel('enable_status', 'DRAFT', '未知')).toBe('草稿');
    expect(displayLabel('enable_status', 'STOPPED', '未知')).toBe('已停止');
  });

  it('bin_status：货位状态独立字典（ACTIVE 启用 / DISABLED 停用）', () => {
    const values = dictOptions('bin_status').map((o) => o.value);
    expect(values).toEqual(['ACTIVE', 'DISABLED']);
    expect(displayLabel('bin_status', 'DISABLED', '未知')).toBe('停用');
  });
});
