/**
 * 表格截断单元格：用原生 title 展示全文。
 * 禁止依赖 EP show-overflow-tooltip（浮层挂表内会盖邻列 / sticky 操作列）。
 * 见 .cursor/rules/admin-layout-anti-jitter.mdc 问题 H、docs/engineering/lessons-learned.md
 */
const CELL_SEL = '.el-table .el-table__cell > .cell, .el-table .el-table__cell .cell';

function truncated(el: HTMLElement): boolean {
  return el.scrollWidth > el.clientWidth + 1 || el.scrollHeight > el.clientHeight + 1;
}

function plainText(el: HTMLElement): string {
  return (el.innerText || '').replace(/\s+/g, ' ').trim();
}

function onEnter(ev: Event) {
  const t = ev.target;
  if (!(t instanceof Element)) return;
  const cell = t.closest(CELL_SEL);
  if (!(cell instanceof HTMLElement)) return;
  // 操作列 / 已有 title 的子节点：不覆盖
  if (cell.closest('.col-action, .el-table-fixed-column--right')) return;
  if (cell.querySelector('[title]')) return;
  if (!truncated(cell)) {
    cell.removeAttribute('title');
    return;
  }
  const text = plainText(cell);
  if (text) cell.setAttribute('title', text);
}

/** 挂到 document：一次覆盖全部列表页 */
export function installTableCellNativeTitle() {
  document.addEventListener('mouseover', onEnter, true);
}
