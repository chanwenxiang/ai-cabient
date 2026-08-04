/** 商户/补货端运行时开关：生产构建剥离开发演示入口 */

export const isDevBuild = !!import.meta.env.DEV;

export function showDevTools(): boolean {
  return isDevBuild;
}
