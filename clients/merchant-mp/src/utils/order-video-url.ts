/**
 * 商户订单购物视频绝对 URL（M12）。
 * 页内禁止再拼 API_BASE + path；小程序 downloadFile 走本函数。
 */
import { API_BASE_URL } from '@/config/api';
import { MerchantEndpoints } from '@/api/endpoints';

export function merchantOrderVideoUrl(orderId: string): string {
  return `${API_BASE_URL.replace(/\/$/, '')}${MerchantEndpoints.orderVideo(orderId)}`;
}

export type MerchantVideoErrorView = {
  title: string;
  desc: string;
  showCopy: boolean;
};

/** 把下载/播放失败收成用户可读文案。404=对象不存在，禁止再引导「复制链接」。 */
export function merchantVideoErrorView(err: unknown): MerchantVideoErrorView {
  const raw = err instanceof Error ? err.message : String(err || '');
  if (/401|未登录|登录已失效/.test(raw)) {
    return { title: '请重新登录', desc: '登录过期后无法拉取录像', showCopy: false };
  }
  if (/404|暂无|不存在|无法读取/.test(raw)) {
    return {
      title: '暂无购物视频',
      desc: '本单没有可播放的录像。超时免单、模拟柜或文件未上传时常见。',
      showCopy: false
    };
  }
  return {
    title: '视频加载失败',
    desc: raw || '录像暂时无法播放',
    showCopy: false
  };
}
