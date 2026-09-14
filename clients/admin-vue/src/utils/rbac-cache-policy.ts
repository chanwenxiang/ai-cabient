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

/**
 * 菜单是否在系统中启用（ACTIVE）。
 * ACTIVE 列表未从服务端加载完成前 fail-closed，避免停用菜单在首屏窗口被短暂展示（A-P2-004）。
 */
export function isNavMenuActiveFor(
  perm: string | null | undefined,
  activeNavLoaded: boolean,
  activeNavPerms: readonly string[]
): boolean {
  if (!perm) return true;
  if (!activeNavLoaded) return false;
  return activeNavPerms.includes(perm);
}
