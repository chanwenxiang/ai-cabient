import { afterEach, describe, expect, it } from 'vitest';
import {
  isWeixinMpRuntime,
  shouldShowMpPrivacyConsent
} from '@aicabinet/shared-uni/privacy-consent';

/**
 * mp 隐私门禁（2026-10-21 隐私合规接入）：
 * - 无 wx（H5/admin）→ 非 mp 运行时，恒不弹；
 * - 低版本基础库无 getPrivacySetting → 不弹（微信官方默认弹窗兜底）；
 * - needAuthorization=true（未同步当前版指引）→ 弹；false（已同步）→ 不弹；
 * - 查询抛错/失败 → 不弹不抛。
 */
describe('privacy-consent mp 门禁（官方 getPrivacySetting）', () => {
  const g = globalThis as { wx?: unknown };
  const prevWx = g.wx;

  afterEach(() => {
    g.wx = prevWx;
  });

  it('无 wx（H5/admin）→ 非 mp 运行时且恒不弹', async () => {
    g.wx = undefined;
    expect(isWeixinMpRuntime()).toBe(false);
    await expect(shouldShowMpPrivacyConsent()).resolves.toBe(false);
  });

  it('低版本基础库（无 getPrivacySetting）→ 判定为 mp 但不弹', async () => {
    g.wx = {};
    expect(isWeixinMpRuntime()).toBe(true);
    await expect(shouldShowMpPrivacyConsent()).resolves.toBe(false);
  });

  it('needAuthorization=true（该用户未同意当前版指引）→ 弹', async () => {
    g.wx = {
      getPrivacySetting: (o: {
        success?: (res: { needAuthorization: boolean; privacyContractName?: string }) => void;
      }) => o.success?.({ needAuthorization: true, privacyContractName: '《测试指引》' })
    };
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
