/**
 * 窄窗口下列宽总和超出容器时，给 .table-scroll 加上 .table-scroll--h，
 * 让表格在自身区域内横向滚动，页面保持窗口宽度、不再整体向右拉伸。
 * 未超宽时保持页面级滚动，列头继续吸顶。
 *
 * 另：表底滚出主区时，在可视区底部贴浮动横滑条（与当前活跃表同步），
 * 滚到表底原生横条可见时自动隐藏。
 *
 * 注意：
 * - 必须用内层列宽 / table 宽度判断。若容器曾被设成 overflow:visible，
 *   用容器 scrollWidth/clientWidth 会误判，并与 .table-scroll--h 的
 *   width:max-content 形成正反馈，把表格越撑越宽。
 * - 只观察 childList，且忽略 tbody 行增删：EP hover-row 是 class；行数据轮询
 *   也不该反复测宽。测宽会强制 reflow，鼠标移动时主区会上下抖。
 * - 同步期间断开 MutationObserver，且不监听 style（EP 写列宽会连环触发）。
 * - 浏览器缩放会改 clientWidth/列宽亚像素；用回滞避免 --h 反复开关导致白框右边「断掉」。
 */
let rafId = 0;
let dockRafId = 0;
let debounceTimer: ReturnType<typeof globalThis.setTimeout> | 0 = 0;
let observer: MutationObserver | null = null;
let observedRoot: HTMLElement | null = null;
let syncing = false;

let dockEl: HTMLElement | null = null;
let dockScroller: HTMLElement | null = null;
let dockSpacer: HTMLElement | null = null;
let activeTable: HTMLElement | null = null;
let scrollSyncing = false;
const tableScrollBound = new WeakSet<HTMLElement>();

const OBSERVE_OPTIONS: MutationObserverInit = {
  childList: true,
  subtree: true
};

/** 表体行增删不影响列宽，跳过以免轮询/虚拟滚动连环 reflow */
function isTableBodyChurn(target: Node): boolean {
  if (!(target instanceof Element)) return false;
  return !!target.closest(
    '.el-table__body, .el-table__body-wrapper, .el-table__fixed-body-wrapper, tbody'
  );
}

function mutationsNeedSync(mutations: MutationRecord[]): boolean {
  for (const mutation of mutations) {
    if (mutation.type !== 'childList') continue;
    if (isTableBodyChurn(mutation.target)) continue;
    return true;
  }
  return false;
}

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

function forceReflow(el: HTMLElement): number {
  return el.offsetWidth;
}

