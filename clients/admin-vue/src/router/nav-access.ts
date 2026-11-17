/**
 * MA-317（2026-11-17 对抗验证轮）：admin 路由守卫的权限判定——抽成可测纯函数。
 *
 * <p>修前形态：`router/index.ts` 的 beforeEach 只读 `meta.perm || nav?.perm`，
 * 而 `/print`（打印单据，隐藏页）在 `menu.ts` 里声明的是 **`perms` 数组**（OR 语义）
 * ⇒ `requiredPerm` 恒为空、第二条 `nav?.perm && !canAccessNav(nav)` 也恒假 ⇒ 任意登录用户
 * 从地址栏直入 `/admin/print` 就能打开（侧栏走的是 `canAccessPath`，那条路不经过守卫）。
 * 路由注释还写着「权限由 findNavByPath('/print') → ops:warehouse:list 在 beforeEach 校验」——
 * 那是**没实现的说法**，守卫根本没调用过 store 里早已支持 perms 的 `canAccessNav`。</p>
 *
 * <p>本函数把三条判据收在一处（未登记 / 功能码 / 菜单可见性），`nav-access.test.ts` 逐条钉；
 * 把 `nav && !canAccessNav(nav)` 退回 `nav?.perm && ...` ⇒ `/print` 那条用例必红。</p>
 */

/** 守卫只需要这两个字段；NAV_ITEMS 与 findNavByPath 的合成项都结构性满足。 */
export type NavPerm = { perm?: string; perms?: string[] } | null | undefined;

export type NavAccessVerdict = 'ok' | 'unregistered' | 'denied';

export function decideNavAccess(input: {
  /** 路由 `meta.perm`（显式声明的单码，优先级高于菜单项）。 */
  metaPerm: string;
  /** `findNavByPath(to.path)` 的结果；undefined = 该路径没登记菜单项。 */
  nav: NavPerm;
  hasPerm: (perm: string) => boolean;
  canAccessNav: (nav: NavPerm) => boolean;
}): NavAccessVerdict {
  const requiredPerm = input.metaPerm || input.nav?.perm || '';
  // fail-closed：业务路由须能解析到菜单项或有显式 meta.perm
  if (!input.nav && !input.metaPerm) return 'unregistered';
  if (requiredPerm && !input.hasPerm(requiredPerm)) return 'denied';
  // 🔴 MA-317：整条菜单可见性判定必须无条件走 canAccessNav——它才认识 perms（OR 语义）
  // 与「菜单被停用」两件事；旧写法用 `nav?.perm &&` 当前置条件，等于对 perms-only 页面形同不存在。
  if (input.nav && !input.canAccessNav(input.nav)) return 'denied';
  return 'ok';
}
