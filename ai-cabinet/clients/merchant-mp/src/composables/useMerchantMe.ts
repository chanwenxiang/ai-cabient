import { ref } from 'vue';
import { onShow } from '@dcloudio/uni-app';
import { merchantApi, hasPerm } from '@/utils/merchant-api';
import type { MerchantMe } from '@aicabinet/shared-types';

const meRef = ref<MerchantMe | null>(null);
const loadingRef = ref(false);

export async function refreshMerchantMe(): Promise<MerchantMe> {
  loadingRef.value = true;
  try {
    const me = await merchantApi.me();
    uni.setStorageSync('merchant_me', me);
    meRef.value = me;
    return me;
  } finally {
    loadingRef.value = false;
  }
}

export function useMerchantMe() {
  onShow(() => {
    if (uni.getStorageSync('merchant_token')) {
      refreshMerchantMe().catch(() => {
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

export function canEditPricingForMe(me: MerchantMe | null): boolean {
  if (!me) return false;
  if (me.canEditPricing) return true;
  return (me.merchants || []).some((m) => m.allowMerchantPricingEdit);
}

export function canEditPlanogramForMerchant(me: MerchantMe | null, merchantId?: string | null): boolean {
  if (!me || !merchantId) return false;
  const m = (me.merchants || []).find((x) => x.merchantId === merchantId);
  return !!m?.allowMerchantPlanogramEdit && hasPerm(me, 'merchant:slots:edit');
}

export function canEditPricingWithPerm(me: MerchantMe | null): boolean {
  return canEditPricingForMe(me) && hasPerm(me, 'merchant:pricing:edit');
}