function measureOverflow(el: HTMLElement): boolean {
  const table = el.querySelector<HTMLElement>('.el-table');
  const hadH = el.classList.contains('table-scroll--h');
  const clientW = el.clientWidth;
  const enterPx = 2;
  const leavePx = 6;

  const colsW = columnSumWidth(table);
  if (colsW > 0 && clientW > 0) {
    if (hadH) return colsW > clientW - leavePx;
    return colsW > clientW + enterPx;
  }

  if (hadH) el.classList.remove('table-scroll--h');
  forceReflow(el);

  const headerW = table?.querySelector('.el-table__header table')?.scrollWidth ?? 0;
  const bodyW = table?.querySelector('.el-table__body table')?.scrollWidth ?? 0;
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

function ensureDock(): { dock: HTMLElement; scroller: HTMLElement; spacer: HTMLElement } {
  if (dockEl && dockScroller && dockSpacer) {
    return { dock: dockEl, scroller: dockScroller, spacer: dockSpacer };
  }
  const dock = document.createElement('div');
  dock.className = 'table-hscroll-dock';
  dock.setAttribute('data-testid', 'table-hscroll-dock');
  dock.setAttribute('aria-hidden', 'true');
  dock.hidden = true;
  const scroller = document.createElement('div');
  scroller.className = 'table-hscroll-dock__scroller';
  const spacer = document.createElement('div');
  spacer.className = 'table-hscroll-dock__spacer';
  scroller.appendChild(spacer);
  dock.appendChild(scroller);
  document.body.appendChild(dock);

  scroller.addEventListener(
    'scroll',
    () => {
      if (scrollSyncing || !activeTable) return;
      scrollSyncing = true;
      activeTable.scrollLeft = scroller.scrollLeft;
      scrollSyncing = false;
    },
    { passive: true }
  );

  dockEl = dock;
  dockScroller = scroller;
  dockSpacer = spacer;
  return { dock, scroller, spacer };
}

function onTableScroll(event: Event): void {
  const table = event.currentTarget as HTMLElement;
  if (scrollSyncing || table !== activeTable || !dockScroller) return;
  if (dockEl?.hidden) return;
  scrollSyncing = true;
  dockScroller.scrollLeft = table.scrollLeft;
  scrollSyncing = false;
}

function bindTableScroll(el: HTMLElement): void {
  if (tableScrollBound.has(el)) return;
  el.addEventListener('scroll', onTableScroll, { passive: true });
  tableScrollBound.add(el);
}

function hideDock(): void {
  if (!dockEl) return;
  dockEl.hidden = true;
  activeTable = null;
}

function updateFloatingHScrollDock(): void {
  const main = observedRoot || document.getElementById('main-content');
  if (!main) {
    hideDock();
    return;
  }
  // 抽屉/模态打开时不贴浮动条，避免盖住抽屉内容并减少滚动期布局抖动
  if (document.querySelector('.el-overlay.is-drawer:not([style*="display: none"])')) {
    hideDock();
    return;
  }
  const mainRect = main.getBoundingClientRect();
  let best: HTMLElement | null = null;
  let bestScore = -1;

  main.querySelectorAll<HTMLElement>('.table-scroll').forEach((el) => {
    if (el.closest('.footfall-page')) return;
    bindTableScroll(el);
    if (el.scrollWidth <= el.clientWidth + 2) return;
    const r = el.getBoundingClientRect();
    const intersects = r.bottom > mainRect.top + 8 && r.top < mainRect.bottom - 8;
    if (!intersects) return;
    if (r.bottom <= mainRect.bottom - 8) return;
    const visibleH = Math.min(r.bottom, mainRect.bottom) - Math.max(r.top, mainRect.top);
    if (visibleH > bestScore) {
      bestScore = visibleH;
      best = el;
    }
  });

  if (!best) {
    hideDock();
    return;
  }

  const target: HTMLElement = best;
  const { dock, scroller, spacer } = ensureDock();
  const tableRect = target.getBoundingClientRect();
  const left = Math.max(tableRect.left, mainRect.left);
  const right = Math.min(tableRect.right, mainRect.right);
  activeTable = target;
  spacer.style.width = `${target.scrollWidth}px`;
  dock.style.left = `${left}px`;
  dock.style.width = `${Math.max(0, right - left)}px`;
  dock.style.bottom = `${Math.max(0, window.innerHeight - mainRect.bottom)}px`;
  dock.hidden = false;
  if (!scrollSyncing && Math.abs(scroller.scrollLeft - target.scrollLeft) > 1) {
    scrollSyncing = true;
    scroller.scrollLeft = target.scrollLeft;
    scrollSyncing = false;
  }
}

function scheduleDockUpdate(): void {
  if (dockRafId) return;
  dockRafId = requestAnimationFrame(() => {
    dockRafId = 0;
    updateFloatingHScrollDock();
  });
}

export function syncTableScrollFit(): void {
  rafId = 0;
  if (syncing) return;
  syncing = true;
  observer?.disconnect();
  try {
    document.querySelectorAll<HTMLElement>('.table-scroll').forEach((el) => {
      if (el.closest('.footfall-page')) {
        el.classList.remove('table-scroll--h');
        return;
      }
      const next = measureOverflow(el);
      if (el.classList.contains('table-scroll--h') === next) return;
      el.classList.toggle('table-scroll--h', next);
    });
  } finally {
    syncing = false;
    attachObserver();
    scheduleDockUpdate();
  }
}

function scheduleSync(): void {
  if (syncing) return;
  if (rafId) return;
  if (debounceTimer) globalThis.clearTimeout(debounceTimer);
  debounceTimer = globalThis.setTimeout(() => {
    debounceTimer = 0;
    if (rafId || syncing) return;
    rafId = requestAnimationFrame(syncTableScrollFit);
  }, 48);
}

function onVisualViewportChange(): void {
  scheduleSync();
  scheduleDockUpdate();
}

export function observeTableScrollFit(root: HTMLElement): void {
  // 单页仅一个主内容根；换根时先完整拆除，避免双实例共享 dock / listener 串状态
  if (observedRoot && observedRoot !== root) {
    stopTableScrollFit();
  }
  observedRoot = root;
  observer?.disconnect();
  observer = new MutationObserver((mutations) => {
    if (!mutationsNeedSync(mutations)) return;
    scheduleSync();
  });
  syncTableScrollFit();
  window.addEventListener('resize', scheduleSync);
  // 浏览器缩放 / 触控缩放常改 visualViewport 而不触发 window.resize
  window.visualViewport?.addEventListener('resize', onVisualViewportChange);
  window.visualViewport?.addEventListener('scroll', onVisualViewportChange);
  root.addEventListener('scroll', scheduleDockUpdate, { passive: true });
}

export function stopTableScrollFit(): void {
  observer?.disconnect();
  observer = null;
  if (observedRoot) {
    observedRoot.removeEventListener('scroll', scheduleDockUpdate);
  }
  observedRoot = null;
  window.removeEventListener('resize', scheduleSync);
  window.visualViewport?.removeEventListener('resize', onVisualViewportChange);
  window.visualViewport?.removeEventListener('scroll', onVisualViewportChange);
  if (debounceTimer) globalThis.clearTimeout(debounceTimer);
  debounceTimer = 0;
  if (rafId) cancelAnimationFrame(rafId);
  rafId = 0;
  if (dockRafId) cancelAnimationFrame(dockRafId);
  dockRafId = 0;
  syncing = false;
  hideDock();
  dockEl?.remove();
  dockEl = null;
  dockScroller = null;
  dockSpacer = null;
  activeTable = null;
}
