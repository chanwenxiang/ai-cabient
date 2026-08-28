/** uni-app 表单取值 / H5 兜底，避免 v-model 与自动化填表不同步。 */

export function eventInputValue(e: unknown): string {
  const ev = e as { detail?: { value?: unknown }; target?: { value?: unknown } };
  const raw = ev?.detail?.value ?? ev?.target?.value ?? '';
  if (typeof raw === 'string') return raw;
  if (typeof raw === 'number' || typeof raw === 'boolean' || typeof raw === 'bigint') {
    return String(raw);
  }
  return '';
}

function readInputValue(el: Element | null): string {
  if (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) {
    return el.value.trim();
  }
  return '';
}

/** 仅 H5：从原生 input/textarea 读取当前值（提交前兜底）。 */
export function readDomFieldValue(kind: 'input' | 'textarea' | 'password' = 'input'): string {
  // #ifdef H5
  if (typeof document === 'undefined') return '';
  if (kind === 'textarea') {
    return readInputValue(document.querySelector('textarea'));
  }
  if (kind === 'password') {
    return readInputValue(document.querySelector('input[type="password"]'));
  }
  return readInputValue(
    document.querySelector('.uni-input-input') ||
      document.querySelector('uni-input input') ||
      document.querySelector('input')
  );
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
