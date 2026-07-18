import type { App, Directive, DirectiveBinding } from 'vue';
import { useAuthStore } from '@/stores/auth';

/**
 * RuoYi-style button permission directive.
 * Usage: v-hasPermi="['ops:rbac:role:add']" or v-hasPermi="'ops:rbac:role:add'"
 * Removes the element when the user lacks any listed permission.
 */
function checkPermi(el: HTMLElement, binding: DirectiveBinding<string | string[]>) {
  const value = binding.value;
  const codes = Array.isArray(value) ? value : value ? [value] : [];
  if (!codes.length) return;
  const auth = useAuthStore();
  const ok = codes.some((code) => auth.hasPerm(code));
  if (!ok) {
    el.parentNode?.removeChild(el);
  }
}

const hasPermi: Directive<HTMLElement, string | string[]> = {
  mounted: checkPermi
};

export function setupHasPermi(app: App) {
  app.directive('hasPermi', hasPermi);
}

export default hasPermi;
