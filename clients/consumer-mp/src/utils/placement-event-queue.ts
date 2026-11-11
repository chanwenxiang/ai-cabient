/**
 * 展示位打点的「未登录暂存、登录后补发」队列。
 *
 * <p>为什么需要（2026-11-11 实证）：消费者端首页首屏早于微信静默登录 ——
 * `App.onLaunch` 里 `await ensureConsumerAuth()` 是异步的，而 `index.vue` 的弹窗
 * `popups` 走公开白名单、游客也能拿到数据，拿到即打点；此时 `POST /marketing/placement-events`
 * 必然无凭证（网关日志 `401 60B` = 后端「请先登录」，两次间隔同一秒）。
 * 打点接口要求登录是**有意设计**（防匿名刷量，主体从登录态取），
 * 所以正确解法不是放行游客，而是把这几秒内的事件攒起来、登录成功后补发。</p>
 *
 * <p>边界：纯内存、FIFO、按事件去重、有上限。运行期重载即清空 —— 这批事件只服务
 * 「同一次冷启动的头几秒」，不做跨会话持久化（否则会拿旧曝光污染新会话的 ROI）。</p>
 */

export interface PlacementEventBody {
  position: string;
  activityId?: number;
  campaignId?: number;
  assetId?: number;
  eventType: 'IMPRESSION' | 'CLICK';
}

export interface PlacementEventQueueDeps {
  /** 当前是否已有登录态（决定「直发」还是「暂存」）。 */
  isLoggedIn: () => boolean;
  /** 真正发一次打点请求；抛出即视为失败。 */
  send: (body: PlacementEventBody) => Promise<unknown>;
  /** 发送失败留痕（打点永不打扰用户，但必须能在日志里看到丢了多少）。 */
  onSendError?: (body: PlacementEventBody, err: unknown) => void;
  /** 队列上限，超出丢最旧的。默认 20：三个位置 × 少量素材，够用且不会无限涨。 */
  maxSize?: number;
}

export const DEFAULT_MAX_PENDING = 20;

/** 去重键：同一事件在「补发」里只算一次（服务端另有 60s 去重窗口兜底）。 */
export function placementEventKey(body: PlacementEventBody): string {
  return [
    body.position,
    body.activityId ?? '',
    body.campaignId ?? '',
    body.assetId ?? '',
    body.eventType
  ].join('|');
}

export interface PlacementEventQueue {
  /** 有登录态直接发；没有就入队等补发。永不抛错、永不阻塞调用方。 */
  report: (body: PlacementEventBody) => void;
  /** 登录成功后调用：按入队顺序补发，成功一条出队一条；失败即停并保留剩余。 */
  flush: () => Promise<number>;
  pendingCount: () => number;
  clear: () => void;
}

export function createPlacementEventQueue(deps: PlacementEventQueueDeps): PlacementEventQueue {
  const pending: PlacementEventBody[] = [];
  const max = deps.maxSize && deps.maxSize > 0 ? deps.maxSize : DEFAULT_MAX_PENDING;
  let flushing = false;

  function sendNow(body: PlacementEventBody) {
    deps.send(body).catch((err) => deps.onSendError?.(body, err));
  }

  return {
    report(body) {
      if (deps.isLoggedIn()) {
        sendNow(body);
        return;
      }
      const key = placementEventKey(body);
      // 同一事件重复上报（banner 滑回来、弹窗重渲染）不必占两个槽位
      if (pending.some((p) => placementEventKey(p) === key)) return;
      pending.push({ ...body });
      while (pending.length > max) pending.shift();
    },

    async flush() {
      // 未登录 / 已有 flush 在跑：不发。后者防「登录成功后多个入口同时 flush」重复上报。
      if (flushing || !deps.isLoggedIn()) return 0;
      flushing = true;
      let sent = 0;
      try {
        while (pending.length) {
          const body = pending[0];
          try {
            await deps.send(body);
            pending.shift();
            sent += 1;
          } catch (err) {
            // 保留队首：弱网/限流导致的失败不该把事件丢掉，下次登录或下次 report 再试
            deps.onSendError?.(body, err);
            break;
          }
        }
      } finally {
        flushing = false;
      }
      return sent;
    },

    pendingCount: () => pending.length,

    clear: () => {
      pending.length = 0;
    }
  };
}
