/** H5 首屏隐私同意：两端共用存储键与判定（运行时探测，不依赖条件编译）。 */

export const PRIVACY_CONSENT_KEY = 'aicabinet_privacy_consent_v1';

export function isH5PrivacyRuntime(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof navigator !== 'undefined' &&
    !/miniProgram|miniprogram/i.test(navigator.userAgent)
  );
}

export function hasPrivacyConsent(): boolean {
  try {
    return uni.getStorageSync(PRIVACY_CONSENT_KEY) === '1';
  } catch {
    return false;
  }
}

export function acceptPrivacyConsent(): void {
  uni.setStorageSync(PRIVACY_CONSENT_KEY, '1');
}

/** H5 且未同意时需要弹出首屏隐私窗。 */
export function shouldShowPrivacyConsent(): boolean {
  return isH5PrivacyRuntime() && !hasPrivacyConsent();
}

/* ── 微信小程序端（2026-10-21 隐私合规接入）───────────────────────────
 * 背景与取证：官方《小程序隐私协议开发指南》——
 *  1) 2023-10-17 起隐私功能强制启用：仅指引中声明过的接口可调用，未声明直接禁用（errno 112）；
 *  2) 未注册 onNeedPrivacyAuthorization 时，调用隐私接口由**微信官方默认弹窗**兜底（同意→继续/拒绝→报错），
 *     因此「不接入」不会导致接口被拦——接入的价值是首屏主动提示 + 品牌一致 UI（官方推荐姿势）。
 *  3) 主动查询用 wx.getPrivacySetting().needAuthorization；同步同意用
 *     <button open-type="agreePrivacyAuthorization">（见 privacy-consent-modal.vue）。
 * 探测约定沿用本仓「globalThis 运行时探测，不依赖条件编译」（同 use-network-status.ts）。 */

type WxPrivacyApi = {
  getPrivacySetting?: (o: {
    success?: (res: { needAuthorization?: boolean; privacyContractName?: string }) => void;
    fail?: () => void;
  }) => void;
};

function wxPrivacyApi(): WxPrivacyApi | undefined {
  const w = (globalThis as Record<string, unknown>).wx;
  return w && typeof w === 'object' ? (w as WxPrivacyApi) : undefined;
}

/**
 * 微信小程序运行时判定。
 *
 * 🔴 「有 globalThis.wx」绝不能当 mp 判据：uni-app 的 **H5** 运行时把 uni API 别名挂到
 * `globalThis.wx`（实测键含 `$emit`/`addInterceptor`/`canvasGetImageData`…，但**无**
 * `getPrivacySetting`）。首版只判 wx 存在 ⇒ H5 被判成 mp ⇒ use-privacy-consent 走 mp 分支
 * 永不弹首屏窗，而 request.ts 的 H5 门禁照旧拦（PRIVACY_CONSENT_REQUIRED）⇒ 整站 H5 请求死锁，
 * CI consumer UAT 的 captcha 请求根本没发出（表现为「6 次未取到 2xx」，被文案误报成 429 限流）。
 * 两道独立排除：DOM 存在=H5；官方隐私接口缺失=非 mp（低版本基础库由微信官方弹窗兜底）。
 */
export function isWeixinMpRuntime(): boolean {
  if (typeof (globalThis as Record<string, unknown>).window !== 'undefined') {
    return false;
  }
  return typeof wxPrivacyApi()?.getPrivacySetting === 'function';
}

/**
 * mp 端是否该弹首屏隐私窗 = 官方 needAuthorization（该用户尚未同意当前版指引）。
 * 无 wx / 低版本基础库无此 API / 查询失败 → 一律 false 不弹（漏网场景由微信官方默认弹窗兜底）。
 */
export function shouldShowMpPrivacyConsent(): Promise<boolean> {
  return new Promise((resolve) => {
    const getSetting = wxPrivacyApi()?.getPrivacySetting;
    if (typeof getSetting !== 'function') {
      resolve(false);
      return;
    }
    try {
      getSetting({
        success: (res) => resolve(res?.needAuthorization === true),
        fail: () => resolve(false)
      });
    } catch {
      resolve(false);
    }
  });
}
