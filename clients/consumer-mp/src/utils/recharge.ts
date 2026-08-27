import { consumerApi } from '@/utils/consumer-api';

const PENDING_RECHARGE_KEY = 'pending_recharge_order_id';
const ALIPAY_RETURN_PAGE_KEY = 'alipay_return_page';

/** 支付宝同步回跳后应打开的页面（H5） */
export const ALIPAY_RETURN_PAGE = '/pages/recharge/recharge';

export function savePendingRechargeOrder(orderId: string) {
  uni.setStorageSync(PENDING_RECHARGE_KEY, orderId);
}

export function peekPendingRechargeOrder(): string {
  return String(uni.getStorageSync(PENDING_RECHARGE_KEY) || '');
}

export function clearPendingRechargeOrder() {
  uni.removeStorageSync(PENDING_RECHARGE_KEY);
}

export function takePendingRechargeOrder(): string {
  const id = peekPendingRechargeOrder();
  if (id) clearPendingRechargeOrder();
  return id;
}

export function rememberAlipayReturnPage(page = ALIPAY_RETURN_PAGE) {
  uni.setStorageSync(ALIPAY_RETURN_PAGE_KEY, page);
}

export function takeAlipayReturnPage(): string {
  const page = String(uni.getStorageSync(ALIPAY_RETURN_PAGE_KEY) || '');
  if (page) uni.removeStorageSync(ALIPAY_RETURN_PAGE_KEY);
  return page || ALIPAY_RETURN_PAGE;
}

/** 仅允许跳转到支付宝网关（正式 / 沙箱）。 */
function isAllowedAlipayAction(action: string): boolean {
  try {
    const url = new URL(
      action,
      typeof globalThis !== 'undefined' ? globalThis.location.origin : 'https://local.invalid'
    );
    if (url.protocol !== 'https:' && url.protocol !== 'http:') return false;
    const host = url.hostname.toLowerCase();
    return (
      host === 'openapi.alipay.com' ||
      host === 'openapi.alipaydev.com' ||
      host.endsWith('.alipay.com') ||
      host.endsWith('.alipaydev.com')
    );
  } catch {
    return false;
  }
}

export function openAlipayPayUrl(payUrl: string) {
  if (!payUrl) {
    throw new Error('支付宝支付链接为空');
  }
  if (!isAllowedAlipayAction(payUrl)) {
    throw new Error('支付宝支付地址无效');
  }
  rememberAlipayReturnPage();
  // #ifdef H5
  globalThis.location.href = payUrl;
  // #endif
  // #ifndef H5
  throw new Error('支付宝沙箱充值请在 H5 浏览器中打开');
  // #endif
}

/**
 * 提交支付宝 pagePay 表单。
 * 禁止 document.write：会毁掉当前 SPA；禁止把后端 HTML（含 script）直接 innerHTML。
 * 只解析 form/action/hidden inputs，用 createElement 重建后再 submit。
 */
