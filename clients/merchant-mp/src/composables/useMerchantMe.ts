import { ref } from 'vue';
import { onShow } from '@dcloudio/uni-app';
import { getToken, merchantApi, hasPerm } from '@/utils/merchant-api';
import type { MerchantMe } from '@aicabinet/shared-types';
import type { MerchantNavItem, MerchantPack } from '@/config/merchant-nav';

const meRef = ref<MerchantMe | null>(null);
const loadingRef = ref(false);

let meSeq = 0;
let inflight: Promise<MerchantMe> | null = null;

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
      if (inflight === p) inflight = null;
    }
  })();
  inflight = p;
  return p;
}

export function useMerchantMe() {
  onShow(() => {
    if (getToken()) {
      refreshMerchantMe().catch(() => {
        if (!getToken()) return;
        meRef.value = (uni.getStorageSync('merchant_me') as MerchantMe) || null;
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

export function canEditPlanogramForMerchant(me: MerchantMe | null, merchantId?: string | null): boolean {
  if (!me || !merchantId) return false;
  if (!hasPack(me, 'field')) return false;
  const m = (me.merchants || []).find((x) => x.merchantId === merchantId);
  return !!m?.allowMerchantPlanogramEdit && hasPerm(me, 'merchant:slots:edit');
}

export function canEditPricingWithPerm(me: MerchantMe | null): boolean {
  return canEditPricingForMe(me) && hasPerm(me, 'merchant:pricing:edit');
}
