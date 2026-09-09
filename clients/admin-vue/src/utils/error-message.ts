/** 从 unknown 提取可读错误文案（Element Plus / 业务 Error）。 */
export function errorMessage(error: unknown, fallback = '操作失败'): string {
  if (error instanceof Error && error.message) return error.message;
  if (typeof error === 'string' && error) return error;
  if (error && typeof error === 'object' && 'message' in error) {
    const msg = (error as { message?: unknown }).message;
    if (typeof msg === 'string' && msg) return msg;
  }
  return fallback;
}

/** MessageBox 取消/关闭不提示。 */
export function isUserDismiss(error: unknown): boolean {
  return error === 'cancel' || error === 'close';
}
