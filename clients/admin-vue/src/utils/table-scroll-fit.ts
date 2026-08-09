/**
 * 窄窗口下列宽总和超出容器时，给 .table-scroll 加上 .table-scroll--h，
 * 让表格在自身区域内横向滚动，页面保持窗口宽度、不再整体向右拉伸。
 * 未超宽时保持页面级滚动，列头继续吸顶。
 */
let rafId = 0;
let observer: MutationObserver | null = null;

export function syncTableScrollFit(): void {
  rafId = 0;
  document.querySelectorAll<HTMLElement>('.table-scroll').forEach((el) => {
    const overflows = el.scrollWidth > el.clientWidth + 1;
    el.classList.toggle('table-scroll--h', overflows);
  });
}

function scheduleSync(): void {
  if (rafId) return;
  rafId = requestAnimationFrame(syncTableScrollFit);
}

export function observeTableScrollFit(root: HTMLElement): void {
  syncTableScrollFit();
  observer?.disconnect();
  observer = new MutationObserver(scheduleSync);
  observer.observe(root, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ['style', 'class']
  });
  window.addEventListener('resize', scheduleSync);
}

export function stopTableScrollFit(): void {
  observer?.disconnect();
  observer = null;
  window.removeEventListener('resize', scheduleSync);
  if (rafId) cancelAnimationFrame(rafId);
  rafId = 0;
}
