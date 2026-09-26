/**
 * 商户订单购物视频绝对 URL（M12）。
 * 页内禁止再拼 API_BASE + path；H5 fetch / 小程序 download 共用。
 */
import { API_BASE_URL } from '@/config/api';
import { MerchantEndpoints } from '@/api/endpoints';

export function merchantOrderVideoUrl(orderId: string): string {
  return `${API_BASE_URL.replace(/\/$/, '')}${MerchantEndpoints.orderVideo(orderId)}`;
}
