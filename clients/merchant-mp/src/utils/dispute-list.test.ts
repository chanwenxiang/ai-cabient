import { describe, expect, it } from 'vitest';
import {
  DISPUTES_FOCUS_SCAN_MAX_PAGES,
  DISPUTES_PAGE_SIZE,
  appendDisputePageItems,
  applyDisputesFirstPage,
  disputeSlaDetailLabel,
  disputeSlaListLabel,
  merchantDisputeResolveConfirmContent,
  playablePlaybackUrl
} from './dispute-list';

describe('dispute-list · M7/M7b', () => {
  it('PAGE_SIZE 上限 50', () => {
    expect(DISPUTES_PAGE_SIZE).toBe(50);
    expect(DISPUTES_PAGE_SIZE).toBeLessThan(100);
    expect(DISPUTES_FOCUS_SCAN_MAX_PAGES).toBeGreaterThan(0);
  });

  it('只接受可播放协议', () => {
    expect(playablePlaybackUrl('https://cdn.example/a.mp4')).toBe('https://cdn.example/a.mp4');
    expect(playablePlaybackUrl('minio://cabinet-videos/sim/a.mp4')).toBe('');
  });

  it('首屏与追加分页', () => {
    const first = applyDisputesFirstPage({
      items: [{ ticketId: 'T1' }, { ticketId: 'T2' }],
      total: 3
    });
    expect(first.list).toHaveLength(2);
    expect(first.hasMore).toBe(true);
    const next = appendDisputePageItems({
      list: first.list,
      pageIndex: 1,
      pageSize: 50,
      previousTotal: first.total,
      res: { items: [{ ticketId: 'T2' }, { ticketId: 'T3' }], total: 3 }
    });
    expect(next.list.map((t) => t.ticketId)).toEqual(['T1', 'T2', 'T3']);
    expect(next.hasMore).toBe(false);
    expect(next.appended).toBe(1);
  });

  it('SLA 与结案确认文案', () => {
    expect(disputeSlaListLabel({ slaOverdue: true }, false, '已结案', '处理中')).toBe('已超时');
    expect(disputeSlaListLabel({ slaHoursRemaining: 2 }, false, '已结案', '处理中')).toBe(
      '剩余 2 小时'
    );
    expect(disputeSlaListLabel({}, true, '已结案', '处理中')).toBe('已结案');
    expect(disputeSlaDetailLabel({ slaHoursRemaining: 1 })).toBe('剩余 1 小时');
    expect(merchantDisputeResolveConfirmContent('WAIVE', '免单')).toContain('免单');
    expect(merchantDisputeResolveConfirmContent('KEEP', '维持原判')).toBe('确认维持原判？');
  });
});
