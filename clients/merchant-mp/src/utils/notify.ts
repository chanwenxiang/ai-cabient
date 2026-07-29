/** Merchant WeChat subscribe helpers (template IDs optional). */
const SUBSCRIBE_TMPL_IDS = (import.meta.env.VITE_WX_SUBSCRIBE_TMPL_IDS || '')
  .split(',')
  .map((s: string) => s.trim())
  .filter(Boolean);

export const MERCHANT_ALERT_TYPES = [
  { value: 'DISPUTE', label: '争议待审' },
  { value: 'DEVICE_OFFLINE', label: '柜机离线' },
  { value: 'LOW_STOCK', label: '低库存' },
  { value: 'EXPIRY', label: '临期下架' },
  { value: 'SLOT_DISCREPANCY', label: '货道差异' },
  { value: 'REPLENISHMENT', label: '补货任务' },
  { value: 'EXCEPTION', label: '识别/故障异常' }
] as const;

export async function requestMerchantSubscribe(): Promise<boolean> {
  // #ifdef MP-WEIXIN
  if (!SUBSCRIBE_TMPL_IDS.length) return true;
  return await new Promise((resolve) => {
    uni.requestSubscribeMessage({
      tmplIds: SUBSCRIBE_TMPL_IDS,
      complete: () => resolve(true),
      fail: () => resolve(false)
    });
  });
  // #endif
  // #ifndef MP-WEIXIN
  return true;
  // #endif
}

export function wxLoginCode(): Promise<string> {
  return new Promise((resolve, reject) => {
    // #ifdef MP-WEIXIN
    uni.login({
      provider: 'weixin',
      success: (res) => {
        if (res.code) resolve(res.code);
        else reject(new Error('未获取到微信登录码'));
      },
      fail: (err) => reject(new Error(err.errMsg || '微信登录失败'))
    });
    // #endif
    // #ifndef MP-WEIXIN
    reject(new Error('仅微信小程序可绑定提醒'));
    // #endif
  });
}
