import { nextTick, onBeforeUnmount, ref } from 'vue';

export type ResizableDrawerOptions = {
  /** sessionStorage 键，按页面/抽屉区分记忆宽度 */
  storageKey: string;
  defaultWidth?: number;
  minWidth?: number;
  /** 上限像素；实际还会再压到 viewport 的 92% */
  maxWidth?: number;
};

/** 拖宽回滞：亚像素/1px 抖动不写 DOM，避免下半区表格/栅格每帧重排 */
const RESIZE_HYSTERESIS_PX = 2;

function clamp(n: number, min: number, max: number) {
  return Math.min(max, Math.max(min, n));
}

function readWidth(key: string, fallback: number, min: number, max: number) {
  try {
    const raw = sessionStorage.getItem(key);
    const n = raw ? Number(raw) : Number.NaN;
    if (Number.isFinite(n)) return clamp(Math.round(n), min, max);
  } catch {
    /* ignore */
  }
  return clamp(Math.round(fallback), min, max);
}

/**
 * 右侧抽屉可拖左缘加宽：拖动中只改 DOM（取整+回滞），松手再写入 Vue。
 *
 * 松手顺序必须：width.value → nextTick（EP :size 已上）→ 再清 inline。
 * 若先清 inline，会短暂落到旧 :size，表现为「拉窄后自动弹回变宽」。
 */
export function useResizableDrawer(options: ResizableDrawerOptions) {
  const minWidth = options.minWidth ?? 420;
  const maxWidthCap = options.maxWidth ?? 1200;
  const defaultWidth = options.defaultWidth ?? 560;

  const maxNow = () => Math.min(Math.round(globalThis.innerWidth * 0.92), maxWidthCap);

  const width = ref(readWidth(options.storageKey, defaultWidth, minWidth, maxNow()));

  let raf = 0;
  let detach: (() => void) | null = null;

  function reclampedWidth(current: number) {
    return clamp(Math.round(current), minWidth, maxNow());
  }

  function onViewportResize() {
    const next = reclampedWidth(width.value);
    if (next !== width.value) width.value = next;
  }

  globalThis.addEventListener('resize', onViewportResize);

  function onResizeStart(e: PointerEvent) {
    if (e.button !== 0) return;
    e.preventDefault();
    const startX = e.clientX;
    const startW = Math.round(width.value);
    const drawerEl = (e.currentTarget as HTMLElement | null)?.closest(
      '.resizable-drawer-panel.el-drawer'
    ) as HTMLElement | null;
    if (!drawerEl) return;

    drawerEl.classList.add('is-resizing');
    let latest = startW;
    let lastApplied = startW;
    drawerEl.style.width = `${startW}px`;

    const apply = (w: number) => {
      const rounded = Math.round(w);
      if (Math.abs(rounded - lastApplied) < RESIZE_HYSTERESIS_PX) {
        latest = rounded;
        return;
      }
      lastApplied = rounded;
      latest = rounded;
      drawerEl.style.width = `${rounded}px`;
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
      const finalW = Math.round(latest);
      // 先钉住最终宽，再同步 Vue，避免清 inline 时弹回旧 :size
      drawerEl.style.width = `${finalW}px`;
      width.value = finalW;
      try {
        sessionStorage.setItem(options.storageKey, String(finalW));
      } catch {
        /* ignore */
      }
      void nextTick(() => {
        // EP 已按新 size 写宽后再去掉拖动态；保留 inline 与 :size 一致，不清空
        // （清空会在部分 EP 版本上闪回旧宽）
        drawerEl.classList.remove('is-resizing');
        drawerEl.style.width = `${finalW}px`;
      });
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
    globalThis.removeEventListener('resize', onViewportResize);
  });

  return { width, onResizeStart };
}
