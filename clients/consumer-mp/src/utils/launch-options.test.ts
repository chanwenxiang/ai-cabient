import { describe, expect, it } from 'vitest';
import { parseLaunchOptions } from '@aicabinet/shared-uni/qrcode';

const DEVICE = 'EG858564255060';
const URL_UNDER_RULE = `https://cabinet.easygovm.com/o/${DEVICE}`;

describe('parseLaunchOptions 扫码启动参数', () => {
  it('微信「扫普通链接二维码打开小程序」走 q（URL 编码）', () => {
    const launch = parseLaunchOptions({ q: encodeURIComponent(URL_UNDER_RULE) });
    expect(launch.deviceId).toBe(DEVICE);
  });

  it('支付宝「关联普通链接二维码」走 qrCode（未编码的完整地址）', () => {
    const launch = parseLaunchOptions({ qrCode: URL_UNDER_RULE });
    expect(launch.deviceId).toBe(DEVICE);
    // qrCode 只由支付宝注入，地址里不含渠道词 ⇒ 必须兜底 ALIPAY，否则 entryChannel 记账为空
    expect(launch.channel).toBe('ALIPAY');
  });

  it('支付宝 qrCode 被 URL 编码时同样能解出柜机号', () => {
    const launch = parseLaunchOptions({ qrCode: encodeURIComponent(URL_UNDER_RULE) });
    expect(launch.deviceId).toBe(DEVICE);
  });

  it('支付宝 qrCode 带自定义参数时仍取 URL 里的柜机号', () => {
    const launch = parseLaunchOptions({ qrCode: `${URL_UNDER_RULE}?from=poster` });
    expect(launch.deviceId).toBe(DEVICE);
  });

  it('两个字段都没有 → 不凭空造柜机号（不得误开门）', () => {
    expect(parseLaunchOptions({}).deviceId).toBe('');
    expect(parseLaunchOptions({ scene: 'foo' }).deviceId).toBe('');
  });

  it('qrCode 是不含柜机号的地址 → 解析为空，不得落到手输以外的行为', () => {
    expect(parseLaunchOptions({ qrCode: 'https://cabinet.easygovm.com/about' }).deviceId).toBe('');
  });
});
