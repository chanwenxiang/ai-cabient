import { describe, expect, it } from 'vitest';
import { MerchantEndpoints } from '@/api/endpoints';
import { merchantOrderVideoUrl } from './order-video-url';

describe('merchantOrderVideoUrl · M12', () => {
  it('绝对 URL 含 Endpoints 相对路径且编码 orderId', () => {
    const url = merchantOrderVideoUrl('O/1');
    expect(url).toContain(MerchantEndpoints.orderVideo('O/1'));
    expect(url).toMatch(/^https?:\/\//);
    expect(url).toContain(encodeURIComponent('O/1'));
  });
});
