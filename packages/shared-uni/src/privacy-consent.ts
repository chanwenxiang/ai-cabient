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

/** 微信小程序运行时判定：H5/admin 环境无 wx → false（request 层 H5 门禁不受影响）。 */
export function isWeixinMpRuntime(): boolean {
  return wxPrivacyApi() !== undefined;
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
