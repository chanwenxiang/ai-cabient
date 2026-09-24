import { watch, type App, type Directive, type DirectiveBinding } from 'vue';
import { useAuthStore } from '@/stores/auth';

/**
 * RuoYi-style button permission directive.
 * Usage: v-hasPermi="['ops:rbac:role:add']" or v-hasPermi="'ops:rbac:role:add'"
 * Shows the element when the user has **any** listed permission (OR);
 * hides when none match. Re-evaluates when auth.permissions change.
 *
 * 无权限时除 display:none 外，在**捕获阶段拦截 click**，避免自动化或脚本对隐藏节点 click
 * 仍弹出业务弹窗。空码 / 空数组不生效（保持元素原样），避免 `hasPerm(undefined)` 误放行。
 *
 * 🔴 2026-09-24 修复（真机取证）：这里原先还**直接写 `el.disabled`** —— 首次挂载时快照原值
 * （`__hasPermiDisabled`），之后每次 sync 都按快照写回。那套做法会与模板/组件争抢同一个属性：
 * 首次挂载时 `:disabled` 表达式的依赖往往还没就绪（典型是异步加载的设备/资产状态），
 * `!canLifecycle('UNBIND')` 求值为 true ⇒ 快照被记成 true；等依赖到位、组件已把可用态渲染出来，
 * 指令又按快照把 `disabled=true` 写回去 ⇒ 按钮被**永久钉死在禁用**。
 *
 * 实测证据（设备详情页 330449777078 / 777740024057，lifecycleStatus=DEPLOYED）：
 * 「解绑 / 撤回未投放 / 返厂 / 退役」四个按钮 `class` 里**没有** `is-disabled`、
 * `aria-disabled="false"`（组件认为可用），原生 `disabled` 属性却是 `true` —— 状态自相矛盾，点了没反应。
 *
 * 现在本指令只负责「显隐 + 事件守卫」，`disabled` 完全交还模板与组件，从根上消除争抢。
 * 回归保护见 `hasPermi.test.ts`。
 */
type ElWithPermi = HTMLElement & {
  __hasPermiStop?: () => void;
  __hasPermiDisplay?: string;
  __hasPermiGuard?: (e: Event) => void;
};

function normalizeCodes(value: string | string[] | undefined | null): string[] {
  const raw = Array.isArray(value) ? value : value ? [value] : [];
  return raw.map((c) => String(c || '').trim()).filter(Boolean);
}

/**
 * 无权限节点的兜底拦截。
 * `display:none` + `pointer-events:none` 只挡真实鼠标命中，挡不住 `el.click()`，
 * 故在捕获阶段掐掉事件传播与同元素上的其它监听（Vue 的 `@click` 是冒泡监听，不会执行）。
 */
function blockEvent(e: Event) {
  e.stopImmediatePropagation();
  e.preventDefault();
}

function applyPermi(el: ElWithPermi, binding: DirectiveBinding<string | string[]>) {
  const codes = normalizeCodes(binding.value);
  if (!codes.length) return;

  el.__hasPermiDisplay ??= el.style.display;

  const auth = useAuthStore();
  const sync = () => {
    const ok = codes.some((code) => auth.hasPerm(code));
    el.style.display = ok ? el.__hasPermiDisplay || '' : 'none';
    el.style.pointerEvents = ok ? '' : 'none';
    el.setAttribute('aria-hidden', ok ? 'false' : 'true');
    if (ok) {
      el.removeAttribute('tabindex');
      if (el.__hasPermiGuard) {
        el.removeEventListener('click', el.__hasPermiGuard, true);
        el.__hasPermiGuard = undefined;
      }
    } else {
      el.setAttribute('tabindex', '-1');
      if (!el.__hasPermiGuard) {
        el.__hasPermiGuard = blockEvent;
        el.addEventListener('click', el.__hasPermiGuard, true);
      }
    }
  };

  sync();
  el.__hasPermiStop?.();
  // Track permission list changes so role edits take effect after refreshPermissions().
  el.__hasPermiStop = watch(() => auth.permissions.join('\0'), sync);
}

const hasPermi: Directive<ElWithPermi, string | string[]> = {
  mounted: applyPermi,
  updated: applyPermi,
  unmounted(el) {
    el.__hasPermiStop?.();
    if (el.__hasPermiGuard) {
      el.removeEventListener('click', el.__hasPermiGuard, true);
    }
    delete el.__hasPermiStop;
    delete el.__hasPermiDisplay;
    delete el.__hasPermiGuard;
  }
};

export function setupHasPermi(app: App) {
  app.directive('hasPermi', hasPermi);
}

export default hasPermi;
