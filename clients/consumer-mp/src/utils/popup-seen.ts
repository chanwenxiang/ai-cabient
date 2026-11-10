/**
 * 活动弹窗「今天已弹过」标记（CB-082）。
 *
 * <p>R2-C6：原实现是**一个活动一个 storage 键**（`<prefix><activityId>`），只写不删 ——
 * 活动越多键越多，永不过期。开门前弹窗（index.vue）与关门后弹窗（result.vue）各有一套，
 * 统一收在这里：
 * <ul>
 *   <li>全部活动存进**单个有界 map**（保留最近 {@link MAX_ACTIVITIES} 个）；</li>
 *   <li>首次读写时把历史遗留的前缀键迁进 map 再删除；</li>
 *   <li>淘汰按日期排序（`todayKey()` 不补零，故按数值比较而非字符串）。</li>
 * </ul>
 *
 * 被淘汰的活动会再弹一次——相对「storage 无限增长」，这是可接受的代价
 * （同一活动每日一次只是防骚扰，不是业务约束）。
 */
const MAX_ACTIVITIES = 50;

export interface PopupSeenStore {
  /** 该活动「今天已看过」的日期键；没看过返回空串 */
  seenDay(activityId: number): string;
  /** 记下「今天看过」 */
  markSeen(activityId: number): void;
}

type SeenMap = Record<string, string>;

/** `2026-11-10` 这类键按数值排序（字符串排序会把 2026-11-2 排在 2026-11-10 之后） */
function dayOrder(day: string): number {
  const [y, m, d] = String(day || '')
    .split('-')
    .map((n) => Number(n) || 0);
  return y * 10000 + m * 100 + d;
}

export function createPopupSeenStore(legacyPrefix: string): PopupSeenStore {
  // 新键名 = 旧前缀 + "map"（前缀以 ':' 结尾，故与新键不会互相命中）
  const mapKey = `${legacyPrefix}map`;
  /** 迁移每次进程只跑一次（读路径也会触发，别每次弹窗判定都扫一遍全量 key） */
  let migrated = false;

  function read(): SeenMap {
    try {
      const raw = uni.getStorageSync(mapKey);
      return raw && typeof raw === 'object' ? (raw as SeenMap) : {};
    } catch {
      return {};
    }
  }

  function write(map: SeenMap): void {
    try {
      uni.setStorageSync(mapKey, map);
    } catch {
      /* 写入失败不影响主流程（下次再弹一次而已） */
    }
  }

  /** 把旧的 `<prefix><activityId>` 键迁进 map 并删除（每个旧键都是永久泄漏）。@return 是否有迁移 */
  function sweepLegacy(map: SeenMap): boolean {
    let changed = false;
    try {
      const keys = (uni.getStorageInfoSync()?.keys || []) as string[];
      for (const key of keys) {
        if (key === mapKey || !key.startsWith(legacyPrefix)) continue;
        const activityId = key.slice(legacyPrefix.length);
        const day = String(uni.getStorageSync(key) || '');
        if (activityId && day && !map[activityId]) map[activityId] = day;
        uni.removeStorageSync(key);
        changed = true;
      }
    } catch {
      /* 存储信息读不到就跳过迁移：只影响清理，不影响弹窗判定 */
    }
    return changed;
  }

  /**
   * 首次访问即迁移。**读也要迁**——否则升级后老用户当天还会再看到一次已看过的弹窗
   * （旧键在 read 里读不到），且旧键要等到下次 markSeen 才被清。
   * 迁移结果要落回去，否则下次启动又「忘掉」这些活动。
   */
  function ensureMigrated(): SeenMap {
    const map = read();
    if (migrated) return map;
    migrated = true;
    if (sweepLegacy(map)) write(map);
    return map;
  }

  return {
    seenDay(activityId: number): string {
      return ensureMigrated()[String(activityId)] || '';
    },
    markSeen(activityId: number): void {
      const d = new Date();
      const today = `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()}`;
      const map = ensureMigrated();
      map[String(activityId)] = today;
      const kept: SeenMap = {};
      Object.entries(map)
        .sort((a, b) => dayOrder(a[1]) - dayOrder(b[1]))
        .slice(-MAX_ACTIVITIES)
        .forEach(([id, day]) => {
          kept[id] = day;
        });
      write(kept);
    }
  };
}
