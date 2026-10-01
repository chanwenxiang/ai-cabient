import { ref } from 'vue';
import { formatError } from '@aicabinet/shared-uni/format';
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

  let pollTimer: ReturnType<typeof setInterval> | null = null;
  let pollInFlight = false;
  let pollStartedAt = 0;
  let pollCappedSessionId = '';
  let pollFailStreak = 0;

  function stopPoll() {
    if (pollTimer != null) {
      clearInterval(pollTimer);
      pollTimer = null;
    }
  }

  function startPoll() {
    const sessionId = options.getSessionId();
    if (sessionId && pollCappedSessionId === sessionId) return;
    stopPoll();
    pollError.value = '';
    pollFailStreak = 0;
    pollStartedAt = Date.now();
    // 立即拉一次，避免弱网下再等一个 interval 才知道状态
    void tickPoll();
    pollTimer = setInterval(() => void tickPoll(), options.pollMs);
  }

  /** 单次会话轮询：防并发堆积；连续失败升级弱网文案；C2 总时长到期停表转订单页。 */
  async function tickPoll() {
    const sessionId = options.getSessionId();
    if (!sessionId || pollInFlight) return;
    if (pollStartedAt > 0 && pollDurationExceeded(pollStartedAt)) {
      pollCappedSessionId = sessionId;
      stopPoll();
      options.onCapped();
      return;
    }
    pollInFlight = true;
    try {
      await options.onTick();
      pollFailStreak = 0;
      pollError.value = '';
    } catch (e) {
      pollFailStreak += 1;
      pollError.value = pollErrorMessage(
        pollFailStreak,
        isNetworkishErrorMessage(formatError(e)),
        formatError(e),
        options.failWarnAt
      );
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
