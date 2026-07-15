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

export async function requestDisputeSubscribe() {
  return requestOrderSubscribe();
}

export function showDisputeResolvedToast(ticket: {
  status?: string;
  billedAmountCents?: number | null;
  resolutionItems?: { skuName?: string; quantity?: number }[];
}) {
  const amount = ticket.billedAmountCents ?? 0;
  let title = '人工审核已完成';
  if (ticket.status === 'RESOLVED' && amount > 0) {
    title = `审核完成，已扣款 ¥${(amount / 100).toFixed(2)}`;
  } else if (ticket.status === 'RESOLVED' && amount <= 0) {
    title = '审核完成，本次未扣款';
  }
  uni.showToast({ title, icon: 'success', duration: 2500 });
}

export function delay(ms: number) {
  return new Promise<void>((resolve) => setTimeout(resolve, ms));
}
