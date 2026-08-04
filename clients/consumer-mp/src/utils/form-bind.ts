/** uni-app 表单取值 / H5 兜底，避免 v-model 与自动化填表不同步。 */

export function eventInputValue(e: unknown): string {
  const ev = e as { detail?: { value?: unknown }; target?: { value?: unknown } };
  const raw = ev?.detail?.value ?? ev?.target?.value ?? '';
  return String(raw ?? '');
}

/** 仅 H5：从原生 input/textarea 读取当前值（提交前兜底）。 */
export function readDomFieldValue(kind: 'input' | 'textarea' | 'password' = 'input'): string {
  // #ifdef H5
  if (typeof document === 'undefined') return '';
  if (kind === 'textarea') {
    const ta = document.querySelector('textarea') as HTMLTextAreaElement | null;
    return (ta?.value || '').trim();
  }
  if (kind === 'password') {
    const pwd = document.querySelector('input[type="password"]') as HTMLInputElement | null;
    return (pwd?.value || '').trim();
  }
  const el =
    (document.querySelector('.uni-input-input') as HTMLInputElement | null) ||
    (document.querySelector('uni-input input') as HTMLInputElement | null) ||
    (document.querySelector('input') as HTMLInputElement | null);
  return (el?.value || '').trim();
  // #endif
  // #ifndef H5
  return '';
  // #endif
}

export function readDomPassword(): string {
  return readDomFieldValue('password');
}

export function readDomTextarea(): string {
  return readDomFieldValue('textarea');
}
