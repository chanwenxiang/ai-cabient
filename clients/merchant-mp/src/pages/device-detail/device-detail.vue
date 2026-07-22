<template>
  <view>
    <view v-if="!canView" class="card"><text class="err">当前账号无柜机详情权限</text></view>
    <view v-else-if="loading" class="card">加载中…</view>
    <view v-else-if="error" class="card"><text class="err">{{ error }}</text></view>
    <view v-else>
      <view class="card">
        <text class="title">{{ deviceName }}</text>
        <text class="meta">{{ deviceId }} · {{ online ? '在线' : '离线' }}</text>
        <text class="meta">当前 {{ currentTemp }} / 目标 {{ targetTemp }}</text>
        <view class="pref-row" @click="togglePreferred">
          <text class="pref-star" :class="{ on: isPreferred }">★</text>
          <text>{{ isPreferred ? '常驻柜（点击取消）' : '设为常驻柜' }}</text>
        </view>
        <view
          v-if="canRequest"
          class="btn-primary"
          style="margin-top:16px"
          @click="goRequest"
        >发起要货</view>
      </view>

      <view v-if="canEditDevice" class="card">
        <text class="section">柜机设置</text>
        <input v-model="formName" class="input" placeholder="显示名称" />
        <input v-model="formTargetTemp" class="input" type="number" placeholder="目标温度(°C)" />
        <input v-model="formRemark" class="input" placeholder="备注" />
        <view class="btn-primary" @click="saveSettings">{{ saving ? '保存中…' : '保存设置' }}</view>
      </view>

      <view class="card">
        <view class="row">
          <text class="section">货道</text>
          <text v-if="!canEditSlots" class="meta">只读（平台未开启或未授权）</text>
        </view>
        <view class="slot-grid">
          <view v-for="s in slots" :key="s.slotCode" class="slot-cell">
            <text class="slot-code">{{ s.slotCode }}</text>
            <text>{{ s.assignedSkuName || '空' }}</text>
            <text class="meta">库存 {{ s.bookQty }}/{{ s.parLevel }}</text>
            <input
              v-if="canEditSlots"
              v-model="slotPar[s.slotCode]"
              class="input-sm"
              type="number"
              placeholder="par"
            />
          </view>
        </view>
        <view v-if="canEditSlots" class="btn-primary" style="margin-top:12px" @click="saveSlots">
          {{ savingSlots ? '保存中…' : '保存货道' }}
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app';
import { computed, ref } from 'vue';
import { merchantApi, hasPerm } from '@/utils/merchant-api';
import { useMerchantMe, canEditPlanogramForMerchant } from '@/composables/useMerchantMe';
import {
  clearPreferredDeviceId,
  getPreferredDeviceId,
  setPreferredDeviceId
} from '@/utils/preferred-device';
import type { DeviceSlot, MerchantMe } from '@aicabinet/shared-types';

const { me, refresh: refreshMe } = useMerchantMe();
const loading = ref(true);
const error = ref('');
const deviceId = ref('');
const merchantId = ref('');
const deviceName = ref('');
const online = ref(false);
const currentTemp = ref('-');
const targetTemp = ref('-');
const formName = ref('');
const formTargetTemp = ref('');
const formRemark = ref('');
const saving = ref(false);
const savingSlots = ref(false);
const slots = ref<DeviceSlot[]>([]);
const slotPar = ref<Record<string, string>>({});
const isPreferred = ref(false);

const canView = computed(() => hasPerm(me.value, 'merchant:devices:detail'));
const canEditDevice = computed(() => hasPerm(me.value, 'merchant:devices:edit'));
const canEditSlots = computed(() => canEditPlanogramForMerchant(me.value, merchantId.value));
const canRequest = computed(() => hasPerm(me.value, 'merchant:replenishment:request'));

onLoad((opts) => {
  if (!uni.getStorageSync('merchant_token')) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  deviceId.value = decodeURIComponent((opts?.id as string) || '');
  if (!deviceId.value) {
    error.value = '柜机不存在';
    loading.value = false;
    return;
  }
  loadDetail();
});

