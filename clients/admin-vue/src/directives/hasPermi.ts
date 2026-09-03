import { watch, type App, type Directive, type DirectiveBinding } from 'vue';
import { useAuthStore } from '@/stores/auth';

/**
 * RuoYi-style button permission directive.
 * Usage: v-hasPermi="['ops:rbac:role:add']" or v-hasPermi="'ops:rbac:role:add'"
 * Shows the element when the user has **any** listed permission (OR);
 * hides when none match. Re-evaluates when auth.permissions change.
 *
 * 无权限时除 display:none 外，禁用交互，避免自动化或脚本对隐藏节点 click 仍弹出业务弹窗。
 * 空码 / 空数组不生效（保持元素原样），避免 `hasPerm(undefined)` 误放行。
 */
type ElWithPermi = HTMLElement & {
  __hasPermiStop?: () => void;
  __hasPermiDisplay?: string;
  __hasPermiDisabled?: boolean;
};

function normalizeCodes(value: string | string[] | undefined | null): string[] {
  const raw = Array.isArray(value) ? value : value ? [value] : [];
  return raw.map((c) => String(c || '').trim()).filter(Boolean);
}

function applyPermi(el: ElWithPermi, binding: DirectiveBinding<string | string[]>) {
  const codes = normalizeCodes(binding.value);
  if (!codes.length) return;

  el.__hasPermiDisplay ??= el.style.display;
  if (el.__hasPermiDisabled === undefined) {
    el.__hasPermiDisabled = 'disabled' in el ? Boolean((el as HTMLButtonElement).disabled) : false;
  }

  const auth = useAuthStore();
  const sync = () => {
    const ok = codes.some((code) => auth.hasPerm(code));
    el.style.display = ok ? el.__hasPermiDisplay || '' : 'none';
    el.style.pointerEvents = ok ? '' : 'none';
    el.setAttribute('aria-hidden', ok ? 'false' : 'true');
    if ('disabled' in el) {
      (el as HTMLButtonElement).disabled = ok ? Boolean(el.__hasPermiDisabled) : true;
    }
    if (!ok) {
      el.setAttribute('tabindex', '-1');
    } else {
      el.removeAttribute('tabindex');
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
    delete el.__hasPermiStop;
    delete el.__hasPermiDisplay;
    delete el.__hasPermiDisabled;
  }
};

export function setupHasPermi(app: App) {
  app.directive('hasPermi', hasPermi);
}

export default hasPermi;
