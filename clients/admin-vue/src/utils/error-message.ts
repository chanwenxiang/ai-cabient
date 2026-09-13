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

/** 会话失效类错误（退出登录在途请求常见）。 */
export function isSessionAuthError(error: unknown): boolean {
  return /请先登录|登录已失效|登录状态已失效|未授权|INVALID_TOKEN|MISSING_TOKEN/i.test(
    errorMessage(error, '')
  );
}

/** MessageBox 取消/关闭不提示。 */
export function isUserDismiss(error: unknown): boolean {
  return error === 'cancel' || error === 'close';
}
