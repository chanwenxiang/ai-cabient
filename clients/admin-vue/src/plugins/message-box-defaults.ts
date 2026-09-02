import { ElMessageBox } from 'element-plus';
import type { ElMessageBoxOptions } from 'element-plus';

/** IMP-007：MessageBox 默认挂 body，避免 Popover/Dialog 内残留空 overlay */
function withBodyAppend(options?: ElMessageBoxOptions | null): ElMessageBoxOptions {
  return { appendTo: document.body, ...(options || {}) };
}

type AnyMsg = (...args: any[]) => ReturnType<typeof ElMessageBox.confirm>;

function wrap(method: AnyMsg): AnyMsg {
  return (
    message: string,
    titleOrOptions?: string | ElMessageBoxOptions,
    options?: ElMessageBoxOptions
  ) => {
    if (typeof titleOrOptions === 'object' && titleOrOptions !== null) {
      return method(message, withBodyAppend(titleOrOptions as ElMessageBoxOptions));
    }
    return method(message, titleOrOptions as string | undefined, withBodyAppend(options));
  };
}

export function installMessageBoxDefaults(): void {
  ElMessageBox.confirm = wrap(
    ElMessageBox.confirm.bind(ElMessageBox)
  ) as typeof ElMessageBox.confirm;
  ElMessageBox.alert = wrap(ElMessageBox.alert.bind(ElMessageBox)) as typeof ElMessageBox.alert;
  ElMessageBox.prompt = wrap(ElMessageBox.prompt.bind(ElMessageBox)) as typeof ElMessageBox.prompt;
}
