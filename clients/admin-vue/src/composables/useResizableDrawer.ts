import { onBeforeUnmount, ref } from 'vue';

export type ResizableDrawerOptions = {
  /** sessionStorage 键，按页面/抽屉区分记忆宽度 */
  storageKey: string;
  defaultWidth?: number;
  minWidth?: number;
  /** 上限像素；实际还会再压到 viewport 的 92% */
  maxWidth?: number;
};

function clamp(n: number, min: number, max: number) {
  return Math.min(max, Math.max(min, n));
}

function readWidth(key: string, fallback: number, min: number, max: number) {
  try {
    const raw = sessionStorage.getItem(key);
    const n = raw ? Number(raw) : Number.NaN;
    if (Number.isFinite(n)) return clamp(n, min, max);
  } catch {
    /* ignore */
  }
  return clamp(fallback, min, max);
}

/**
 * 右侧抽屉可拖左缘加宽：拖动中只改 DOM，松手再写入 Vue，避免 el-table 每帧重排抖动。
 */
export function useResizableDrawer(options: ResizableDrawerOptions) {
  const minWidth = options.minWidth ?? 420;
  const maxWidthCap = options.maxWidth ?? 1200;
  const defaultWidth = options.defaultWidth ?? 560;

  const maxNow = () => Math.min(Math.round(globalThis.innerWidth * 0.92), maxWidthCap);

  const width = ref(readWidth(options.storageKey, defaultWidth, minWidth, maxNow()));

  let raf = 0;
  let detach: (() => void) | null = null;

  function onResizeStart(e: PointerEvent) {
    if (e.button !== 0) return;
    e.preventDefault();
    const startX = e.clientX;
    const startW = width.value;
    const drawerEl = (e.currentTarget as HTMLElement | null)?.closest(
      '.resizable-drawer-panel.el-drawer'
    ) as HTMLElement | null;
    if (!drawerEl) return;

    drawerEl.classList.add('is-resizing');
    let latest = startW;

    const apply = (w: number) => {
      latest = w;
      drawerEl.style.width = `${w}px`;
    };

    const onMove = (ev: PointerEvent) => {
      const next = clamp(startW + (startX - ev.clientX), minWidth, maxNow());
      if (raf) cancelAnimationFrame(raf);
      raf = requestAnimationFrame(() => apply(next));
    };

    const onUp = () => {
      if (raf) cancelAnimationFrame(raf);
      raf = 0;
      globalThis.removeEventListener('pointermove', onMove);
      globalThis.removeEventListener('pointerup', onUp);
      detach = null;
      drawerEl.classList.remove('is-resizing');
      width.value = latest;
      try {
        sessionStorage.setItem(options.storageKey, String(latest));
      } catch {
        /* ignore */
      }
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
    };

    detach = () => {
      if (raf) cancelAnimationFrame(raf);
      globalThis.removeEventListener('pointermove', onMove);
      globalThis.removeEventListener('pointerup', onUp);
      drawerEl.classList.remove('is-resizing');
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
    };

    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
    globalThis.addEventListener('pointermove', onMove);
    globalThis.addEventListener('pointerup', onUp);
  }

  onBeforeUnmount(() => {
    detach?.();
  });

  return { width, onResizeStart };
}
