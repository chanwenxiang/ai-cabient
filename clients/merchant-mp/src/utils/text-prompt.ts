/**
 * Cross-platform text prompt.
 * H5 uses a branded overlay (uni.showModal editable looks crude in browser).
 * Mini Program keeps native editable modal.
 */

export type TextPromptOptions = {
  title: string;
  hint?: string;
  placeholder?: string;
  defaultValue?: string;
  confirmText?: string;
  cancelText?: string;
  /** When true, empty confirm shows toast and keeps dialog open (H5) / rejects empty (native). */
  required?: boolean;
  requiredMessage?: string;
  maxLength?: number;
  /** Prefer single-line input (cabinet id). Default multiline for replies/notes. */
  singleLine?: boolean;
  testId?: string;
};

const HOST_ID = 'merchant-text-prompt';

/** 关闭当前 H5 弹层（用于二次打开时释放上一次 Promise） */
let activeH5Finish: ((value: string | null) => void) | null = null;

function isBrowserH5(): boolean {
  return typeof globalThis !== 'undefined' && typeof document !== 'undefined';
}

function promptNative(opts: TextPromptOptions): Promise<string | null> {
  return new Promise((resolve) => {
    uni.showModal({
      title: opts.title,
      editable: true,
      placeholderText: opts.placeholder || '',
      content: opts.defaultValue || '',
      confirmText: opts.confirmText || '确定',
      cancelText: opts.cancelText || '取消',
      success(res) {
        if (!res.confirm) {
          resolve(null);
          return;
        }
        const value = String(res.content || '').trim();
        if (opts.required && !value) {
          uni.showToast({ title: opts.requiredMessage || '请填写内容', icon: 'none' });
          resolve(null);
          return;
        }
        resolve(value);
      },
      fail() {
        resolve(null);
      }
    });
  });
}

