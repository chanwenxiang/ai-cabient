/** 微信订阅消息模板 ID，在小程序后台配置后填入 .env.development */
const SUBSCRIBE_TMPL_IDS = (import.meta.env.VITE_WX_SUBSCRIBE_TMPL_IDS || '')
  .split(',')
  .map((s: string) => s.trim())
  .filter(Boolean);

function mergeSubscribeTmplIds(extra?: string[]): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const id of [...SUBSCRIBE_TMPL_IDS, ...(extra || [])]) {
    const trimmed = String(id || '').trim();
    if (!trimmed || seen.has(trimmed)) continue;
    seen.add(trimmed);
    out.push(trimmed);
  }
  return out;
}

/** 统一 Toast 时长（毫秒） */
export const TOAST_DURATION_MS = 2000;
export const TOAST_DURATION_LONG_MS = 2500;

export type ToastIcon = 'success' | 'error' | 'loading' | 'none';

export function showAppToast(
  title: string,
  options?: { icon?: ToastIcon; duration?: number; mask?: boolean }
) {
  const text = String(title || '').trim() || '操作失败';
  uni.showToast({
    title: text.length > 20 ? `${text.slice(0, 19)}…` : text,
    icon: options?.icon ?? 'none',
    duration: options?.duration ?? TOAST_DURATION_MS,
    mask: options?.mask ?? false
  });
}

export function showSuccess(title: string, duration = TOAST_DURATION_MS) {
  showAppToast(title, { icon: 'success', duration });
}

export function showError(title: string, duration = TOAST_DURATION_LONG_MS) {
  showAppToast(title, { icon: 'none', duration });
}

/**
 * 长文案错误展示（2026-10-23 审计 M3）：≤20 字走 toast（不打断），
 * 更长走 showModal 全文——toast 会被平台截成 19 字，后端业务驳回理由
 * （实名/仲裁指引/距离说明等 50 字级）全文不可见
 * （铁律教训：≥20 字的拒绝原因一律别走 toast，参照 WalletPage banner 先例）。
 * @param errOrMsg Error 实例或已提取的消息串；空值回落 fallback
 * @param duration 短文案走 toast 时的时长（长文案走 modal，无时长概念）
 */
export function showLongError(errOrMsg: unknown, fallback: string, duration?: number): void {
  let text =
    errOrMsg instanceof Error && errOrMsg.message
      ? errOrMsg.message
      : typeof errOrMsg === 'string' && errOrMsg.trim()
        ? errOrMsg
        : '';
  if (!text) text = fallback;
  if (text.length <= 20) {
    showError(text, duration);
    return;
  }
  uni.showModal({
    title: '操作未完成',
    content: text,
    showCancel: false,
    confirmText: '我知道了'
  });
}

export function showConfirm(options: {
  title?: string;
  content: string;
  confirmText?: string;
  cancelText?: string;
  showCancel?: boolean;
}): Promise<boolean> {
  return new Promise((resolve) => {
    uni.showModal({
      title: options.title || '确认',
      content: options.content,
      confirmText: options.confirmText || '确定',
      cancelText: options.cancelText || '取消',
      showCancel: options.showCancel !== false,
      success: (res) => resolve(!!res.confirm),
      fail: () => resolve(false)
    });
  });
}

function canSubscribeMessage() {
  return typeof uni !== 'undefined' && typeof uni.requestSubscribeMessage === 'function';
}

export const MERCHANT_ALERT_TYPES = [
  { value: 'DISPUTE', label: '争议待审' },
  { value: 'DEVICE_OFFLINE', label: '柜机离线' },
  { value: 'SALES_LOCKED', label: '柜机停售' },
  { value: 'LOW_STOCK', label: '低库存' },
  { value: 'EXPIRY', label: '临期下架' },
  { value: 'SLOT_DISCREPANCY', label: '货道差异' },
  { value: 'REPLENISHMENT', label: '补货任务' },
  { value: 'EXCEPTION', label: '识别/故障异常' }
] as const;

/** True when compile-time and/or runtime WeChat subscribe template IDs exist. */
export function hasSubscribeTemplates(extra?: string[]) {
  return mergeSubscribeTmplIds(extra).length > 0;
}

/**
 * 请求微信订阅授权（消费端）：无模板或非微信环境时静默跳过。
 * @param extra 运行时模板 ID（后端 SystemConfig 的 wechatSubscribeTemplateId）——
 *   编译期 VITE_WX_SUBSCRIBE_TMPL_IDS 与运行期配置合并，任一有值即请求
 *   （2026-10-23 第五轮审计：此前只读编译期 env，而 consumer-mp 全链路未配置该 env，
 *   订阅授权 100% 静默失效）。
 */
export async function requestOrderSubscribe(extra?: string[]) {
  const tmplIds = mergeSubscribeTmplIds(extra);
  if (!canSubscribeMessage() || !tmplIds.length) return;
  await new Promise<void>((resolve) => {
    uni.requestSubscribeMessage({
      tmplIds,
      complete: () => resolve()
    });
  });
}

/**
 * 请求微信订阅授权（商户端）。
 * @returns `'ok' | 'skipped' | 'failed'`
 */
export async function requestMerchantSubscribe(
  extra?: string[]
): Promise<'ok' | 'skipped' | 'failed'> {
  const tmplIds = mergeSubscribeTmplIds(extra);
  if (!canSubscribeMessage() || !tmplIds.length) return 'skipped';
  return await new Promise((resolve) => {
    uni.requestSubscribeMessage({
      tmplIds,
      success: () => resolve('ok'),
      fail: () => resolve('failed'),
      complete: () => {}
    });
  });
}

export function wxLoginCode(): Promise<string> {
  return new Promise((resolve, reject) => {
    if (typeof uni === 'undefined' || typeof uni.login !== 'function') {
      reject(new Error('仅微信小程序可绑定提醒'));
      return;
    }
    uni.login({
      provider: 'weixin',
      success: (res) => {
        if (res.code) resolve(res.code);
        else reject(new Error('未获取到微信登录码'));
      },
      fail: (err) => reject(new Error('微信登录失败'))
    });
  });
}

export function showBillToast(totalCents: number) {
  const yuan = (totalCents / 100).toFixed(2);
  const title = totalCents <= 0 ? '本次未消费' : `已扣款 ¥${yuan}`;
  uni.showToast({
    title,
    icon: totalCents <= 0 ? 'none' : 'success',
    duration: TOAST_DURATION_MS
  });
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
  uni.showToast({ title, icon: 'success', duration: TOAST_DURATION_LONG_MS });
}

export function delay(ms: number) {
  return new Promise<void>((resolve) => setTimeout(resolve, ms));
}
