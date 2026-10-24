import { ElMessageBox } from 'element-plus';

/**
 * IMP-033/007：切页或关弹层后清理残留 overlay，避免串页/挡按钮。
 *
 * 🔴 成因注释（2026-10-23，全局检索「F5 才能开、切页后点击无反应」）：
 * el-dialog 的 body>.el-overlay 是**持久复用元素**——首次挂载即以 display:none 骨架存在，
 * 开关只是 v-show 切显隐；`destroy-on-close` 仅销毁内容（.el-dialog），overlay 骨架留在 DOM。
 * 本工具原先把「内部无 dialog/drawer/messagebox」的 overlay 一律 remove() ⇒ 空骨架被摘，
 * 而 Vue 不知道：vnode.el 仍指向游离元素，下次 v-model=true 只改游离元素的 style/display，
 * 弹层永远回不到 DOM（body 锁类已加、无任何 JS 报错）。改为**只清可见的**：
 * display:none 的骨架是 Vue 正常复用资产，不可动；「挡按钮」的残留必然可见。
 */
export function dismissPageOverlays(): void {
  const box = ElMessageBox as typeof ElMessageBox & { close?: () => void };
  box.close?.();

  document.querySelectorAll('body > .el-overlay').forEach((overlay) => {
    // 离场动画中的 overlay 内容尚在（after-leave 才销毁），content 守卫已覆盖；
    // 再加 display 守卫兜住「隐藏骨架」——两层任一命中都保留。
    if (getComputedStyle(overlay).display === 'none') return;
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
