/** 微信订阅消息模板 ID，在小程序后台配置后填入 .env.development */
const SUBSCRIBE_TMPL_IDS = (import.meta.env.VITE_WX_SUBSCRIBE_TMPL_IDS || '')
  .split(',')
  .map((s: string) => s.trim())
  .filter(Boolean);

export async function requestOrderSubscribe() {
  // #ifdef MP-WEIXIN
  if (!SUBSCRIBE_TMPL_IDS.length) return;
  await new Promise<void>((resolve) => {
    uni.requestSubscribeMessage({
      tmplIds: SUBSCRIBE_TMPL_IDS,
      complete: () => resolve()
    });
  });
  // #endif
}

export function showBillToast(totalCents: number) {
  const yuan = (totalCents / 100).toFixed(2);
  const title = totalCents <= 0 ? '本次未消费' : `已扣款 ¥${yuan}`;
  uni.showToast({
    title,
    icon: totalCents <= 0 ? 'none' : 'success',
    duration: 2000
  });
}

export function delay(ms: number) {
  return new Promise<void>((resolve) => setTimeout(resolve, ms));
}
