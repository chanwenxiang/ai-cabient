/**
 * 消费者订单购物视频绝对 URL（C12，对齐 merchant M12）。
 * 页内禁止再拼 API_BASE + path；H5 fetch / 小程序 download 共用。
 */
import { API_BASE_URL } from '@/config/api';
import { ConsumerEndpoints } from '@/api/endpoints';

export function consumerOrderVideoUrl(orderId: string): string {
  return `${API_BASE_URL.replace(/\/$/, '')}${ConsumerEndpoints.orderVideo(orderId)}`;
}

/** 相对/绝对视频地址归一化（深链或网关偶发相对路径）。 */
export function normalizeMediaUrl(url: string): string {
  const trimmed = String(url || '').trim();
  if (!trimmed) return '';
  if (/^https?:\/\//i.test(trimmed)) return trimmed;
  const base = API_BASE_URL.replace(/\/$/, '');
  return trimmed.startsWith('/') ? base + trimmed : `${base}/${trimmed}`;
}
