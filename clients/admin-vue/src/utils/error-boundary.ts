/**
 * R4-A19：页面错误边界的「致命错误」判据（独立成纯函数以便钉测）。
 *
 * Vue 3 的 onErrorCaptured 第三参 info 在**开发构建**传可读文案（'setup function' /
 * 'render function' / 'native event handler'…），在**生产构建**传
 * `https://vuejs.org/error-reference/#runtime-${code}`（ErrorCodes：setup=0、render function=1）。
 * 只判 dev 文案的话，渲染兜底在生产永不命中（本仓部署产物已实证）。
 *
 * 致命 = 子树已不可用（setup/渲染期抛错）⇒ 整页替换兜底 UI；
 * 其余（事件处理/watcher/生命周期）⇒ 提示后保留页面。
 */
export function isFatalRenderInfo(info: string): boolean {
  if (/^(setup function|render function)$/.test(info)) return true;
  // 生产形态：#runtime-0 = setup function、#runtime-1 = render function
  if (/#runtime-[01]$/.test(info)) return true;
  return false;
}
