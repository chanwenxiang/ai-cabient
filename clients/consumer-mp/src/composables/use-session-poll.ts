import { ref } from 'vue';
import { formatError } from '@aicabinet/shared-uni/format';
import { isMpAuthFailure } from '@aicabinet/shared-uni/request';
import {
  isNetworkishErrorMessage,
  pollDurationExceeded,
  pollErrorMessage
} from '@/utils/landing-session';

/**
 * C13 切四：会话轮询的「调度壳」（从 pages/index/index.vue 搬移；设计契约见下）。
 *
 * ## 设计契约（切四设计先行结论，2026-10-01）
 *
 * **composable 拥有**（与资金无关的调度语义）：
 * - 单会话单轮询窗口：start() 幂等（先 stop 重启）；同一会话到总时长上限后停表并标记
 *   `pollCappedSessionId`，同会话不再重燃（C2）；
 * - 防并发堆积（pollInFlight）；
 * - 失败连击与弱网文案升级（pollFailStreak/pollError，POLL_FAIL_WARN_AT 语义不变）；
 * - 手动刷新（refreshNow：guard + 成功 toast 回调）。
 * - 2026-10-20 接口层审计增补：失败指数退避（2s→4s→…上限 30s，成功复位）；
 *   鉴权失效（401 且刷新失败）直接停表，不再 2s 硬打 401 至时长上限。
 *
 * **页面保留**（onTick 回调，资金/结算语义不搬）：getSession、applySessionView、
 * 购物车联动、finishSession 结算、abort 清理——即 `classifyPollSessionState` 的
 * 分支处理全部留在页面；onTick 抛错 = 本次失败（由本壳记账），成功返回 = 清零。
 *
 * 🔴 行为保持约定：原页内实现 tickPoll/pollSessionOnce 的 try/catch 边界与
 * `立即拉一次` 启动语义逐行等价；stop 名称保持 startPoll/stopPoll/refreshSessionNow，
 * 页面调用点零改动。
 */
export interface SessionPollOptions {
  /** 当前会话 id（响应式取值）；空 = 不轮询。 */
  getSessionId: () => string | null;
  /** 单次轮询：拉会话→应用视图→处理 finish/abort（内部可调用返回的 stopPoll）。抛错=失败。 */
  onTick: () => Promise<void>;
  /** 轮询间隔 ms（原 SESSION_POLL_MS）。 */
  pollMs: number;
  /** 连续失败多少次后文案升级（原 POLL_FAIL_WARN_AT）。 */
  failWarnAt: number;
  /** 轮询总时长到期（C2）：停表后由页面提示并引导去订单页。 */
  onCapped: () => void;
  /** 手动刷新成功且无错误时的提示（原 showSuccess('状态已更新')）。 */
  onRefreshOk?: () => void;
}

export function useSessionPoll(options: SessionPollOptions) {
  const pollError = ref('');
  const pollRefreshing = ref(false);

  let pollTimer: ReturnType<typeof setTimeout> | null = null;
  let wakeSleep: (() => void) | null = null;
  let pollInFlight = false;
  let pollStartedAt = 0;
  let pollCappedSessionId = '';
  let pollFailStreak = 0;
  /** 调度循环存活标记：stopPoll 置 false 后循环自然退出（2026-10-20 接口层审计改为退避调度）。 */
  let pollLoopActive = false;

  function stopPoll() {
    pollLoopActive = false;
    // 若正睡在退避间隔上：唤醒使其立刻退出，避免悬挂协程
    if (wakeSleep) {
      wakeSleep();
      wakeSleep = null;
    }
  }

  function sleepMs(ms: number): Promise<void> {
    return new Promise((resolve) => {
      pollTimer = setTimeout(resolve, ms);
      wakeSleep = () => {
        if (pollTimer != null) {
          clearTimeout(pollTimer);
          pollTimer = null;
        }
        resolve();
      };
    });
  }

  /**
   * 退避调度：连续失败时按 2^n 递增间隔（上限 30s），成功即回原频率。
   * 弱网/鉴权失效场景不再以固定频率硬打（接口层审计 高-4）。
   */
  async function scheduleLoop() {
    while (pollLoopActive) {
      const delay =
        pollFailStreak > 0
          ? Math.min(options.pollMs * 2 ** Math.min(pollFailStreak, 4), 30_000)
          : options.pollMs;
      await sleepMs(delay);
      if (!pollLoopActive) return;
      const ran = await tickPoll();
      if (!ran || !pollLoopActive) return;
    }
  }

  function startPoll() {
    const sessionId = options.getSessionId();
    if (sessionId && pollCappedSessionId === sessionId) return;
    stopPoll();
    pollError.value = '';
    pollFailStreak = 0;
    pollStartedAt = Date.now();
    // 立即拉一次，避免弱网下再等一个 interval 才知道状态；
    // 仅当本次 tick 真正执行（未被 in-flight 去重跳过）才启动退避循环，防双循环叠发
    pollLoopActive = true;
    void tickPoll().then((ran) => {
      if (ran && pollLoopActive) void scheduleLoop();
    });
  }

  /** 单次会话轮询：防并发堆积；连续失败升级弱网文案；C2 总时长到期停表转订单页。
   *  返回是否真正执行（被 in-flight 去重跳过/已停表时返回 false，调用方据此决定是否续循环）。 */
  async function tickPoll(): Promise<boolean> {
    const sessionId = options.getSessionId();
    if (!sessionId || pollInFlight) return false;
    if (pollStartedAt > 0 && pollDurationExceeded(pollStartedAt)) {
      pollCappedSessionId = sessionId;
      stopPoll();
      options.onCapped();
      return false;
    }
    pollInFlight = true;
    try {
      await options.onTick();
      pollFailStreak = 0;
      pollError.value = '';
      return true;
    } catch (e) {
      pollFailStreak += 1;
      // 登录态已失效（401 且刷新失败）⇒ 停表：再打只会持续 401 空转
      // （接口层审计 高-2：原实现最长 10 分钟硬打 401）
      if (isMpAuthFailure(e)) {
        stopPoll();
        pollError.value = '登录已失效，请重新进入开门流程';
        return false;
      }
      pollError.value = pollErrorMessage(
        pollFailStreak,
        isNetworkishErrorMessage(formatError(e)),
        formatError(e),
        options.failWarnAt
      );
      return true;
    } finally {
      pollInFlight = false;
    }
  }

  async function refreshSessionNow() {
    if (!options.getSessionId() || pollRefreshing.value) return;
    pollRefreshing.value = true;
    try {
      await tickPoll();
      if (!pollError.value) {
        options.onRefreshOk?.();
      }
    } finally {
      pollRefreshing.value = false;
    }
  }

  return { pollError, pollRefreshing, startPoll, stopPoll, refreshSessionNow };
}
