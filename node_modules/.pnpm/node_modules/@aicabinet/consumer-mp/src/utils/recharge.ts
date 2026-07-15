import { consumerApi } from '@/utils/consumer-api';

const PENDING_RECHARGE_KEY = 'pending_recharge_order_id';

export function savePendingRechargeOrder(orderId: string) {
  uni.setStorageSync(PENDING_RECHARGE_KEY, orderId);
}

export function takePendingRechargeOrder(): string {
  const id = String(uni.getStorageSync(PENDING_RECHARGE_KEY) || '');
  if (id) uni.removeStorageSync(PENDING_RECHARGE_KEY);
  return id;
}

export function openAlipayPayUrl(payUrl: string) {
  if (!payUrl) {
    throw new Error('支付宝支付链接为空');
  }
  // #ifdef H5
  window.location.href = payUrl;
  // #endif
  // #ifndef H5
  throw new Error('支付宝沙箱充值请在 H5 浏览器中打开');
  // #endif
}

export function openAlipayPayForm(payFormHtml: string) {
  if (!payFormHtml) {
    throw new Error('支付宝支付表单为空');
  }
  // #ifdef H5
  const doc = window.document;
  doc.open();
  doc.write(payFormHtml);
  doc.close();
  // #endif
  // #ifndef H5
  throw new Error('支付宝沙箱充值请在 H5 浏览器中打开');
  // #endif
}

export function openAlipayPrepay(alipayPay?: { payUrl?: string; payFormHtml?: string }) {
  if (alipayPay?.payFormHtml) {
    openAlipayPayForm(alipayPay.payFormHtml);
    return;
  }
  if (alipayPay?.payUrl) {
    openAlipayPayUrl(alipayPay.payUrl);
    return;
  }
  throw new Error('未获取到支付宝支付参数');
}

export async function pollRechargePaid(orderId: string, attempts = 30, intervalMs = 2000) {
  for (let i = 0; i < attempts; i++) {
    const order = await consumerApi.getRechargeOrder(orderId);
    if (order.status === 'PAID') return order;
    if (order.status === 'CANCELLED' || order.status === 'REFUNDED') {
      throw new Error('充值订单已取消或关闭');
    }
    await delay(intervalMs);
  }
  throw new Error('充值结果确认超时，请稍后在「我的」页刷新余额');
}

function delay(ms: number) {
  return new Promise<void>((resolve) => setTimeout(resolve, ms));
}

export async function resumePendingRechargeIfAny(): Promise<boolean> {
  const orderId = takePendingRechargeOrder();
  if (!orderId) return false;
  try {
    await pollRechargePaid(orderId, 15, 1500);
    uni.showToast({ title: '充值已到账', icon: 'success' });
    return true;
  } catch (e) {
    uni.showToast({
      title: e instanceof Error ? e.message : '充值确认失败',
      icon: 'none',
      duration: 3000
    });
    return false;
  }
}