export function openAlipayPayForm(payFormHtml: string) {
  if (!payFormHtml) {
    throw new Error('支付宝支付表单为空');
  }
  rememberAlipayReturnPage();
  // #ifdef H5
  const doc = new DOMParser().parseFromString(payFormHtml.trim(), 'text/html');
  const src = doc.querySelector('form');
  if (!src) {
    throw new Error('支付宝支付表单无效');
  }
  const action = (src.getAttribute('action') || '').trim();
  if (!isAllowedAlipayAction(action)) {
    throw new Error('支付宝支付地址无效');
  }
  const form = document.createElement('form');
  form.method = 'POST';
  form.action = action;
  form.acceptCharset = 'utf-8';
  form.style.display = 'none';
  const nodes = Array.from(src.querySelectorAll('input')).slice(0, 64);
  for (const node of nodes) {
    const name = (node.getAttribute('name') || '').trim();
    if (!name || name.length > 128) continue;
    const input = document.createElement('input');
    input.type = 'hidden';
    input.name = name;
    input.value = String(
      (node as HTMLInputElement).value || node.getAttribute('value') || ''
    ).slice(0, 8192);
    form.appendChild(input);
  }
  if (!form.querySelector('input')) {
    throw new Error('支付宝支付表单无效');
  }
  document.body.appendChild(form);
  form.submit();
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

/** H5：识别支付宝同步回跳，并导航到充值页（避免停在首页且无返回） */
export function redirectIfAlipayReturn(): boolean {
  // #ifdef H5
  try {
    const search = globalThis.location.search || '';
    const hash = globalThis.location.hash || '';
    const combined = `${search}\n${hash}`;
    const fromAlipay =
      /[?&]out_trade_no=/.test(combined) ||
      /[?&]trade_no=/.test(combined) ||
      /[?&]trade_status=/.test(combined) ||
      /[?&]alipay_return=1/.test(combined) ||
      /method=alipay\.trade/i.test(combined);
    if (!fromAlipay) return false;
    if (hash.includes('/pages/recharge/recharge')) return false;
    const page = takeAlipayReturnPage();
    const url = page.startsWith('/') ? page : `/${page}`;
    uni.reLaunch({ url });
    return true;
  } catch {
    return false;
  }
  // #endif
  // #ifndef H5
  return false;
  // #endif
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
  const orderId = peekPendingRechargeOrder();
  if (!orderId) return false;
  try {
    // 短轮询：覆盖沙箱收银台回跳后的短暂延迟；未支付则静默保留 pending
    await pollRechargePaid(orderId, 8, 1500);
    clearPendingRechargeOrder();
    uni.showToast({ title: '充值已到账', icon: 'success' });
    return true;
  } catch (e) {
    const msg = e instanceof Error ? e.message : '充值确认失败';
    // 未完成支付 / 网络抖动：保留 pending，不弹干扰 toast
    if (/超时|timeout|无法连接|网络|request:fail/i.test(msg)) {
      return false;
    }
    // 已取消/关闭：清掉 pending，避免每次进「我的」都弹错误
    if (/取消|关闭|CANCELLED|REFUNDED/i.test(msg)) {
      clearPendingRechargeOrder();
      return false;
    }
    // 订单不存在等：清掉脏 pending
    if (/不存在|404|NOT_FOUND/i.test(msg)) {
      clearPendingRechargeOrder();
      return false;
    }
    // 开发阶段：避免反复弹错误；仅保留一次轻提示并可手动清理
    const softKey = `recharge_resume_soft_${orderId}`;
    if (!uni.getStorageSync(softKey)) {
      uni.setStorageSync(softKey, '1');
      uni.showToast({ title: '有一笔充值待确认，稍后刷新余额即可', icon: 'none', duration: 2500 });
    }
    return false;
  }
}

type WxPayLike = {
  timeStamp?: string;
  timestamp?: string;
  nonceStr?: string;
  packageValue?: string;
  package?: string;
  signType?: string;
  paySign?: string;
  debugInfo?: Record<string, string>;
};

function wxPayMode(prepay: { debugInfo?: Record<string, string>; wxPay?: WxPayLike }) {
  return String(prepay.debugInfo?.mode || prepay.wxPay?.debugInfo?.mode || '').toLowerCase();
}

/** 调起微信收银台（仅小程序 live 可用） */
export function invokeWxRequestPayment(wxPay: WxPayLike): Promise<void> {
  return new Promise((resolve, reject) => {
    // #ifdef MP-WEIXIN
    const pkg = wxPay.packageValue || wxPay.package || '';
    uni.requestPayment({
      provider: 'wxpay',
      timeStamp: String(wxPay.timeStamp || wxPay.timestamp || ''),
      nonceStr: String(wxPay.nonceStr || ''),
      package: pkg,
      signType: (wxPay.signType as 'RSA' | 'MD5' | undefined) || 'RSA',
      paySign: String(wxPay.paySign || ''),
      success: () => resolve(),
      fail: (err) => reject(new Error(err?.errMsg || '微信支付取消或失败'))
    });
    // #endif
    // #ifndef MP-WEIXIN
    reject(new Error('真实微信支付仅支持微信小程序；H5 请使用「微信模拟充值」'));
    // #endif
  });
}

/**
 * 微信充值：
 * - live + 小程序：requestPayment + 轮询到账
 * - mock / H5：预下单后 mock-success 即时到账（本地可测）
 */
export async function runWeChatRecharge(
  amountCents: number,
  idempotencyKey: string
): Promise<{
  orderId: string;
  mode: 'mock' | 'live';
}> {
  const prepay = await consumerApi.createRechargePrepay('WECHAT', amountCents, idempotencyKey);
  const mode = wxPayMode(prepay) === 'live' ? 'live' : 'mock';
  if (mode === 'live' && prepay.wxPay) {
    savePendingRechargeOrder(prepay.orderId);
    try {
      await invokeWxRequestPayment(prepay.wxPay as WxPayLike);
      await pollRechargePaid(prepay.orderId, 20, 1500);
      clearPendingRechargeOrder();
    } catch (e) {
      // 保留 pending，返回页可 resume；用户取消则不强制清
      throw e;
    }
    return { orderId: prepay.orderId, mode };
  }
  await consumerApi.confirmMockRecharge(prepay.orderId);
  return { orderId: prepay.orderId, mode: 'mock' };
}

/**
 * 支付宝充值：
 * - live：跳转表单/链接（沙箱进件）
 * - mock：预下单后走统一 mock-success 即时到账（无真实进件）
 */
export async function runAlipayRecharge(
  amountCents: number,
  idempotencyKey: string
): Promise<{
  orderId: string;
  mode: 'mock' | 'live';
}> {
  const prepay = await consumerApi.createRechargePrepay('ALIPAY', amountCents, idempotencyKey);
  const mode = String(prepay.debugInfo?.mode || '').toLowerCase() === 'live' ? 'live' : 'mock';
  if (mode === 'live') {
    if (!prepay.alipayPay?.payFormHtml && !prepay.alipayPay?.payUrl) {
      throw new Error('未获取到支付宝支付链接，请检查沙箱配置');
    }
    savePendingRechargeOrder(prepay.orderId);
    openAlipayPrepay(prepay.alipayPay);
    return { orderId: prepay.orderId, mode };
  }
  await consumerApi.confirmMockRecharge(prepay.orderId);
  return { orderId: prepay.orderId, mode: 'mock' };
}
