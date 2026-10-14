/**
 * 触感反馈（用户可在「我的 → 偏好设置」关闭，默认开）。
 *
 * 存储键本机生效、不上云；读取异常按「开」处理（fail-open：反馈缺失
 * 不影响功能，回归到无震动旧行为）。写入异常静默——本次会话内开关
 * 仍由页面内存态生效，下次冷启动回默认。
 */
const STORAGE_KEY = 'consumer_haptic_enabled';

export function isHapticEnabled(): boolean {
  try {
    const v = uni.getStorageSync(STORAGE_KEY);
    return v !== 0 && v !== '0';
  } catch {
    return true;
  }
}

export function setHapticEnabled(enabled: boolean): void {
  try {
    uni.setStorageSync(STORAGE_KEY, enabled ? 1 : 0);
  } catch {
    /* ignore：存储失败不致命 */
  }
}

/** 轻震动反馈；开关关闭或机型不支持时静默。 */
export function hapticLight(): void {
  if (!isHapticEnabled()) return;
  try {
    uni.vibrateShort({ type: 'light' });
  } catch {
    /* 部分平台/机型不支持 vibrateShort，静默 */
  }
}
