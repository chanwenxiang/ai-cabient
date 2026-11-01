// module-audit Step 0 页面清单对账：以代码为唯一真值，输出「轮次页面数 + 菜单↔路由差集」。
// 用法：node docs/module-audit/step0-inventory.mjs
// 每轮开轮跑一次，把输出原样贴进 rounds/R{NN}-*.md 的 Step 0 小节（含 git rev-parse --short HEAD）。
import fs from 'node:fs';
import path from 'node:path';

const ROOT = path.resolve(import.meta.dirname, '../..');
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');

const navSrc = read('clients/admin-vue/src/config/menu.ts');
const routerSrc = read('clients/admin-vue/src/router/index.ts');

const navRegion = navSrc.slice(
  navSrc.indexOf('const BASE_NAV'),
  navSrc.indexOf('export const NAV_ITEMS')
);
// 逐条切块解析：path 与 title 之间常有注释块（如 /warehouse/fulfillment 的铁律 80 说明），不能假设相邻。
const blockStarts = [...navRegion.matchAll(/path:\s*'([^']+)'/g)];
const nav = blockStarts
  .map((m, i) => {
    const block = navRegion.slice(m.index, blockStarts[i + 1] ? blockStarts[i + 1].index : navRegion.length);
    const title = /title:\s*'([^']+)'/.exec(block);
    const group = /group:\s*'([^']+)'/.exec(block);
    const perm = /perm:\s*'([^']+)'/.exec(block);
    return { path: m[1], title: title ? title[1] : '?', group: group ? group[1] : '?', perm: perm ? perm[1] : null };
  })
  .filter((n, i, a) => a.findIndex((x) => x.path === n.path) === i);

const routeLiterals = [...routerSrc.matchAll(/path:\s*'([^']+)'/g)].map((m) => m[1]);
const routeSet = new Set(routeLiterals.map((p) => p.replace(/^\/+/, '').replace(/\/+$/, '')));

const navPaths = new Set(nav.map((n) => n.path));
const covered = (p) => {
  const bare = p.replace(/^\//, '');
  if (routeSet.has(bare)) return true;
  const seg = bare.split('/').pop();
  return routeSet.has(seg) || routeSet.has(bare.replace(/\//g, '/'));
};

const deadMenu = nav.filter((n) => !covered(n.path));
const CHROME = new Set(['/login', '/forbidden', '/not-found', '/index.html', '/', '/redirect']);
// 兼容重定向不是页面：`{ path: 'x', redirect: '/y' }` 形式（如 rbac→roles、oper-logs→audit）。
// 🔴 兼容重定向的写法有两种：`{ path: 'x', redirect: ... }` 与 `{ path: 'x', name: 'y', redirect: (to) => ... }`
// （`/warehouse` 就是后者，旧正则漏它 ⇒ 被当成「路由有、菜单未登记」的疑似死页）。
const redirectOnly = new Set(
  [...routerSrc.matchAll(/path:\s*'([^']+)'\s*,(?:[^{}]*?)redirect:/g)].map((m) => '/' + m[1].replace(/^\//, ''))
);
const unregistered = [
  ...new Set(
    routeLiterals
      .map((p) => '/' + p.replace(/^\//, ''))
      .filter(
        (p) =>
          !p.includes(':') &&
          p.length > 4 &&
          !navPaths.has(p) &&
          !CHROME.has(p) &&
          !redirectOnly.has(p) &&
          // 父级容器路由（自身只 redirect，子页已在菜单里）不算候选
          ![...navPaths].some((np) => np !== p && np.startsWith(p + '/'))
      )
  ),
];

const groups = {};
for (const n of nav) (groups[n.group] ||= []).push(n);

const noPerm = nav.filter((n) => !n.perm);
console.log(`HEAD: ${process.env.AUDIT_HEAD ?? '（跑之前执行 git rev-parse --short HEAD 并写进轮报告）'}`);
console.log(`admin 侧栏登记页合计：${nav.length}（去重后）`);
for (const g of Object.keys(groups)) console.log(`  ${g}：${groups[g].length}`);
console.log(`\n无 perm 码（登录即可见，R8 权限矩阵重点）：${noPerm.length ? noPerm.map((n) => n.path).join(' ') : '无'}`);
console.log(`\n菜单有、路由无（死入口）：${deadMenu.length ? deadMenu.map((n) => n.path).join(' ') : '无'}`);
console.log(`兼容重定向（非页面，不计入审计面）：${[...redirectOnly].sort().join(' ')}`);
console.log(
  `\n路由有、菜单未登记（可能被 fail-closed 守卫拦成 forbidden，逐条人工判是否死页）：\n  ${
    unregistered.join('\n  ') || '无'
  }`
);

for (const mp of ['consumer', 'merchant']) {
  const j = JSON.parse(read(`clients/${mp}-mp/src/pages.json`));
  const main = (j.pages || []).length;
  const sub = (j.subPackages || []).reduce((a, b) => a + b.pages.length, 0);
  console.log(`${mp}-mp：main=${main} sub=${sub} total=${main + sub}`);
}
