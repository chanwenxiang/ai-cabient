import { onBeforeUnmount, onMounted, ref, watch } from 'vue';

/**
 * 运营后台列表页的「自动刷新」开关（默认关闭，按页面持久化）。
 *
 * 为什么是「开关」而不是全局默认开：
 * - 后台列表页是运营**主动查**的场景，多数页面数据不会自己变；无条件轮询只是白烧接口。
 * - 运营往往正在翻页 / 勾选 / 编辑行，自动刷新若不静默（闪 loading、清选择）就是干扰。
 * - 真正需要「自己跟上来」的是**待办型**页面（争议 / 异常 / 上传队列 / OTA 下发…），
 *   由各页显式开启，且 tick 走 `table.silentRefresh()`（不闪 loading、不清选择）。
 *
 * 行为约定：
 * - 标签页切到后台（`document.hidden`）时**暂停倒计时**，不消耗轮询配额；
 * - 上一次刷新未返回时跳过本轮，慢接口不会滚雪球；
 * - 自动刷新失败**静默**（不弹 ElMessage），避免打断运营操作；
 * - 用户切换开关 / 间隔时立刻写回 localStorage，下次进页面沿用。
 */
export interface UseAutoRefreshOptions {
  /** 持久化键；传入才记住用户选择（建议按路由区分） */
  storageKey?: string;
  /** 到点执行的动作，建议传静默刷新（不闪 loading、不清选择） */
  refresh: () => unknown | Promise<unknown>;
  /** 可选间隔档位（秒） */
  intervals?: number[];
  /** 默认间隔（秒） */
  defaultIntervalSec?: number;
  /** 默认是否开启（默认关） */
  defaultEnabled?: boolean;
}

const DEFAULT_INTERVALS = [15, 30, 60, 300];
const DEFAULT_INTERVAL_SEC = 30;

interface PersistedState {
  enabled?: boolean;
  intervalSec?: number;
}

export function useAutoRefresh(options: UseAutoRefreshOptions) {
  const intervals = options.intervals?.length ? options.intervals : DEFAULT_INTERVALS;
  const storageKey = options.storageKey ?? '';

  const enabled = ref(options.defaultEnabled ?? false);
  const intervalSec = ref(options.defaultIntervalSec ?? DEFAULT_INTERVAL_SEC);
  const countdown = ref(intervalSec.value);
  const refreshing = ref(false);
  const lastRefreshedAt = ref<number | null>(null);

  if (storageKey && typeof localStorage !== 'undefined') {
    try {
      const raw = localStorage.getItem(storageKey);
      if (raw) {
        const saved = JSON.parse(raw) as PersistedState;
        if (typeof saved.enabled === 'boolean') enabled.value = saved.enabled;
        if (Number(saved.intervalSec) > 0) intervalSec.value = Number(saved.intervalSec);
        countdown.value = intervalSec.value;
      }
    } catch {
      /* 存的值坏了就退回默认，不阻塞页面 */
    }
  }

  function persist() {
    if (!storageKey || typeof localStorage === 'undefined') return;
    try {
      localStorage.setItem(
        storageKey,
        JSON.stringify({ enabled: enabled.value, intervalSec: intervalSec.value })
      );
    } catch {
      /* 隐私模式下 setItem 会抛，忽略即可 */
    }
  }

  let timer: ReturnType<typeof setInterval> | undefined;
  let disposed = false;
  let inFlight = false;

  function isHidden() {
    return typeof document !== 'undefined' && document.hidden;
  }

  function stop() {
    if (timer !== undefined) {
      clearInterval(timer);
      timer = undefined;
    }
  }

  async function runRefresh() {
    if (disposed || inFlight) return;
    inFlight = true;
    refreshing.value = true;
    try {
      await options.refresh();
      lastRefreshedAt.value = Date.now();
    } catch {
      /* 自动刷新失败静默：运营没主动点，不该弹提示打断 */
    } finally {
      inFlight = false;
      refreshing.value = false;
    }
  }

  function tick() {
    if (disposed || !enabled.value || isHidden()) return;
    countdown.value -= 1;
    if (countdown.value > 0) return;
    countdown.value = intervalSec.value;
    void runRefresh();
  }

  function start() {
    stop();
    if (disposed) return;
    countdown.value = intervalSec.value;
    timer = setInterval(tick, 1000);
  }

  /** 手动立刻刷一次（并重置倒计时） */
  function refreshNow() {
    countdown.value = intervalSec.value;
    void runRefresh();
  }

  function setEnabled(next: boolean) {
    enabled.value = next;
  }

  watch(enabled, (next) => {
    if (next) start();
    else stop();
    persist();
  });

  watch(intervalSec, (next) => {
    if (!intervals.includes(next)) intervalSec.value = DEFAULT_INTERVAL_SEC;
    countdown.value = intervalSec.value;
    if (enabled.value) start();
    persist();
  });

  onMounted(() => {
    if (enabled.value) start();
  });

  onBeforeUnmount(() => {
    disposed = true;
    stop();
  });

  return {
    enabled,
    intervalSec,
    intervals,
    countdown,
    refreshing,
    lastRefreshedAt,
    setEnabled,
    refreshNow
  };
}
