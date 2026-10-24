import { describe, expect, it } from 'vitest';
import { existsSync } from 'node:fs';
import {
  MERCHANT_BIZ_NAV,
  MERCHANT_DOCS_NAV,
  MERCHANT_FIELD_NAV,
  MERCHANT_MORE_NAV,
  MERCHANT_TEAM_NAV,
  type MerchantNavItem,
  type MerchantPack
} from './merchant-nav';

/**
 * 菜单完整性门禁（2026-10-14 权限审计沉淀）：
 * 每个菜单项必须 perm+pack 双配置、URL 不带 query、key 全局唯一。
 * 权限码是否真实存在于后端由 scripts/check-frontend-perm-codes.mjs 把守，此处不重复。
 *
 * 2026-10-24 追加 icon 资产存在性断言：菜单 icon 经 mine.vue 的 menuIcon(item.icon)
 * 动态拼接成 /static/menu/<icon>.png——**跨文件数据流**，门禁 P 类的同文件判据
 * 看不见，删 static/menu/*.png 时按文件名 grep 也会漏。孤产清理险情（误删 20 个
 * 被引用图标）的教训：这条数据链必须有用例钉住。
 */

const ALL_NAVS: [string, MerchantNavItem[]][] = [
  ['FIELD', MERCHANT_FIELD_NAV],
  ['BIZ', MERCHANT_BIZ_NAV],
  ['DOCS', MERCHANT_DOCS_NAV],
  ['MORE', MERCHANT_MORE_NAV],
  ['TEAM', MERCHANT_TEAM_NAV]
];

const VALID_PACKS: MerchantPack[] = ['field', 'biz', 'team'];

describe('merchant-nav 菜单完整性', () => {
  it('每个菜单项都有非空 perm（字符串或数组）与非空 pack', () => {
    for (const [label, nav] of ALL_NAVS) {
      for (const item of nav) {
        const perms = Array.isArray(item.perm) ? item.perm : [item.perm];
        expect(perms.length, `${label}.${item.key} perm 不能为空`).toBeGreaterThan(0);
        for (const p of perms) expect(p, `${label}.${item.key} perm 码不能为空`).toBeTruthy();
        expect(VALID_PACKS, `${label}.${item.key} pack 必须是 field/biz/team 之一`).toContain(
          item.pack
        );
      }
    }
  });

  it('菜单 URL 是站内路径且不带 query（query 由跳转方拼接）', () => {
    for (const [, nav] of ALL_NAVS) {
      for (const item of nav) {
        expect(item.url, `${item.key} url 必须以 / 开头`).toMatch(/^\//);
        expect(item.url, `${item.key} url 不应带 query`).not.toContain('?');
      }
    }
  });

  it('key 全局唯一（「更多」复用 BIZ 项除外，它按 key 引用同一对象）', () => {
    const seen = new Map<string, MerchantNavItem>();
    for (const [, nav] of ALL_NAVS) {
      for (const item of nav) {
        const prev = seen.get(item.key);
        if (prev) {
          // MERCHANT_MORE_NAV 从 BIZ 复用：同 key 必须同对象引用
          expect(item, `key "${item.key}" 重复但不是同一对象`).toBe(prev);
        } else {
          seen.set(item.key, item);
        }
      }
    }
  });

  it('MERCHANT_MORE_NAV 的 BIZ 复用项与 MERCHANT_BIZ_NAV 同源', () => {
    for (const item of MERCHANT_MORE_NAV) {
      const fromBiz = MERCHANT_BIZ_NAV.find((i) => i.key === item.key);
      if (fromBiz) expect(item).toBe(fromBiz);
    }
  });

  it('每个菜单项的 icon 都有对应静态资产（menuIcon 动态拼接，删静态时 grep 看不见）', () => {
    for (const [label, nav] of ALL_NAVS) {
      for (const item of nav) {
        if (!item.icon) continue;
        const asset = new URL(`../static/menu/${item.icon}.png`, import.meta.url);
        expect(
          existsSync(asset),
          `${label}.${item.key} 的 icon "${item.icon}" 缺少 static/menu/${item.icon}.png`
        ).toBe(true);
      }
    }
  });
});
