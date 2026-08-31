/**
 * 窄窗口下列宽总和超出容器时，给 .table-scroll 加上 .table-scroll--h，
 * 让表格在自身区域内横向滚动，页面保持窗口宽度、不再整体向右拉伸。
 * 未超宽时保持页面级滚动，列头继续吸顶。
 *
 * 注意：
 * - 必须用内层列宽 / table 宽度判断。若容器曾被设成 overflow:visible，
 *   用容器 scrollWidth/clientWidth 会误判，并与 .table-scroll--h 的
 *   width:max-content 形成正反馈，把表格越撑越宽。
 * - 只观察 childList：EP 在行上切换 hover-row / current-row 时会狂写 class，
 *   若监听 attributes 会在鼠标上下移动时反复测宽/强制 reflow，主区上下抖。
 * - 同步期间断开 MutationObserver，且不监听 style（EP 写列宽会连环触发）。
 * - 浏览器缩放会改 clientWidth/列宽亚像素；用回滞避免 --h 反复开关导致白框右边「断掉」。
 */
let rafId = 0;
let debounceTimer = 0;
let observer: MutationObserver | null = null;
let observedRoot: HTMLElement | null = null;
let syncing = false;

const OBSERVE_OPTIONS: MutationObserverInit = {
  childList: true,
  subtree: true
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
    const w =
      Number.parseFloat(el.style.width || '') ||
      Number.parseFloat(col.getAttribute('width') || '') ||
      0;
    sum += w;
  });
  return sum;
}

/**
 * 在非 --h 布局下测量：临时去掉 max-content，避免把已膨胀的宽度当成「需要横滚」。
 * 带回滞：已是 --h 时略放宽「仍超宽」判定，减轻缩放时边框/滚动条闪断。
 */
function forceReflow(el: HTMLElement): number {
  return el.offsetWidth;
}

function measureOverflow(el: HTMLElement): boolean {
  const table = el.querySelector<HTMLElement>('.el-table');
  const hadH = el.classList.contains('table-scroll--h');
  const clientW = el.clientWidth;
  // 缩放亚像素约 1～3px；回滞避免 100% / 90% / 110% 来回切换布局
  const enterPx = 2;
  const leavePx = 6;

  // 优先用 colgroup 声明宽：不依赖 max-content，不必临时拆 --h（避免可见闪跳）
  const colsW = columnSumWidth(table);
  if (colsW > 0 && clientW > 0) {
    if (hadH) return colsW > clientW - leavePx;
    return colsW > clientW + enterPx;
  }

  if (hadH) el.classList.remove('table-scroll--h');
  // 强制回到 width:100% 布局再读
  forceReflow(el);

  const headerW = table?.querySelector('.el-table__header table')?.scrollWidth ?? 0;
  const bodyW = table?.querySelector('.el-table__body table')?.scrollWidth ?? 0;
  // 不用 el.scrollWidth：overflow:visible 时会跟着子项一起涨，形成反馈环
  const contentW = Math.max(colsW, headerW, bodyW, table?.scrollWidth ?? 0);

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
  if (syncing) return;
  // 合并同帧 + 短防抖：表格批量插入行时只测一次，避免连续 reflow
  if (rafId) return;
  if (debounceTimer) globalThis.clearTimeout(debounceTimer);
  debounceTimer = globalThis.setTimeout(() => {
    debounceTimer = 0;
    if (rafId || syncing) return;
    rafId = requestAnimationFrame(syncTableScrollFit);
  }, 48);
}

export function observeTableScrollFit(root: HTMLElement): void {
  observedRoot = root;
  observer?.disconnect();
  observer = new MutationObserver(scheduleSync);
  syncTableScrollFit();
  window.addEventListener('resize', scheduleSync);
  // 只跟窗口/缩放尺寸，不跟 visualViewport scroll：桌面端鼠标移动偶发触发
  // visualViewport scroll，会反复测表宽/强制 reflow，主区看起来上下抖 1～2px。
  window.visualViewport?.addEventListener('resize', scheduleSync);
}

export function stopTableScrollFit(): void {
  observer?.disconnect();
  observer = null;
  observedRoot = null;
  window.removeEventListener('resize', scheduleSync);
  window.visualViewport?.removeEventListener('resize', scheduleSync);
  if (debounceTimer) globalThis.clearTimeout(debounceTimer);
  debounceTimer = 0;
  if (rafId) cancelAnimationFrame(rafId);
  rafId = 0;
  syncing = false;
}
