import { ElMessageBox } from 'element-plus';

/** IMP-033/007：切页或关弹层后清理残留 overlay，避免串页/挡按钮 */
export function dismissPageOverlays(): void {
  const box = ElMessageBox as typeof ElMessageBox & { close?: () => void };
  box.close?.();

  document.querySelectorAll('body > .el-overlay').forEach((overlay) => {
    const hasMessageBox = overlay.querySelector('.el-message-box');
    const hasDialog = overlay.querySelector('.el-dialog');
    const hasDrawer = overlay.querySelector('.el-drawer');
    if (!hasMessageBox && !hasDialog && !hasDrawer) {
      overlay.remove();
    }
  });

  document.body.classList.remove('el-popup-parent--hidden');
  document.body.style.removeProperty('overflow');
  document.body.style.removeProperty('padding-right');
}
