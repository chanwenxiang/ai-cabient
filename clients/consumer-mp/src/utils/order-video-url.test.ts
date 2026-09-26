import { describe, expect, it } from 'vitest';
import { ConsumerEndpoints } from '@/api/endpoints';
import { consumerOrderVideoUrl, normalizeMediaUrl } from './order-video-url';

describe('order-video-url · C12', () => {
  it('绝对 URL 含 Endpoints 且编码 orderId', () => {
    const url = consumerOrderVideoUrl('O/1');
    expect(url).toContain(ConsumerEndpoints.orderVideo('O/1'));
    expect(url).toMatch(/^https?:\/\//);
    expect(url).toContain(encodeURIComponent('O/1'));
  });

  it('normalizeMediaUrl：绝对保留、相对拼 base', () => {
    expect(normalizeMediaUrl('https://cdn.example/a.mp4')).toBe('https://cdn.example/a.mp4');
    expect(normalizeMediaUrl('/api/v2/x')).toMatch(/\/api\/v2\/x$/);
    expect(normalizeMediaUrl('')).toBe('');
  });
});
