/**
 * 「未登录暂存、登录后补发」的通用发送队列（展示位打点 / 设备推广位打点共用）。
 *
 * <p>为什么需要（2026-11-11 实证）：消费者端首屏渲染**早于** `App.onLaunch` 的
 * `await ensureConsumerAuth()` —— 首页弹窗走 `popups` 公开白名单，游客也拿得到数据，
 * 拿到即打点；此时 `POST /marketing/placement-events` 必然无凭证
 * （网关日志 `401 60B` = 后端「请先登录」，成对出现是因为旧请求层在无会话时也重放一次）。
 * 打点接口要求登录是**有意设计**（主体从登录态取，防匿名刷量），
 * 所以正确解法不是放行游客，而是把这几秒内的事件攒起来、登录成功后补发。</p>
 *
 * <p>边界：纯内存、FIFO、按事件去重、有上限。运行期重载即清空 —— 这批事件只服务
 * 「同一次冷启动的头几秒」，不做跨会话持久化（否则会拿旧曝光污染新会话的 ROI）。</p>
 *
 * <p>⚠️ 只适用于**打点**这类「过去发生过的事实」。拉内容（如 `screen-content`）不能走这里：
 * 内容是投放清单，几秒前的快照没有补发价值，正确做法是未登录不拉、登录后重拉。</p>
 */

export interface SendQueueDeps<V> {
  /** 当前是否已有登录态（决定「直发」还是「暂存」）。 */
  isLoggedIn: () => boolean;
  /** 真正发一次请求；抛出即视为失败。 */
  send: (item: V) => Promise<unknown>;
  /** 去重键：同键事件在补发里只算一次。 */
  keyOf: (item: V) => string;
  /** 发送失败留痕（打点永不打扰用户，但必须能在日志里看到丢了多少）。 */
  onSendError?: (item: V, err: unknown) => void;
  /** 队列上限，超出丢最旧的。 */
  maxSize?: number;
}

export interface SendQueue<V> {
  /** 有登录态直接发；没有就入队等补发。永不抛错、永不阻塞调用方。 */
  report: (item: V) => void;
  /** 登录成功后调用：按入队顺序补发，成功一条出队一条；失败即停并保留剩余。 */
  flush: () => Promise<number>;
  pendingCount: () => number;
  clear: () => void;
}

export const DEFAULT_MAX_PENDING = 20;

export function createSendQueue<V>(deps: SendQueueDeps<V>): SendQueue<V> {
  const pending: V[] = [];
  const max = deps.maxSize && deps.maxSize > 0 ? deps.maxSize : DEFAULT_MAX_PENDING;
  let flushing = false;

  function sendNow(item: V) {
    deps.send(item).catch((err) => deps.onSendError?.(item, err));
  }

  return {
    report(item) {
      if (deps.isLoggedIn()) {
        sendNow(item);
        return;
      }
      const key = deps.keyOf(item);
      // 同一事件重复上报（banner 滑回来、组件重渲染）不必占两个槽位
      if (pending.some((p) => deps.keyOf(p) === key)) return;
      pending.push(item);
      while (pending.length > max) pending.shift();
    },

    async flush() {
      // 未登录 / 已有 flush 在跑：不发。后者防「登录成功后多个入口同时 flush」重复上报。
      if (flushing || !deps.isLoggedIn()) return 0;
      flushing = true;
      let sent = 0;
      try {
        while (pending.length) {
          const item = pending[0];
          try {
            await deps.send(item);
            pending.shift();
            sent += 1;
          } catch (err) {
            // 保留队首：弱网/限流导致的失败不该把事件丢掉，下次登录或下次 report 再试
            deps.onSendError?.(item, err);
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

// ── 展示位打点（首页 banner / 开门前弹窗 / 关门后弹窗） ──────────────────────

export interface PlacementEventBody {
  position: string;
  activityId?: number;
  campaignId?: number;
  assetId?: number;
  eventType: 'IMPRESSION' | 'CLICK';
}

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

export function createPlacementEventQueue(deps: {
  isLoggedIn: () => boolean;
  send: (body: PlacementEventBody) => Promise<unknown>;
  onSendError?: (body: PlacementEventBody, err: unknown) => void;
  maxSize?: number;
}): SendQueue<PlacementEventBody> {
  return createSendQueue<PlacementEventBody>({
    isLoggedIn: deps.isLoggedIn,
    send: deps.send,
    keyOf: placementEventKey,
    onSendError: deps.onSendError,
    maxSize: deps.maxSize
  });
}

// ── 设备推广位打点（开门页 /devices/{id}/ad-play） ──────────────────────────

export interface AdPlayEventBody {
  deviceId: string;
  campaignId: number;
  assetId: number;
  eventType: 'IMPRESSION' | 'COMPLETE' | 'CLICK';
}

export function adPlayEventKey(body: AdPlayEventBody): string {
  return [body.deviceId, body.campaignId, body.assetId, body.eventType].join('|');
}

export function createAdPlayEventQueue(deps: {
  isLoggedIn: () => boolean;
  send: (body: AdPlayEventBody) => Promise<unknown>;
  onSendError?: (body: AdPlayEventBody, err: unknown) => void;
  maxSize?: number;
}): SendQueue<AdPlayEventBody> {
  return createSendQueue<AdPlayEventBody>({
    isLoggedIn: deps.isLoggedIn,
    send: deps.send,
    keyOf: adPlayEventKey,
    onSendError: deps.onSendError,
    maxSize: deps.maxSize
  });
}