function promptH5(opts: TextPromptOptions): Promise<string | null> {
  return new Promise((resolve) => {
    if (activeH5Finish) {
      const prev = activeH5Finish;
      activeH5Finish = null;
      prev(null);
    }
    const existing = document.getElementById(HOST_ID);
    if (existing) existing.remove();

    const testId = opts.testId || 'text-prompt';
    const singleLine = !!opts.singleLine;
    const host = document.createElement('div');
    host.id = HOST_ID;
    host.dataset.testid = testId;
    host.setAttribute('role', 'dialog');
    host.setAttribute('aria-modal', 'true');
    host.setAttribute('aria-label', opts.title);

    const styleEl = document.createElement('style');
    styleEl.textContent = `
        #${HOST_ID} {
          position: fixed; inset: 0; z-index: 10060; display: flex; align-items: center; justify-content: center;
          padding: max(24px, env(safe-area-inset-top)) 20px max(24px, env(safe-area-inset-bottom));
          box-sizing: border-box; background: rgba(15, 23, 42, 0.55); overscroll-behavior: contain;
          font-family: var(--app-font, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei UI", "Microsoft YaHei", "Noto Sans SC", sans-serif);
          -webkit-font-smoothing: antialiased;
        }
        #${HOST_ID} .mtp-card {
          width: 100%; max-width: 320px; padding: 22px 20px 16px; border-radius: 16px; background: #fff;
          box-shadow: 0 18px 40px rgba(15, 23, 42, 0.2); box-sizing: border-box;
        }
        #${HOST_ID} .mtp-title { display: block; font-size: 17px; font-weight: 650; color: #0f172a; text-align: center; }
        #${HOST_ID} .mtp-hint { display: block; margin-top: 8px; font-size: 12px; line-height: 1.45; color: #64748b; text-align: center; }
        #${HOST_ID} .mtp-field {
          display: block; width: 100%; margin-top: 16px; padding: 12px 14px; border: 1px solid #e2e8f0;
          border-radius: 12px; background: #f8fafc; color: #0f172a; font-size: 16px; font-family: inherit;
          line-height: 1.4; outline: none; box-sizing: border-box; resize: none;
        }
        #${HOST_ID} .mtp-field:focus-visible, #${HOST_ID} .mtp-field:focus {
          border-color: #0f766e; background: #fff; box-shadow: 0 0 0 3px rgba(15, 118, 110, 0.15);
        }
        #${HOST_ID} textarea.mtp-field { min-height: 88px; }
        #${HOST_ID} .mtp-field::placeholder { color: #94a3b8; font-family: inherit; }
        #${HOST_ID} .mtp-actions { display: flex; gap: 10px; margin-top: 18px; }
        #${HOST_ID} .mtp-btn {
          flex: 1; margin: 0; padding: 11px 12px; border: none; border-radius: 12px; font-size: 15px;
          font-weight: 600; font-family: inherit; cursor: pointer; line-height: 1.2;
        }
        #${HOST_ID} .mtp-btn.cancel { color: #334155; background: #f1f5f9; }
        #${HOST_ID} .mtp-btn.ok { color: #fff; background: linear-gradient(135deg, #0f766e, #14b8a6); }
        #${HOST_ID} .mtp-btn:active { opacity: 0.88; }
    `;
    const card = document.createElement('div');
    card.className = 'mtp-card';
    const titleEl = document.createElement('span');
    titleEl.className = 'mtp-title';
    titleEl.id = `${HOST_ID}-title`;
    titleEl.textContent = opts.title;
    card.appendChild(titleEl);
    if (opts.hint) {
      const hintEl = document.createElement('span');
      hintEl.className = 'mtp-hint';
      hintEl.textContent = opts.hint;
      card.appendChild(hintEl);
    }
    const field = singleLine
      ? document.createElement('input')
      : document.createElement('textarea');
    field.className = 'mtp-field';
    field.id = `${HOST_ID}-field`;
    field.setAttribute('aria-labelledby', `${HOST_ID}-title`);
    field.dataset.testid = `${testId}-input`;
    field.maxLength = opts.maxLength || (singleLine ? 64 : 200);
    field.placeholder = opts.placeholder || '';
    field.value = opts.defaultValue || '';
    if (singleLine && field instanceof HTMLInputElement) {
      field.type = 'text';
      field.inputMode = 'text';
      field.autocomplete = 'off';
      field.autocapitalize = 'characters';
      field.spellcheck = false;
    }
    card.appendChild(field);
    const actions = document.createElement('div');
    actions.className = 'mtp-actions';
    const cancelBtn = document.createElement('button');
    cancelBtn.type = 'button';
    cancelBtn.className = 'mtp-btn cancel';
    cancelBtn.dataset.testid = `${testId}-cancel`;
    cancelBtn.textContent = opts.cancelText || '取消';
    const okBtn = document.createElement('button');
    okBtn.type = 'button';
    okBtn.className = 'mtp-btn ok';
    okBtn.dataset.testid = `${testId}-confirm`;
    okBtn.textContent = opts.confirmText || '确定';
    actions.append(cancelBtn, okBtn);
    card.appendChild(actions);
    host.append(styleEl, card);

    const finish = (value: string | null) => {
      if (activeH5Finish !== finish) return;
      activeH5Finish = null;
      document.removeEventListener('keydown', onKeyDown, true);
      host.remove();
      resolve(value);
    };
    activeH5Finish = finish;

    const fieldEl = (): HTMLInputElement | HTMLTextAreaElement | null => {
      const el = host.querySelector('.mtp-field');
      if (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) return el;
      return null;
    };

    const readValue = () => String(fieldEl()?.value || '').trim();

    const onConfirm = () => {
      const value = readValue();
      if (opts.required && !value) {
        uni.showToast({ title: opts.requiredMessage || '请填写内容', icon: 'none' });
        fieldEl()?.focus();
        return;
      }
      finish(value);
    };

    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        finish(null);
      } else if (e.key === 'Enter' && (singleLine || e.metaKey || e.ctrlKey)) {
        e.preventDefault();
        onConfirm();
      }
    };

    host.addEventListener('click', (e) => {
      if (e.target === host) finish(null);
    });
    cancelBtn.addEventListener('click', () => finish(null));
    okBtn.addEventListener('click', onConfirm);
    document.addEventListener('keydown', onKeyDown, true);
    document.body.appendChild(host);

    const input = fieldEl();
    requestAnimationFrame(() => {
      // Avoid autofocus on coarse pointers (mobile) — prevents keyboard/zoom jump.
      const coarse = globalThis.matchMedia('(pointer: coarse)').matches;
      if (coarse) return;
      input?.focus();
      const len = input?.value.length || 0;
      input?.setSelectionRange(len, len);
    });
  });
}

/** Returns trimmed text, or null when cancelled / empty-required. */
export function promptText(opts: TextPromptOptions): Promise<string | null> {
  if (isBrowserH5()) {
    return promptH5(opts);
  }
  return promptNative(opts);
}
