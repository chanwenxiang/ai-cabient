import { describe, expect, it } from 'vitest';
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { TABBAR_ITEM_NAMES } from './tabbar-skin';

/**
 * tabBar 图标资产存在性断言（2026-10-24 孤产清理险情收尾）：
 * tabbar-skin.ts 用 `/static/tab/${name}${suffix}.png` **字符串拼接**引用图标——
 * 门禁 P 类明说不机检拼接路径（见 check-mp-defect-classes.mjs P 类「已知边界」），
 * render-mp-assets.mjs 的 TAB_ICONS_BY_APP 也不再无条件生成全部变体。
 * 删 static/tab/*.png 时按文件名 grep 同样看不见这条链，故用本用例钉住：
 * base + darkbg 两套皮肤 × 3 tab × (常规 + active) 共 12 个文件缺一即红。
 */
const TAB_DIR = join(import.meta.dirname, '..', 'static', 'tab');
const SUFFIXES: Array<'' | '-darkbg'> = ['', '-darkbg'];

describe('tabbar 图标资产存在性', () => {
  it('每个 tab 的 base 与 darkbg 皮肤图标（常规 + active）都必须在盘', () => {
    for (const name of TABBAR_ITEM_NAMES) {
      for (const suffix of SUFFIXES) {
        for (const tail of ['.png', '-active.png']) {
          const rel = `/static/tab/${name}${suffix}${tail}`;
          expect(
            existsSync(join(TAB_DIR, `${name}${suffix}${tail}`)),
            `tabBar 图标缺失: ${rel}（skin=${suffix || 'base'}）`
          ).toBe(true);
        }
      }
    }
  });
});
