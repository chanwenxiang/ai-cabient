/**
 * 「会话已就绪」广播。
 *
 * <p>场景：消费者端首屏渲染早于 `App.onLaunch` 的 `await ensureConsumerAuth()`，
 * 有些事只能在**拿到票据之后**才能做 —— 典型是拉 `screen-content`（投放清单，不在公开白名单，
 * 游客态拉必 401，且内容有时效、不能像打点那样攒着补发）。它们需要一句
 * 「登录落定了，你重做一次」。</p>
 *
 * <p>与打点队列的分工：队列负责「补发过去发生的事实」，本总线负责「重做一次取数」。
 * 两者都在 `applyTokenSession` 末尾触发（所有登录路径的唯一收敛点）。</p>
 *
 * <p>边界：监听器是**一次性**语义由调用方自己控制（返回的取消函数可随时摘掉）；
 * 广播时单个监听器抛错不影响其他监听器 —— 一个组件的重拉失败不该拖垮别的组件。</p>
 */

export type SessionReadyListener = () => void;

export interface SessionReadyBus {
  /** 注册监听器；返回取消函数（幂等，重复调用无副作用）。 */
  on: (listener: SessionReadyListener) => () => void;
  /** 广播「会话已就绪」。 */
  emit: () => void;
  /** 当前监听器数量（测试与泄漏排查用）。 */
  size: () => number;
}

export function createSessionReadyBus(): SessionReadyBus {
  // 用数组而不是 Set：每次 on 都是一个**独立注册**，取消函数只摘掉自己那一个。
  // 用 Set 的话「同一函数注册两次」会被合并，摘一个就把另一个也废了（契约不一致）。
  const listeners: SessionReadyListener[] = [];
  return {
    on(listener) {
      listeners.push(listener);
      let active = true;
      return () => {
        if (!active) return;
        active = false;
        const index = listeners.indexOf(listener);
        if (index >= 0) listeners.splice(index, 1);
      };
    },
    emit() {
      // 复制一份再遍历：监听器里可能再次 on/off，直接遍历原数组会漏掉或重复触发
      for (const listener of [...listeners]) {
        try {
          listener();
        } catch {
          /* 单个监听器失败不影响其他消费者 */
        }
      }
    },
    size: () => listeners.length
  };
}
