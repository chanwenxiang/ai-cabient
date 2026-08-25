/**
 * 窄窗口下列宽总和超出容器时，给 .table-scroll 加上 .table-scroll--h，
 * 让表格在自身区域内横向滚动，页面保持窗口宽度、不再整体向右拉伸。
 * 未超宽时保持页面级滚动，列头继续吸顶。
 *
 * 注意：
 * - 必须用内层列宽 / table 宽度判断。若容器曾被设成 overflow:visible，
 *   用容器 scrollWidth/clientWidth 会误判，并与 .table-scroll--h 的
 *   width:max-content 形成正反馈，把表格越撑越宽。
 * - 同步期间断开 MutationObserver，且不监听 style（EP 写列宽会连环触发）。
 * - 浏览器缩放会改 clientWidth/列宽亚像素；用回滞避免 --h 反复开关导致白框右边「断掉」。
 */
let rafId = 0;
let observer: MutationObserver | null = null;
let observedRoot: HTMLElement | null = null;
let syncing = false;

const OBSERVE_OPTIONS: MutationObserverInit = {
  childList: true,
  subtree: true,
  attributes: true,
  // 不监听 style：Element Plus 写列宽/表宽会与 max-content 互相追逐
  attributeFilter: ['class']
};

/** 取表头 colgroup 声明宽度之和（稳定，不受 max-content 膨胀影响） */
function columnSumWidth(table: HTMLElement | null): number {
  if (!table) return 0;
  const group =
    table.querySelector('.el-table__header-wrapper colgroup') ||
    table.querySelector('.el-table__header colgroup') ||
    table.querySelector('colgroup');
  if (!group) return 0;
  let sum = 0;
  group.querySelectorAll('col').forEach((col) => {
    // gutter 列不计入：本布局纵滚在 .table-scroll，gutter 宽应为 0
    if (col.getAttribute('name') === 'gutter' || col.classList.contains('gutter')) return;
    const el = col as HTMLElement;
    const w = parseFloat(el.style.width || '') || parseFloat(col.getAttribute('width') || '') || 0;
    sum += w;
  });
  return sum;
}

/**
 * 在非 --h 布局下测量：临时去掉 max-content，避免把已膨胀的宽度当成「需要横滚」。
 * 带回滞：已是 --h 时略放宽「仍超宽」判定，减轻缩放时边框/滚动条闪断。
 */
function measureOverflow(el: HTMLElement): boolean {
  const table = el.querySelector<HTMLElement>('.el-table');
  const hadH = el.classList.contains('table-scroll--h');
  if (hadH) el.classList.remove('table-scroll--h');
  // 强制回到 width:100% 布局再读
  void el.offsetWidth;

  const colsW = columnSumWidth(table);
  const headerW = table?.querySelector('.el-table__header table')?.scrollWidth ?? 0;
  const bodyW = table?.querySelector('.el-table__body table')?.scrollWidth ?? 0;
  // 不用 el.scrollWidth：overflow:visible 时会跟着子项一起涨，形成反馈环
  const contentW = Math.max(colsW, headerW, bodyW, table?.scrollWidth ?? 0);
  const clientW = el.clientWidth;
  // 缩放亚像素约 1～3px；回滞避免 100% / 90% / 110% 来回切换布局
  const enterPx = 2;
  const leavePx = 6;

  if (hadH) {
    return contentW > clientW - leavePx;
  }
  return contentW > clientW + enterPx;
}

function attachObserver(): void {
  if (!observer || !observedRoot) return;
  observer.observe(observedRoot, OBSERVE_OPTIONS);
}

export function syncTableScrollFit(): void {
  rafId = 0;
  if (syncing) return;
  syncing = true;
  observer?.disconnect();
  try {
    document.querySelectorAll<HTMLElement>('.table-scroll').forEach((el) => {
      // 客流坪效等「卡片内嵌多表」页：禁止 --h（fit-content 会把整页撑出横向滚动，白框跟着滑）
      if (el.closest('.footfall-page')) {
        el.classList.remove('table-scroll--h');
        return;
      }
      el.classList.toggle('table-scroll--h', measureOverflow(el));
    });
  } finally {
    syncing = false;
    attachObserver();
  }
}

function scheduleSync(): void {
  if (rafId || syncing) return;
  rafId = requestAnimationFrame(syncTableScrollFit);
}

export function observeTableScrollFit(root: HTMLElement): void {
  observedRoot = root;
  observer?.disconnect();
  observer = new MutationObserver(scheduleSync);
  syncTableScrollFit();
  window.addEventListener('resize', scheduleSync);
  window.visualViewport?.addEventListener('resize', scheduleSync);
  window.visualViewport?.addEventListener('scroll', scheduleSync);
}

export function stopTableScrollFit(): void {
  observer?.disconnect();
  observer = null;
  observedRoot = null;
  window.removeEventListener('resize', scheduleSync);
  window.visualViewport?.removeEventListener('resize', scheduleSync);
  window.visualViewport?.removeEventListener('scroll', scheduleSync);
  if (rafId) cancelAnimationFrame(rafId);
  rafId = 0;
  syncing = false;
}
