import { describe, expect, it } from 'vitest';
import { MerchantEndpoints } from '@/api/endpoints';
import { merchantOrderVideoUrl, merchantVideoErrorView } from './order-video-url';

describe('merchantOrderVideoUrl · M12', () => {
  it('绝对 URL 含 Endpoints 相对路径且编码 orderId', () => {
    const url = merchantOrderVideoUrl('O/1');
    expect(url).toContain(MerchantEndpoints.orderVideo('O/1'));
    expect(url).toMatch(/^https?:\/\//);
    expect(url).toContain(encodeURIComponent('O/1'));
  });
});

describe('merchantVideoErrorView', () => {
  it('404 映射为暂无录像且不复制链接', () => {
    const view = merchantVideoErrorView(new Error('下载失败 (404)'));
    expect(view.title).toBe('暂无购物视频');
    expect(view.showCopy).toBe(false);
  });

  it('对象不存在文案同样不复制', () => {
    const view = merchantVideoErrorView(new Error('视频文件不存在或无法读取'));
    expect(view.title).toBe('暂无购物视频');
    expect(view.showCopy).toBe(false);
  });
});
