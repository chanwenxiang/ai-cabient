/**
 * 组织切换上下文（CB-050 扩展，2026-10-16 用户拍板）。
 * 后端商户数据本就按 allowedMerchantIds（绑定商户+下级树）放行，
 * 切换组织 = 前端选定 merchantId 后在设备/柜机列表做上下文过滤，不做鉴权切换。
 */
const KEY = 'merchant_org_filter';

export function getOrgFilter(): string {
  try {
    return String(uni.getStorageSync(KEY) || '');
  } catch {
    return '';
  }
}

export function setOrgFilter(merchantId: string): void {
  try {
    if (merchantId) {
      uni.setStorageSync(KEY, merchantId);
    } else {
      uni.removeStorageSync(KEY);
    }
  } catch {
    /* 存储失败静默：过滤仅影响展示范围 */
  }
}
