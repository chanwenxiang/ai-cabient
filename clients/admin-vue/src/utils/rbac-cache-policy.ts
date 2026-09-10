/**
 * 权限接口软失败时的落点策略：
 * - 未与服务端同步过：清空（防 localStorage 篡改抬权）
 * - 本会话已同步过：保留内存中的上次服务端结果（不回读 localStorage）
 */
export function permissionsAfterSoftFail(
  alreadyHydratedFromServer: boolean,
  currentInMemory: string[]
): string[] {
  if (alreadyHydratedFromServer) return currentInMemory;
  return [];
}
