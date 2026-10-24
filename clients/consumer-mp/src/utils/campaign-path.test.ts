import { describe, expect, it } from 'vitest';
import { isSafeCampaignPath } from './campaign-path';

describe('isSafeCampaignPath', () => {
  it('放行本小程序页面路径（含 query）', () => {
    expect(isSafeCampaignPath('/pages/coupons/coupons')).toBe(true);
    expect(isSafeCampaignPath('/pages/coupons/coupons?from=banner')).toBe(true);
  });

  it('拒绝空值与非页面路径', () => {
    expect(isSafeCampaignPath(undefined)).toBe(false);
    expect(isSafeCampaignPath(null)).toBe(false);
    expect(isSafeCampaignPath('')).toBe(false);
    expect(isSafeCampaignPath('pages/coupons/coupons')).toBe(false);
  });

  it('拒绝外链与协议跳转', () => {
    expect(isSafeCampaignPath('https://evil.example.com/x')).toBe(false);
    expect(isSafeCampaignPath('javascript:alert(1)')).toBe(false);
    expect(isSafeCampaignPath('weixin://open')).toBe(false);
  });

  it('拒绝空白与反斜杠', () => {
    expect(isSafeCampaignPath('/pages/coupons/coupons id=1')).toBe(false);
    expect(isSafeCampaignPath('/pages\\coupons\\coupons')).toBe(false);
  });

  it('两侧空白 trim 后仍可判定', () => {
    expect(isSafeCampaignPath('  /pages/coupons/coupons  ')).toBe(true);
  });
});
