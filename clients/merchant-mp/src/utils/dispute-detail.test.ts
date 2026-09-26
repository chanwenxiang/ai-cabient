import { describe, expect, it } from 'vitest';
import { mergeDisputeDetailRow, resolveDisputeDetailPermissions } from './dispute-detail';

describe('dispute-detail · M7b', () => {
  it('合并详情与最新消息', () => {
    const row = mergeDisputeDetailRow(
      { ticketId: 'T1', status: 'OPEN', lastMessage: '旧' },
      {
        ticket: { ticketId: 'T1', status: 'OPEN', reason: '新原因' },
        messages: [{ body: '客服回复' }, { body: '最新一条' }]
      }
    );
    expect(row.lastMessage).toBe('最新一条');
    expect((row as { reason?: string }).reason).toBe('新原因');
  });

  it('权限：API 显式 false 压过列表兜底', () => {
    const denied = resolveDisputeDetailPermissions({
      status: 'OPEN',
      canReplyFromApi: false,
      canResolveFromApi: false,
      hasReplyPerm: true,
      hasResolvePerm: true
    });
    expect(denied.canReplyDetail).toBe(false);
    expect(denied.canResolveDetail).toBe(false);
  });
});
