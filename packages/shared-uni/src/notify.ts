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
  { value: 'REPLENISHMENT', label: '补货任务' },
  { value: 'EXCEPTION', label: '识别/故障异常' }
] as const;

/** True when compile-time and/or runtime WeChat subscribe template IDs exist. */
export function hasSubscribeTemplates(extra?: string[]) {
  return mergeSubscribeTmplIds(extra).length > 0;
}

export type SubscribeTemplateStatus = 'accept' | 'reject' | 'skipped' | 'failed';

export interface SubscribeTemplateResult {
  tmplId: string;
  status: SubscribeTemplateStatus;
}

export interface SubscribeRequestResult {
  /** 汇总状态只用于展示；逐模板决策必须读 items，不能把 success 回调当成全部同意。 */
  status: 'ok' | 'partial' | 'rejected' | 'skipped' | 'failed';
  items: SubscribeTemplateResult[];
}

function summarizeSubscribe(items: SubscribeTemplateResult[]): SubscribeRequestResult['status'] {
  if (!items.length || items.every((item) => item.status === 'skipped')) return 'skipped';
  if (items.every((item) => item.status === 'accept')) return 'ok';
  if (items.every((item) => item.status === 'reject')) return 'rejected';
  if (items.every((item) => item.status === 'failed')) return 'failed';
  return 'partial';
}

function skippedSubscribeResult(tmplIds: string[]): SubscribeRequestResult {
  const items = tmplIds.map((tmplId) => ({ tmplId, status: 'skipped' as const }));
  return { status: 'skipped', items };
}

async function requestSubscribe(extra?: string[]): Promise<SubscribeRequestResult> {
  const tmplIds = mergeSubscribeTmplIds(extra);
  if (!canSubscribeMessage() || !tmplIds.length) return skippedSubscribeResult(tmplIds);
  return await new Promise((resolve) => {
    try {
      uni.requestSubscribeMessage({
        tmplIds,
        success: (res) => {
          const receipts = (res || {}) as unknown as Record<string, unknown>;
          const items = tmplIds.map((tmplId) => {
            const receipt = receipts[tmplId];
            const status: SubscribeTemplateStatus =
              receipt === 'accept' || receipt === 'reject' ? receipt : 'failed';
            return { tmplId, status };
          });
          resolve({ status: summarizeSubscribe(items), items });
        },
        // 整个请求失败时每个模板都是 failed；不能伪造 accept/reject。
        fail: () => {
          const items = tmplIds.map((tmplId) => ({ tmplId, status: 'failed' as const }));
          resolve({ status: 'failed', items });
        }
      });
    } catch {
      const items = tmplIds.map((tmplId) => ({ tmplId, status: 'failed' as const }));
      resolve({ status: 'failed', items });
    }
  });
}

/**
 * 请求微信订阅授权（消费端）：逐模板返回微信 accept/reject 回执。
 * @param extra 运行时模板 ID（后端 SystemConfig 的 wechatSubscribeTemplateId）——
 *   编译期 VITE_WX_SUBSCRIBE_TMPL_IDS 与运行期配置合并，任一有值即请求。
 */
export function requestOrderSubscribe(extra?: string[]): Promise<SubscribeRequestResult> {
  return requestSubscribe(extra);
}

/** 请求微信订阅授权（商户端），与消费端共享逐模板回执模型。 */
export function requestMerchantSubscribe(extra?: string[]): Promise<SubscribeRequestResult> {
  return requestSubscribe(extra);
}

/** 已授权回执按模板 ID 独立持久化；后台换模板后不会误用旧授权。 */
const SUBSCRIBE_ACK_PREFIX = 'wechat_subscribe_ack_tmpl:';
const LEGACY_SUBSCRIBE_ACK_KEY = 'wechat_subscribe_ack_tmpl';

export function readSubscribeAck(tmplId: string): boolean {
  const id = String(tmplId || '').trim();
  if (!id) return false;
  try {
    return (
      uni.getStorageSync(SUBSCRIBE_ACK_PREFIX + id) === true ||
      String(uni.getStorageSync(LEGACY_SUBSCRIBE_ACK_KEY) || '') === id
    );
  } catch {
    return false;
  }
}

export function markSubscribeAck(tmplId: string): void {
  const id = String(tmplId || '').trim();
  if (!id) return;
  try {
    uni.setStorageSync(SUBSCRIBE_ACK_PREFIX + id, true);
  } catch {
    /* 存储不可用只影响本次会话，不阻断授权成功提示。 */
  }
}

/** 统一可见反馈文案；所有调用点都必须消费逐模板结果，不能只看汇总 status。 */
export function subscribeFeedbackText(
  result: SubscribeRequestResult,
  acceptedText = '消息提醒已开启'
): string {
  const counts = { accept: 0, reject: 0, skipped: 0, failed: 0 };
  for (const item of result.items) counts[item.status] += 1;
  if (counts.accept && counts.accept === result.items.length) return acceptedText;
  if (counts.reject === result.items.length && counts.reject > 0) {
    return '已拒绝提醒，可在设置中开启';
  }
  if (counts.skipped === result.items.length) return '提醒未开启：环境或模板不可用';
  if (counts.failed === result.items.length && counts.failed > 0) return '提醒授权失败，请重试';
  if (counts.accept) {
    return `部分授权：同意${counts.accept}项，拒绝${counts.reject}项，跳过${counts.skipped}项，失败${counts.failed}项`;
  }
  return `未授权：拒绝${counts.reject}项，跳过${counts.skipped}项，失败${counts.failed}项`;
}

export function showSubscribeFeedback(
  result: SubscribeRequestResult,
  acceptedText = '消息提醒已开启'
): void {
  const text = subscribeFeedbackText(result, acceptedText);
  if (result.status === 'ok') showSuccess(text);
  else if (text.length > 20) showLongError(text, '提醒授权结果');
  else showError(text);
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
      fail: () => reject(new Error('微信登录失败'))
    });
  });
}

/**
 * 结算完成提示。
 *
 * 🔴 2026-11-10 审计 P1-6：`totalCents` 传 `null` 表示「金额未知」（查询失败/弱网/尚未落单）。
 * 此时必须说「账单已生成，正在打开」，**不能**落到 `<= 0` 分支说「本次未消费」——
 * 用户刚被扣款却被告知没消费，是对资金事实的错误陈述，极易升级投诉。
 * 传 `0` 才是「确认零元单」。
 */
export function showBillToast(totalCents: number | null, orderStatus?: string | null) {
  if (totalCents == null || !Number.isFinite(totalCents)) {
    uni.showToast({
      title: '账单已生成，正在打开…',
      icon: 'none',
      duration: TOAST_DURATION_MS
    });
    return;
  }
  const yuan = (totalCents / 100).toFixed(2);
  // R4-C2：待补缴单（余额不足/支付分失败落 PENDING）并没有扣款 —— 不能说「已扣款」，
  // 那是对资金事实的错误陈述（R1-P1-6 的反向形态）。
  const pendingLike = orderStatus === 'PENDING' || orderStatus === 'PROCESSING';
  if (pendingLike && totalCents > 0) {
    uni.showToast({
      title: `订单已生成，待支付 ¥${yuan}`,
      icon: 'none',
      duration: TOAST_DURATION_MS
    });
    return;
  }
  const title = totalCents <= 0 ? '本次未消费' : `已扣款 ¥${yuan}`;
  // R9-化妆6：success 图标标题 ~7 全角字即裁切 —— 大额优先保证金额完整（资金事实）
  uni.showToast({
    title,
    icon: totalCents <= 0 || title.length > 9 ? 'none' : 'success',
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
