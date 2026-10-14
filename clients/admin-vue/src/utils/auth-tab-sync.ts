import { beginLogout, clearSession, endLogout } from '@/api/client';
import { ElMessage } from 'element-plus';

/**
 * 多标签页会话同步（2026-10-14 审计补齐）。
 *
 * 🔴 为什么不能用 storage 事件：JWT 走 sessionStorage（A-P2-006，每标签页独立），
 * sessionStorage 变更不触发其他标签页的 storage 事件；Cookie 模式下 cookie 变更同样不可监听。
 * 故用 BroadcastChannel 广播：任一标签页 `logout()` → 其余标签页清会话 + 跳登录。
 * 不支持 BroadcastChannel 的环境静默跳过（回退现状：靠 401 兜底重定向）。
 */
const CHANNEL_NAME = 'admin-auth-sync';
let channel: BroadcastChannel | null = null;
let installed = false;

function supported(): boolean {
  return typeof BroadcastChannel === 'function';
}

export function installAuthTabSync(): void {
  if (installed || !supported()) return;
  installed = true;
  channel = new BroadcastChannel(CHANNEL_NAME);
  channel.onmessage = (ev: MessageEvent) => {
    if (ev.data !== 'logout') return;
    if (globalThis.location.pathname.includes('/login')) return;
    // 抑制在途请求的 401 提示（与主动登出同口径），清会话后整页跳登录（顺带重置内存态）
    beginLogout();
    clearSession();
    endLogout();
    ElMessage.warning('该账号已在其他窗口退出登录');
    globalThis.location.assign('/admin/login');
  };
}

/** 登出时广播给其他标签页（stores/auth.ts logout 调用）。 */
export function broadcastAuthLogout(): void {
  if (supported() && !channel) {
    channel = new BroadcastChannel(CHANNEL_NAME);
  }
  try {
    channel?.postMessage('logout');
  } catch {
    /* ignore */
  }
}
