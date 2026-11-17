import { describe, expect, it } from 'vitest';
import { decideNavAccess, type NavPerm } from '@/router/nav-access';

/**
 * MA-317：admin 路由守卫的权限判定。
 *
 * 关键回归是第 1 条：`/print` 只在 menu.ts 声明 `perms`（没有 `perm`），
 * 修前守卫的 `requiredPerm = meta.perm || nav?.perm` 恒为空、第二条判据又以
 * `nav?.perm` 为前提 ⇒ 任意登录用户地址栏直入即可打开。
 */

const PRINT_NAV: NavPerm = {
  perms: ['ops:warehouse:list', 'ops:sku:list', 'ops:procurement:list']
};

/** 与 store 的 canAccessNav 同语义的测试替身：perms 为 OR，perm 为单码，都没有则放行。 */
function navAccessFor(held: string[]) {
  return (nav: NavPerm) => {
    if (!nav) return false;
    const required = nav.perms?.length ? nav.perms : nav.perm ? [nav.perm] : [];
    if (!required.length) return true;
    return required.some((code) => held.includes(code));
  };
}

function decide(opts: {
  metaPerm?: string;
  nav?: NavPerm;
  held?: string[];
  canAccessNav?: (nav: NavPerm) => boolean;
}) {
  const held = opts.held ?? [];
  return decideNavAccess({
    metaPerm: opts.metaPerm ?? '',
    nav: opts.nav,
    hasPerm: (perm) => held.includes(perm),
    canAccessNav: opts.canAccessNav ?? navAccessFor(held)
  });
}

describe('decideNavAccess（MA-317）', () => {
  it('🔴 perms-only 隐藏页（/print）：一个码都没有 ⇒ 拒绝（修前恒放行）', () => {
    expect(decide({ nav: PRINT_NAV, held: [] })).toBe('denied');
  });

  it('perms-only 隐藏页：命中 OR 集合里任意一个 ⇒ 放行', () => {
    expect(decide({ nav: PRINT_NAV, held: ['ops:sku:list'] })).toBe('ok');
    expect(decide({ nav: PRINT_NAV, held: ['ops:procurement:list'] })).toBe('ok');
  });

  it('既无菜单项也无 meta.perm ⇒ unregistered（fail-closed 原语义不变）', () => {
    expect(decide({ nav: undefined, held: ['ops:device:list'] })).toBe('unregistered');
  });

  it('meta.perm 单码缺失 ⇒ denied（旧语义保留）', () => {
    expect(decide({ metaPerm: 'ops:fund:list', nav: undefined, held: [] })).toBe('denied');
    expect(decide({ metaPerm: 'ops:fund:list', nav: undefined, held: ['ops:fund:list'] })).toBe(
      'ok'
    );
  });

  it('nav.perm 单码缺失 ⇒ denied', () => {
    expect(decide({ nav: { perm: 'ops:dashboard:view' }, held: ['ops:device:list'] })).toBe(
      'denied'
    );
  });

  it('没有 perm 也没有 perms 的菜单项不误伤（补货员等落页仍按原逻辑）', () => {
    expect(decide({ nav: { perm: undefined }, held: [] })).toBe('ok');
  });

  it('🔴 持有功能码但菜单被停用 ⇒ denied：证明 canAccessNav 是无条件调用的，不再挂在 nav?.perm 上', () => {
    expect(
      decide({
        nav: { perm: 'ops:sku:list', perms: ['ops:sku:list'] },
        held: ['ops:sku:list'],
        canAccessNav: () => false
      })
    ).toBe('denied');
  });
});
