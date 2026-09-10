import { ref } from 'vue';
import { onShow } from '@dcloudio/uni-app';
import { getToken, merchantApi, hasPerm } from '@/utils/merchant-api';
import type { MerchantMe } from '@aicabinet/shared-types';
import type { MerchantNavItem, MerchantPack } from '@/config/merchant-nav';

const meRef = ref<MerchantMe | null>(null);
const loadingRef = ref(false);

let meSeq = 0;
let inflight: Promise<MerchantMe> | null = null;

/** 去掉授权字段，仅保留展示信息，避免 storage 篡改抬权 */
export function stripMerchantGrants(me: MerchantMe): MerchantMe {
  return {
    ...me,
    permissions: [],
    enabledPacks: [],
    canEditPricing: false,
    merchants: (me.merchants || []).map((m) => ({
      ...m,
      allowMerchantPricingEdit: false,
      allowMerchantPlanogramEdit: false,
      packFieldEnabled: false,
      packBizEnabled: false,
      packTeamEnabled: false
    }))
  };
}

/** 本地缓存仅作弱网展示；权限/套餐一律清空 */
export function peekMerchantMeCacheForDisplay(): MerchantMe | null {
  const raw = uni.getStorageSync('merchant_me');
  if (!raw || typeof raw !== 'object') return null;
  return stripMerchantGrants(raw as MerchantMe);
}

/** me 为空时用去权缓存占位；已有内存态（多半来自服务端）不覆盖 */
export function seedMerchantMeDisplayCache(target: { value: MerchantMe | null }): void {
  if (target.value) return;
  target.value = peekMerchantMeCacheForDisplay();
}

export async function refreshMerchantMe(): Promise<MerchantMe> {
  if (inflight) return inflight;
  const seq = ++meSeq;
  const p = (async () => {
    loadingRef.value = true;
    try {
      const me = await merchantApi.me();
      // 仅提交最新一次，避免并发响应覆盖较新资料
      if (seq === meSeq) {
        uni.setStorageSync('merchant_me', me);
        meRef.value = me;
      }
      return me;
    } finally {
      if (seq === meSeq) loadingRef.value = false;
      // refreshMerchantMe 入口已保证单飞（inflight 存在时直接复用），
      // 完成后直接清空即可，无需比较 promise 引用。
      inflight = null;
    }
  })();
  inflight = p;
  return p;
}

export function useMerchantMe() {
  onShow(() => {
    if (getToken()) {
      refreshMerchantMe().catch(() => {
        // 软失败不回读 storage 里的 permissions（可被篡改抬权）；仅保留本会话已成功拉取的内存态
        if (!getToken()) {
          meRef.value = null;
        }
      });
    }
  });

  return {
    me: meRef,
    loading: loadingRef,
    refresh: refreshMerchantMe
  };
}

export function hasPack(me: MerchantMe | null | undefined, pack: MerchantPack): boolean {
  if (!me) return false;
  // enabledPacks 有值（含空数组）即以服务端并集为准；仅未下发时回退商户行开关
  if (Array.isArray(me.enabledPacks)) {
    return me.enabledPacks.includes(pack);
  }
  const merchants = me.merchants || [];
  if (!merchants.length) return false;
  return merchants.some((m) => {
    if (pack === 'field') return m.packFieldEnabled !== false;
    if (pack === 'biz') return m.packBizEnabled !== false;
    return m.packTeamEnabled !== false;
  });
}

export function canAccessNav(me: MerchantMe | null | undefined, item: MerchantNavItem): boolean {
  if (!hasPack(me, item.pack)) return false;
  const perms = Array.isArray(item.perm) ? item.perm : [item.perm];
  return perms.some((p) => hasPerm(me, p));
}

export function canEditPricingForMe(me: MerchantMe | null): boolean {
  if (!me) return false;
  if (!hasPack(me, 'biz')) return false;
  if (me.canEditPricing) return true;
  return (me.merchants || []).some((m) => m.allowMerchantPricingEdit);
}

export function canEditPlanogramForMerchant(
  me: MerchantMe | null,
  merchantId?: string | null
): boolean {
  if (!me || !merchantId) return false;
  if (!hasPack(me, 'field')) return false;
  const m = (me.merchants || []).find((x) => x.merchantId === merchantId);
  return !!m?.allowMerchantPlanogramEdit && hasPerm(me, 'merchant:slots:edit');
}

export function canEditPricingWithPerm(me: MerchantMe | null): boolean {
  return canEditPricingForMe(me) && hasPerm(me, 'merchant:pricing:edit');
}
