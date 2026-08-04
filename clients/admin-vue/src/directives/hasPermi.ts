import { watch, type App, type Directive, type DirectiveBinding } from 'vue';
import { useAuthStore } from '@/stores/auth';

/**
 * RuoYi-style button permission directive.
 * Usage: v-hasPermi="['ops:rbac:role:add']" or v-hasPermi="'ops:rbac:role:add'"
 * Hides the element when the user lacks any listed permission, and re-evaluates
 * when auth.permissions change (no full remount / re-login required).
 */
type ElWithPermi = HTMLElement & {
  __hasPermiStop?: () => void;
  __hasPermiDisplay?: string;
};

function normalizeCodes(value: string | string[] | undefined | null) {
  return Array.isArray(value) ? value : value ? [value] : [];
}

function applyPermi(el: ElWithPermi, binding: DirectiveBinding<string | string[]>) {
  const codes = normalizeCodes(binding.value);
  if (!codes.length) return;

  if (el.__hasPermiDisplay === undefined) {
    el.__hasPermiDisplay = el.style.display;
  }

  const auth = useAuthStore();
  const sync = () => {
    const ok = codes.some((code) => auth.hasPerm(code));
    el.style.display = ok ? el.__hasPermiDisplay || '' : 'none';
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
  }
};

export function setupHasPermi(app: App) {
  app.directive('hasPermi', hasPermi);
}

export default hasPermi;
