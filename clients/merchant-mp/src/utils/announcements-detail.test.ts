import { describe, expect, it, vi } from 'vitest';
import { useAnnouncementDetail } from '@aicabinet/shared-uni/announcements';

/**
 * MA-035（2026-11-02 R9 M 批 5）：公告详情在「入口没带编号」时不得伪装成网络故障。
 *
 * <p>两端（consumer / merchant）的 `pages/announcements/detail` 共用这个 composable，
 * 所以判据落在共享层一处即可覆盖两端：缺参数 ⇒ `missingId=true` + 文案不提「网络」，
 * 页面据此收掉重试按钮（重试读的还是同一个空 id，永远不可能成功）。
 * 同族先例：`pages/device-detail`（deviceId 缺失属入口问题，不是网络故障）。
 */
describe('useAnnouncementDetail · 缺公告编号（MA-035）', () => {
  it('没有 id 时 missingId 为真，且错误文案不提网络', async () => {
    const fetchDetail = vi.fn(async () => null);
    const d = useAnnouncementDetail(fetchDetail);

    await d.load();

    expect(d.missingId.value).toBe(true);
    expect(d.error.value).toBe('缺少公告编号');
    expect(d.error.value).not.toContain('网络');
    expect(fetchDetail).not.toHaveBeenCalled();
    expect(d.loading.value).toBe(false);
  });

  it('带 id 时走正常取数路径，missingId 归假（真不存在/失败仍交给页面提示重试）', async () => {
    const fetchDetail = vi.fn(async () => null);
    const d = useAnnouncementDetail(fetchDetail);

    await d.load(123);

    expect(d.missingId.value).toBe(false);
    expect(fetchDetail).toHaveBeenCalledWith(123);
  });
});
