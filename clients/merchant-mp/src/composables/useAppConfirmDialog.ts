import { ref } from 'vue';

export type AppConfirmDialogState = {
  visible: boolean;
  title: string;
  content: string;
  confirmText: string;
  cancelText: string;
  rememberLabel?: string;
  rememberChecked: boolean;
  resolve: ((ok: boolean) => void) | null;
};

const emptyState = (): AppConfirmDialogState => ({
  visible: false,
  title: '',
  content: '',
  confirmText: '确定',
  cancelText: '取消',
  rememberLabel: undefined,
  rememberChecked: false,
  resolve: null
});

/**
 * H5 可访问确认框状态（替代 uni.showModal）。
 * onRemember 在用户勾选「记住」并点确认时回调（如跳过定位）。
 */
export function useAppConfirmDialog(options?: { onRemember?: () => void }) {
  const confirmDialog = ref<AppConfirmDialogState>(emptyState());

  function askConfirm(opts: {
    title: string;
    content: string;
    confirmText?: string;
    cancelText?: string;
    rememberLabel?: string;
    rememberDefault?: boolean;
  }): Promise<boolean> {
    return new Promise((resolve) => {
      if (confirmDialog.value.visible && confirmDialog.value.resolve) {
        confirmDialog.value.resolve(false);
      }
      confirmDialog.value = {
        visible: true,
        title: opts.title,
        content: opts.content,
        confirmText: opts.confirmText || '确定',
        cancelText: opts.cancelText || '取消',
        rememberLabel: opts.rememberLabel,
        rememberChecked: opts.rememberDefault ?? false,
        resolve
      };
    });
  }

  function resolveConfirm(ok: boolean) {
    const resolver = confirmDialog.value.resolve;
    if (ok && confirmDialog.value.rememberLabel && confirmDialog.value.rememberChecked) {
      options?.onRemember?.();
    }
    confirmDialog.value = emptyState();
    resolver?.(ok);
  }

  return { confirmDialog, askConfirm, resolveConfirm };
}
