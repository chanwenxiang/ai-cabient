import { consumerApi, isConsumerLoggedIn } from './consumer-api';

/**
 * 邀请码落地（营销 2 / V501）。
 *
 * <p>链路：好友点分享卡（`/pages/marketing/index?invite=XXXX`）→ 本地记住 → 登录/已登录时绑定。
 * 为什么不在落地页直接绑：游客态没有 token，绑了也认不出人；而「先记住、登录后补绑」还能覆盖
 * 「分享 → 中途去登录 → 回来」这条最常见的断点。
 *
 * <p>存储带 7 天过期：邀请码是拉新凭据，不该在用户手机里躺半年 —— 过期后绑定会变成
 * 「一个早已结束的活动还在发券」。
 */

const STORAGE_KEY = 'consumer.pendingInviteCode';
const TTL_MS = 7 * 24 * 60 * 60 * 1000;
/** 后端邀请码是 8 位大写字母数字；放宽到 4-64 是为了兼容手工输入的短码/长码，不在这里当校验器 */
const CODE_RE = /^[0-9A-Za-z]{4,64}$/;

interface PendingInvite {
  code: string;
  at: number;
}

/** 记下分享卡带来的邀请码（重复进入以最后一次为准）。非法格式直接忽略，不打断浏览。 */
export function rememberInviteCode(raw?: string | null): void {
  const code = String(raw ?? '')
    .trim()
    .toUpperCase();
  if (!CODE_RE.test(code)) return;
  try {
    uni.setStorageSync(STORAGE_KEY, JSON.stringify({ code, at: Date.now() } as PendingInvite));
  } catch {
    /* 本地存储不可用（隐私模式/配额满）时静默：宁可丢归因，不影响浏览 */
  }
}

/** 取出待绑码（顺带做过期清理）。 */
export function readPendingInviteCode(): string {
  try {
    const raw = uni.getStorageSync(STORAGE_KEY);
    if (!raw) return '';
    const parsed = JSON.parse(String(raw)) as PendingInvite;
    if (!parsed?.code || !Number.isFinite(parsed.at)) {
      clearPendingInviteCode();
      return '';
    }
    if (Date.now() - parsed.at > TTL_MS) {
      clearPendingInviteCode();
      return '';
    }
    return parsed.code;
  } catch {
    return '';
  }
}

export function clearPendingInviteCode(): void {
  try {
    uni.removeStorageSync(STORAGE_KEY);
  } catch {
    /* 同上：清不掉也不影响后续覆盖写 */
  }
}

/**
 * 登录态下补绑（fire-and-forget 调用点：登录成功、营销页 onShow）。
 *
 * <p>400/404/409 都是**不可重试**的终态（参数不合法 / 码无效 / 已绑过或已有消费记录），
 * 必须把本地码清掉，否则每次登录都会再撞一次同样的错误。
 */
export async function flushPendingInvite(): Promise<void> {
  if (!isConsumerLoggedIn()) return;
  const code = readPendingInviteCode();
  if (!code) return;
  try {
    await consumerApi.bindMemberInvite(code);
    clearPendingInviteCode();
  } catch (e) {
    const status = (e as { status?: number } | null)?.status;
    if (status === 400 || status === 404 || status === 409) {
      clearPendingInviteCode();
    }
  }
}
