<template>
  <view>
    <view v-if="!canView" class="card">
      <text class="err">当前账号无定价查看权限</text>
    </view>
    <template v-else>
      <view class="card">
        <picker :range="deviceOptions" range-key="label" @change="onDevicePick">
          <view class="picker">柜机：{{ selectedLabel }}</view>
        </picker>
        <text v-if="!canEdit" class="meta warn">定价只读 — 需平台开启「允许商户改价」且具备 pricing:edit 权限</text>
      </view>

      <view v-if="loading" class="card">加载中…</view>
      <view v-else-if="error" class="card"><text class="err">{{ error }}</text></view>
      <view v-else>
        <view v-for="p in rows" :key="p.skuId + p.deviceId" class="card row">
          <view>
            <text class="name">{{ p.skuName }}</text>
            <text class="meta">基准 ¥{{ (p.basePriceCents / 100).toFixed(2) }}</text>
          </view>
          <view class="price-col">
            <text class="effective">¥{{ (p.effectivePriceCents / 100).toFixed(2) }}</text>
            <input
              v-if="canEdit"
              v-model="draft[p.skuId]"
              class="input"
              type="digit"
              placeholder="覆盖价(元)"
              @blur="savePrice(p)"
            />
          </view>
        </view>
        <view v-if="!rows.length" class="card meta">暂无定价数据</view>
      </view>
    </template>
  </view>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { hasPerm, merchantApi } from '@/utils/merchant-api';
import { useMerchantMe, canEditPricingWithPerm } from '@/composables/useMerchantMe';
import type { MerchantMe, MerchantSkuPricing } from '@aicabinet/shared-types';

const { me, refresh: refreshMe } = useMerchantMe();
const loading = ref(true);
const error = ref('');
const rows = ref<MerchantSkuPricing[]>([]);
const draft = ref<Record<string, string>>({});
const devices = ref<{ deviceId: string; deviceName?: string }[]>([]);
const selectedDeviceId = ref('');
const gated = ref(false);

const canView = computed(() => hasPerm(me.value, 'merchant:pricing:view'));
const canEdit = computed(() => canEditPricingWithPerm(me.value));

const deviceOptions = computed(() =>
  [{ deviceId: '', label: '全部柜机' }, ...devices.value.map((d) => ({ deviceId: d.deviceId, label: d.deviceName || d.deviceId }))]
);

const selectedLabel = computed(() => {
  const hit = deviceOptions.value.find((d) => d.deviceId === selectedDeviceId.value);
  return hit?.label || '全部柜机';
});

watch(me, (m) => {
  if (!m && !uni.getStorageSync('merchant_token')) {
    uni.reLaunch({ url: '/pages/login/login' });
  }
}, { immediate: true });

watch(me, () => {
  if (me.value) load();
}, { immediate: true });

async function load() {
  if (!uni.getStorageSync('merchant_token')) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  try {
    await refreshMe();
  } catch {
    me.value = (uni.getStorageSync('merchant_me') as MerchantMe) || null;
  }
  if (!canView.value) {
    loading.value = false;
    if (!gated.value) {
      gated.value = true;
      uni.showToast({ title: '无定价查看权限', icon: 'none' });
      uni.switchTab({ url: '/pages/home/home' });
    }
    return;
  }
  loading.value = true;
  error.value = '';
  try {
    if (!devices.value.length) {
      const list = await merchantApi.devices();
      devices.value = list;
    }
    rows.value = await merchantApi.pricing(selectedDeviceId.value || undefined);
    const d: Record<string, string> = {};
    rows.value.forEach((p) => {
      d[p.skuId] = p.overridePriceCents != null ? (p.overridePriceCents / 100).toFixed(2) : '';
    });
    draft.value = d;
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

function onDevicePick(e: { detail: { value: string } }) {
  const idx = Number(e.detail.value);
  selectedDeviceId.value = deviceOptions.value[idx]?.deviceId || '';
  load();
}

async function savePrice(p: MerchantSkuPricing) {
  if (!canEdit.value) return;
  const raw = (draft.value[p.skuId] || '').trim();
  const priceCents = raw === '' ? null : Math.round(parseFloat(raw) * 100);
  if (raw !== '' && (Number.isNaN(priceCents) || priceCents! < 0)) {
    uni.showToast({ title: '价格无效', icon: 'none' });
    return;
  }
  try {
    await merchantApi.updatePricing(p.skuId, { deviceId: p.deviceId, priceCents });
    uni.showToast({ title: '已更新', icon: 'success' });
    await load();
  } catch (e) {
    uni.showToast({ title: e instanceof Error ? e.message : '保存失败', icon: 'none' });
  }
}
</script>

<style scoped>
.picker { padding: 8px 0; font-weight: 600; }
.warn { color: #d97706; display: block; margin-top: 8rpx; }
.row { display: flex; justify-content: space-between; align-items: center; }
.name { font-weight: 600; display: block; }
.effective { font-size: 32rpx; font-weight: 700; color: #0f766e; }
.price-col { text-align: right; min-width: 160rpx; }
.input { width: 140rpx; text-align: right; border: 1px solid #e2e8f0; border-radius: 6px; padding: 6px; margin-top: 6rpx; }
.err { color: #ef4444; }
</style>
