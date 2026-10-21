import { describe, expect, it } from 'vitest';
import { resolvePromoSlot, type PromoSlotInput } from './promo-slot';

/**
 * 首页推广位来源选择的判据。
 *
 * 这几条守的是**优先级**，不是「有没有实现」：本项最容易出的错是顺序写反
 * （腾讯广告盖掉自家活动）或闸门用错（未加载完成就闪占位图），两者都只在
 * 特定时序下出现，靠肉眼很难稳定复现。
 */
function input(partial: Partial<PromoSlotInput> = {}): PromoSlotInput {
  return { hasOwnContent: false, wxAdAvailable: false, ...partial };
}

describe('首页推广位来源选择', () => {
  it('有自有投放 ⇒ 一定选自有，腾讯广告不得抢占', () => {
    expect(resolvePromoSlot(input({ hasOwnContent: true }))).toBe('self');
    expect(resolvePromoSlot(input({ hasOwnContent: true, wxAdAvailable: true }))).toBe('self');
  });

  it('无自有投放 + 腾讯广告可用 ⇒ 选腾讯广告', () => {
    expect(resolvePromoSlot(input({ wxAdAvailable: true }))).toBe('wxAd');
  });

  it('两者都没有 ⇒ 整块不渲染（占位图已移除，留空比留「未配置」更干净）', () => {
    expect(resolvePromoSlot(input())).toBe('none');
  });

  it('腾讯广告不依赖我们的接口 ⇒ 即使自有素材还在请求中也能出广告', () => {
    expect(resolvePromoSlot(input({ wxAdAvailable: true }))).toBe('wxAd');
  });

  it('有自有内容 ⇒ 仍是自有', () => {
    expect(resolvePromoSlot(input({ hasOwnContent: true }))).toBe('self');
  });
});