async function loadDetail() {
  try {
    await refreshMe();
  } catch {
    me.value = (uni.getStorageSync('merchant_me') as MerchantMe) || null;
  }
  if (!canView.value) {
    loading.value = false;
    uni.showToast({ title: '无柜机详情权限', icon: 'none' });
    uni.navigateBack({ fail: () => uni.switchTab({ url: '/pages/home/home' }) });
    return;
  }
  loading.value = true;
  try {
    const settings = await merchantApi.deviceSettings(deviceId.value);
    merchantId.value = (settings.merchantId as string) || '';
    deviceName.value = (settings.deviceName as string) || deviceId.value;
    online.value = ((settings.onlineStatus as string) || '').toUpperCase() === 'ONLINE';
    currentTemp.value = settings.currentTempC != null ? settings.currentTempC + '°C' : '暂无';
    targetTemp.value = settings.targetTempC != null ? settings.targetTempC + '°C' : '未设置';
    formName.value = (settings.deviceName as string) || '';
    formTargetTemp.value = settings.targetTempC != null ? String(settings.targetTempC) : '';
    formRemark.value = (settings.opsRemark as string) || '';
    const list = await merchantApi.deviceSlots(deviceId.value);
    slots.value = list;
    const par: Record<string, string> = {};
    list.forEach((s) => {
      par[s.slotCode] = String(s.parLevel);
    });
    slotPar.value = par;
    isPreferred.value = getPreferredDeviceId() === deviceId.value;
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

function togglePreferred() {
  if (!deviceId.value) return;
  if (isPreferred.value) {
    clearPreferredDeviceId();
    isPreferred.value = false;
    uni.showToast({ title: '已取消常驻', icon: 'none' });
    return;
  }
  setPreferredDeviceId(deviceId.value);
  isPreferred.value = true;
  uni.showToast({ title: '已设为常驻柜', icon: 'success' });
}

function goRequest() {
  uni.navigateTo({
    url: `/pages/request/request?deviceId=${encodeURIComponent(deviceId.value)}`
  });
}

async function saveSettings() {
  if (saving.value) return;
  const body: Record<string, unknown> = {
    deviceName: formName.value.trim() || null,
    opsRemark: formRemark.value.trim() || null
  };
  if (formTargetTemp.value !== '') body.targetTempC = parseInt(formTargetTemp.value, 10);
  saving.value = true;
  try {
    await merchantApi.updateDeviceSettings(deviceId.value, body);
    uni.showToast({ title: '已保存', icon: 'success' });
    await loadDetail();
  } catch (e) {
    uni.showToast({ title: e instanceof Error ? e.message : '保存失败', icon: 'none' });
  } finally {
    saving.value = false;
  }
}

async function saveSlots() {
  if (savingSlots.value) return;
  const body = slots.value.map((s) => ({
    slotCode: s.slotCode,
    rowNo: s.rowNo,
    colNo: s.colNo,
    slotType: s.slotType,
    assignedSkuId: s.assignedSkuId,
    parLevel: parseInt(slotPar.value[s.slotCode] || String(s.parLevel), 10),
    minLevel: s.minLevel,
    maxLevel: s.maxLevel,
    enabled: s.enabled
  }));
  savingSlots.value = true;
  try {
    await merchantApi.upsertSlots(deviceId.value, body);
    uni.showToast({ title: '货道已保存', icon: 'success' });
    await loadDetail();
  } catch (e) {
    uni.showToast({ title: e instanceof Error ? e.message : '保存失败', icon: 'none' });
  } finally {
    savingSlots.value = false;
  }
}
</script>

<style scoped>
.title { font-size: 32rpx; font-weight: 600; display: block; }
.section { font-weight: 600; }
.row { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12rpx; }
.pref-row {
  margin-top: 16rpx;
  display: flex;
  align-items: center;
  gap: 8rpx;
  font-size: 26rpx;
  color: #0f766e;
}
.pref-star { color: #cbd5e1; font-size: 32rpx; }
.pref-star.on { color: #f59e0b; }
.input { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 10px; margin: 8px 0; }
.input-sm { width: 100%; font-size: 22rpx; margin-top: 4rpx; border: 1px solid #e2e8f0; border-radius: 4px; padding: 4px; }
.slot-code { font-weight: 600; display: block; }
.err { color: #ef4444; }
</style>
