/**
 * 「后端异步结果」轮询器的**纯逻辑**部分（不依赖任何小程序/浏览器 API，可直接单测）。
 *
 * 解决的问题：页面停在前台时，后端的处理结果（账单生成 / 识别 / 审核 / 扣款 / 充值到账）
 * 不会自己送到界面上 —— 用户只能手动下拉或退出重进，表现为「后端好了但小程序还是旧的」。
 *
 * 设计约束（每条都对应一种反模式）：
 * 1. **只在「未终态」时轮询**：`shouldContinue()` 返回 false 立即停表；终态页面零轮询开销。
 * 2. **切后台即停表**：宿主在 hide/unload 时调 `stop()`/`dispose()`，不做后台轮询。
 * 3. **有总时长上限**：`maxDurationMs` 到点自动停表，避免用户把页面挂着一整天。
 * 4. **串行不叠请求**：上一次加载未返回时跳过本次 tick，慢接口不会滚雪球。
 * 5. **加载动作由页面提供**：本原语只决定「什么时候再拉一次」，不碰页面的 loading / 错误态
 *    —— 传入的 `load` 必须是可静默重入的（不闪 loading、失败不弹 toast）。
 *
 * 宿主接线（页面 vs 组件的钩子名不同）见各端 `composables/use-auto-refresh.ts`：
 * - 页面实例：`onShow` / `onHide`
 * - 组件实例：小程序端只认 `onPageShow` / `onPageHide`（uni 把组件的 pageLifetimes
 *   映射成这两个钩子，**组件里的 `onShow` 永远不会被调用**）。
 */
export interface AutoRefreshOptions {
  /** 轮询间隔（毫秒），必须 > 0；<= 0 直接不轮询（只保留 onShow 唤醒） */
  intervalMs: number;
  /** 每次 tick 执行的加载动作，需可静默重入（不闪 loading、失败不提示） */
  load: () => unknown | Promise<unknown>;
  /** 是否继续轮询；返回 false 立即停表。缺省视为一直轮询（仍受 maxDurationMs 约束） */
  shouldContinue?: () => boolean;
  /** 单次进入页面的轮询总时长上限（毫秒），默认 180000（3 分钟） */
  maxDurationMs?: number;
  /** 每次 tick 前的准入判断；返回 false 跳过本次（如页面有弹层 / 正在提交，避免打断） */
  canRefresh?: () => boolean;
  /** 时钟注入（单测用），默认 Date.now */
  now?: () => number;
}

export interface AutoRefreshHandle {
  /** 立刻开始轮询（宿主 onShow/onPageShow 时调用；页面也可手动调） */
  start: () => void;
  /** 停止轮询（宿主 onHide/onPageHide 时调用） */
  stop: () => void;
  /** 停止轮询并作废本实例（宿主 unload/unmount 时调用，此后 start 不再生效） */
  dispose: () => void;
  /** 当前是否在轮询 */
  running: () => boolean;
  /** 本轮轮询的计时起点（毫秒时间戳，0 表示从未开始） */
  startedAt: () => number;
  /** 手动走一次 tick 判定（单测用；生产由内部定时器驱动） */
  tick: () => void;
}

const DEFAULT_MAX_DURATION_MS = 180_000;

/** 定时器类型在 node/browser 下不同，这里按 setInterval 的返回类型收口 */
type TimerHandle = ReturnType<typeof setInterval>;

export function createAutoRefresher(options: AutoRefreshOptions): AutoRefreshHandle {
  const {
    intervalMs,
    load,
    shouldContinue,
    maxDurationMs = DEFAULT_MAX_DURATION_MS,
    canRefresh,
    now = Date.now
  } = options;

  let timer: TimerHandle | null = null;
  // ⚠️ 哨兵必须是 null，不能用 0：`now()` 在被注入/被假定时器接管时可能合法地返回 0，
  // 用 0 当「还没开始」会让 maxDurationMs 整条判据静默失效（单测实测：4 拍全打出去了）。
  let startedAtMs: number | null = null;
  let inFlight = false;
  let disposed = false;

  function stop() {
    if (timer !== null) {
      clearInterval(timer);
      timer = null;
    }
  }

  function tick() {
    if (disposed || inFlight) return;
    if (startedAtMs !== null && now() - startedAtMs >= maxDurationMs) {
      stop();
      return;
    }
    if (shouldContinue && !shouldContinue()) {
      stop();
      return;
    }
    if (canRefresh && !canRefresh()) return;
    inFlight = true;
    Promise.resolve()
      .then(load)
      .catch(() => {
        /* 轮询失败静默：下一次 tick 再试，不打断用户 */
      })
      .finally(() => {
        inFlight = false;
      });
  }

  function start() {
    if (disposed || intervalMs <= 0) return;
    stop();
    startedAtMs = now();
    timer = setInterval(tick, intervalMs);
  }

  function dispose() {
    disposed = true;
    stop();
  }

  return {
    start,
    stop,
    dispose,
    running: () => timer !== null,
    startedAt: () => startedAtMs ?? 0,
    tick
  };
}

/** 订单类状态里「已经不会再有后端后续动作」的终态；其余（含 DISPUTED 审核中）都要继续轮询 */
export const ORDER_TERMINAL_STATUSES = [
  'PAID',
  'COMPLETED',
  'REFUNDED',
  'PARTIAL_REFUNDED',
  'CANCELLED',
  'FAILED'
] as const;

/** 订单快照是否已进入终态（无订单 / 缺状态一律视为未终态，继续等后端的处理结果） */
export function isOrderTerminal(status?: string | null): boolean {
  const s = String(status || '').toUpperCase();
  if (!s) return false;
  return (ORDER_TERMINAL_STATUSES as readonly string[]).includes(s);
}
