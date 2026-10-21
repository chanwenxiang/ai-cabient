import { afterEach, describe, expect, it } from 'vitest';
import {
  isWeixinMpRuntime,
  shouldShowMpPrivacyConsent
} from '@aicabinet/shared-uni/privacy-consent';

/**
 * mp 隐私门禁（2026-10-21 隐私合规接入）：
 * - 无 wx（admin/node）→ 非 mp 运行时，恒不弹；
 * - 🔴 uni-app **H5** 运行时也有 globalThis.wx（uni API 别名，无 getPrivacySetting）⇒ 必须判 false，
 *   否则 use-privacy-consent 走 mp 分支不弹首屏窗，而 request 层 H5 门禁照旧拦 ⇒ 整站 H5 死锁
 *   （CI run 933 consumer UAT 的 captcha 请求从不发出，即此因）；
 * - 低版本基础库无 getPrivacySetting → 非 mp 门禁，不弹（微信官方默认弹窗兜底）；
 * - needAuthorization=true（未同步当前版指引）→ 弹；false（已同步）→ 不弹；
 * - 查询抛错/失败 → 不弹不抛。
 */
describe('privacy-consent mp 门禁（官方 getPrivacySetting）', () => {
  const g = globalThis as { wx?: unknown; window?: unknown };
  const prevWx = g.wx;
  const prevWindow = g.window;

  afterEach(() => {
    g.wx = prevWx;
    g.window = prevWindow;
  });

  /**
   * 模拟浏览器 H5 的 DOM 存在性。navigator 不 stub：Node 24 自带只读 globalThis.navigator
   * （赋值会 TypeError），且其 UA 不含 miniProgram，正是 isH5PrivacyRuntime 需要的另一半。
   */
  function asBrowserH5() {
    g.window = {};
  }

  it('无 wx（admin/node）→ 非 mp 运行时且恒不弹', async () => {
    g.wx = undefined;
    expect(isWeixinMpRuntime()).toBe(false);
    await expect(shouldShowMpPrivacyConsent()).resolves.toBe(false);
  });

  it('uni-app H5 的 wx 别名对象（有 uni API、无 getPrivacySetting）→ 判 false，不抢 mp 分支', () => {
    asBrowserH5();
    g.wx = { $emit() {}, addInterceptor() {}, canvasGetImageData() {} };
    expect(isWeixinMpRuntime()).toBe(false);
  });

  it('真 mp 运行时（无 DOM）但低版本基础库无 getPrivacySetting → 判 false', () => {
    g.window = undefined;
    g.wx = {};
    expect(isWeixinMpRuntime()).toBe(false);
  });

  it('浏览器 H5 即使拿到 getPrivacySetting 也判 false（与 request 层 H5 门禁同一判据）', () => {
    asBrowserH5();
    g.wx = { getPrivacySetting: () => {} };
    expect(isWeixinMpRuntime()).toBe(false);
  });

  it('needAuthorization=true（该用户未同意当前版指引）→ 弹', async () => {
    g.wx = {
      getPrivacySetting: (o: {
        success?: (res: { needAuthorization: boolean; privacyContractName?: string }) => void;
      }) => o.success?.({ needAuthorization: true, privacyContractName: '《测试指引》' })
    };
    expect(isWeixinMpRuntime()).toBe(true);
    await expect(shouldShowMpPrivacyConsent()).resolves.toBe(true);
  });

  it('needAuthorization=false（已同步过）→ 不弹', async () => {
    g.wx = {
      getPrivacySetting: (o: { success?: (res: { needAuthorization: boolean }) => void }) =>
        o.success?.({ needAuthorization: false })
    };
    await expect(shouldShowMpPrivacyConsent()).resolves.toBe(false);
  });

  it('查询同步抛错 / 回调 fail → 不弹且不抛', async () => {
    g.wx = {
      getPrivacySetting: () => {
        throw new Error('boom');
      }
    };
    await expect(shouldShowMpPrivacyConsent()).resolves.toBe(false);
    g.wx = {
      getPrivacySetting: (o: { fail?: () => void }) => o.fail?.()
    };
    await expect(shouldShowMpPrivacyConsent()).resolves.toBe(false);
  });
});
