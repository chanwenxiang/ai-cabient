<template>
  <view>
    <view v-if="!canView" class="card"><text class="err">当前账号无柜机详情权限</text></view>
    <view v-else-if="loading" class="card">加载中…</view>
    <view v-else-if="error" class="card"
      ><text class="err">{{ error }}</text></view
    >
    <view v-else>
      <view class="card">
        <image
          class="device-hero"
          src="/static/device-default.png"
          mode="aspectFill"
          aria-hidden="true"
        />
        <text class="title">{{ deviceName }}</text>
        <text class="meta"
          >{{ deviceId }} · {{ online ? '在线' : '离线' }}{{ salesLocked ? ' · 停售中' : '' }}</text
        >
        <text v-if="salesLocked" class="locked-banner"
          >柜机已锁机停售，消费者无法开门；补货仍可按任务操作</text
        >
        <text class="meta">当前 {{ currentTemp }} / 目标 {{ targetTemp }}</text>
        <view class="pref-row" @click="togglePreferred">
          <text class="pref-star" :class="{ on: isPreferred }">★</text>
          <text>{{ isPreferred ? '常驻柜（点击取消）' : '设为常驻柜' }}</text>
        </view>
        <view class="action-row">
          <view v-if="canReplenishView" class="btn-primary action-btn" @click="goReplenishment"
            >补货任务</view
          >
          <view v-if="canRequest" class="btn-primary action-btn" @click="goRequest">发起要货</view>
        </view>
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
            <text class="meta"
              >库存 {{ s.bookQty }}/{{ s.maxLevel || s.parLevel || '未设上限' }}</text
            >
            <input
              v-if="canEditSlots"
              v-model="slotPar[s.slotCode]"
              class="input-sm"
              type="number"
              placeholder="目标库存"
            />
          </view>
        </view>
        <view v-if="canEditSlots" class="btn-primary" style="margin-top: 12px" @click="saveSlots">
          {{ savingSlots ? '保存中…' : '保存货道' }}
        </view>
      </view>

      <view v-if="tempHistory.length" class="card">
        <view class="row">
          <text class="section">温度历史（近 24h）</text>
          <text class="meta">{{ tempSummaryText }}</text>
        </view>
        <view v-for="(r, i) in tempHistory" :key="i" class="temp-row">
          <text class="meta">{{ formatTime(r.reportedAt) }}</text>
          <text
            class="temp-val"
            :class="{
              warn:
                targetTempNum != null &&
                Math.abs(r.tempC - targetTempNum) > 2
            }"
            >{{ r.tempC }}°C</text
          >
          <text class="meta">{{ i > 0 ? (r.tempC >= tempHistory[i - 1].tempC ? '↑' : '↓') : '—' }}</text>
        </view>
      </view>

      <view v-if="velocity.length" class="card">
        <view class="row">
          <text class="section">商品动销 / 补货点</text>
          <text class="meta">近 14 日</text>
        </view>
        <view v-for="v in velocity" :key="v.skuId" class="velocity-row">
          <view class="velocity-main">
            <text class="sku-name">{{ v.skuName }}</text>
            <text class="meta">{{ v.skuId }}</text>
          </view>
          <view class="velocity-data">
            <text>7日 {{ v.soldQty7d }}</text>
            <text>14日 {{ v.soldQty14d }}</text>
            <text>日均 {{ Number(v.avgDailySales).toFixed(1) }}</text>
            <text class="rop">补货点 {{ v.ropPoint }}</text>
          </view>
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
import type {
  DeviceSlot,
  DeviceTemperatureReading,
  MerchantMe,
  MerchantSkuVelocity
} from '@aicabinet/shared-types';

const { me, refresh: refreshMe } = useMerchantMe();
const loading = ref(true);
const error = ref('');
let loadSeq = 0;
const deviceId = ref('');
const merchantId = ref('');
const deviceName = ref('');
const online = ref(false);
const salesLocked = ref(false);
const currentTemp = ref('暂无');
const targetTemp = ref('未设置');
const formName = ref('');
const formTargetTemp = ref('');
const formRemark = ref('');
const saving = ref(false);
const savingSlots = ref(false);
const slots = ref<DeviceSlot[]>([]);
const slotPar = ref<Record<string, string>>({});
const tempHistory = ref<DeviceTemperatureReading[]>([]);
const velocity = ref<MerchantSkuVelocity[]>([]);
const isPreferred = ref(false);

const targetTempNum = computed(() => {
  const n = Number(String(targetTemp.value).replace('°C', ''));
  return Number.isFinite(n) ? n : null;
});
const tempSummaryText = computed(() => {
  if (!tempHistory.value.length) return '暂无数据';
  const temps = tempHistory.value.map((r) => r.tempC);
  const min = Math.min(...temps);
  const max = Math.max(...temps);
  const avg = temps.reduce((a, b) => a + b, 0) / temps.length;
  return `最高 ${max}°C · 最低 ${min}°C · 均值 ${avg.toFixed(1)}°C`;
});

function formatTime(iso?: string) {
  if (!iso) return '';
  const d = new Date(iso);
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

const canView = computed(() => hasPerm(me.value, 'merchant:devices:detail'));
const canEditDevice = computed(() => hasPerm(me.value, 'merchant:devices:edit'));
const canEditSlots = computed(() => canEditPlanogramForMerchant(me.value, merchantId.value));
const canReplenishView = computed(() => hasPerm(me.value, 'merchant:replenishment:view'));
const canRequest = computed(() => hasPerm(me.value, 'merchant:replenishment:request'));

onLoad((opts) => {
  if (!uni.getStorageSync('merchant_token')) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  try {
    deviceId.value = decodeURIComponent((opts?.id as string) || '');
  } catch {
    deviceId.value = String(opts?.id || '');
  }
  if (!deviceId.value) {
    error.value = '柜机不存在';
    loading.value = false;
    return;
  }
  loadDetail();
});

async function loadDetail() {
  const seq = ++loadSeq;
  try {
    await refreshMe();
  } catch {
    if (!uni.getStorageSync('merchant_token')) return;
    me.value = (uni.getStorageSync('merchant_me') as MerchantMe) || null;
  }
  if (seq !== loadSeq) return;
  if (!canView.value) {
    loading.value = false;
    uni.showToast({ title: '无柜机详情权限', icon: 'none' });
    uni.navigateBack({ fail: () => uni.switchTab({ url: '/pages/home/home' }) });
    return;
  }
  loading.value = true;
  error.value = '';
  try {
    const settings = await merchantApi.deviceSettings(deviceId.value);
    if (seq !== loadSeq) return;
    merchantId.value = (settings.merchantId as string) || '';
    deviceName.value = (settings.deviceName as string) || deviceId.value;
    online.value = ((settings.onlineStatus as string) || '').toUpperCase() === 'ONLINE';
    salesLocked.value = !!(settings as { salesLocked?: boolean }).salesLocked;
    currentTemp.value = settings.currentTempC != null ? settings.currentTempC + '°C' : '暂无';
    targetTemp.value = settings.targetTempC != null ? settings.targetTempC + '°C' : '未设置';
    formName.value = (settings.deviceName as string) || '';
    formTargetTemp.value = settings.targetTempC != null ? String(settings.targetTempC) : '';
    formRemark.value = (settings.opsRemark as string) || '';
    const [list, temps, vel] = await Promise.all([
      merchantApi.deviceSlots(deviceId.value).catch(() => [] as DeviceSlot[]),
      merchantApi.deviceTemperatureHistory(deviceId.value, 24).catch(
        () => [] as DeviceTemperatureReading[]
      ),
      merchantApi.skuVelocity(deviceId.value).catch(() => [] as MerchantSkuVelocity[])
    ]);
    if (seq !== loadSeq) return;
    slots.value = list;
    tempHistory.value = temps;
    velocity.value = vel;
    const par: Record<string, string> = {};
    list.forEach((s) => {
      par[s.slotCode] = s.parLevel != null ? String(s.parLevel) : '';
    });
    slotPar.value = par;
    isPreferred.value =
      String(getPreferredDeviceId() || '')
        .trim()
        .toUpperCase() ===
      String(deviceId.value || '')
        .trim()
        .toUpperCase();
  } catch (e) {
    if (seq !== loadSeq) return;
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    if (seq === loadSeq) loading.value = false;
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

function goReplenishment() {
  uni.navigateTo({
    url: `/pages/replenishment/replenishment?deviceId=${encodeURIComponent(deviceId.value)}`
  });
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
  if (formTargetTemp.value !== '') {
    const temp = Number(formTargetTemp.value);
    // 用 Number + Number.isInteger 替代 parseInt，避免 "25.5" 被静默截断为 25
    if (!Number.isInteger(temp)) {
      uni.showToast({ title: '目标温度须为整数', icon: 'none' });
      return;
    }
    body.targetTempC = temp;
  }
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
  const body: {
    slotCode: string;
    rowNo: number;
    colNo: number;
    slotType: string;
    assignedSkuId?: string;
    parLevel: number;
    minLevel?: number;
    maxLevel?: number;
    enabled?: boolean;
  }[] = [];
  for (const s of slots.value) {
    const par = Number(slotPar.value[s.slotCode] || s.parLevel);
    if (!Number.isInteger(par) || par < 0) {
      uni.showToast({ title: `货道 ${s.slotCode} 容量无效`, icon: 'none' });
      return;
    }
    body.push({
      slotCode: s.slotCode,
      rowNo: s.rowNo,
      colNo: s.colNo,
      slotType: s.slotType,
      assignedSkuId: s.assignedSkuId,
      parLevel: par,
      minLevel: s.minLevel,
      maxLevel: s.maxLevel,
      enabled: s.enabled
    });
  }
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
.temp-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
  padding: 12rpx 0;
  border-bottom: 1rpx solid #f1f5f9;
}
.temp-row:last-child {
  border-bottom: none;
}
.temp-val {
  font-size: 26rpx;
  font-weight: 700;
  color: #0f766e;
}
.temp-val.warn {
  color: #b45309;
}

.velocity-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
  padding: 16rpx 0;
  border-bottom: 1rpx solid #f1f5f9;
}
.velocity-row:last-child {
  border-bottom: none;
}
.velocity-main {
  flex: 1;
  min-width: 0;
}
.velocity-main .sku-name {
  display: block;
  font-size: 27rpx;
  font-weight: 650;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.velocity-data {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8rpx 16rpx;
  font-size: 22rpx;
  color: #64748b;
}
.velocity-data .rop {
  color: #b45309;
  font-weight: 700;
  background: #fffbeb;
  padding: 2rpx 10rpx;
  border-radius: 999rpx;
}

.device-hero {
  width: 100%;
  height: 260rpx;
  border-radius: 18rpx;
  background: #ecfdf5;
  margin-bottom: 16rpx;
  display: block;
}
.title {
  font-size: 32rpx;
  font-weight: 600;
  display: block;
}
.locked-banner {
  display: block;
  margin-top: 12rpx;
  padding: 12rpx 16rpx;
  border-radius: 12rpx;
  background: #fef3c7;
  color: #92400e;
  font-size: 24rpx;
  line-height: 1.4;
}
.section {
  font-weight: 600;
}
.row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12rpx;
}
.pref-row {
  margin-top: 16rpx;
  display: flex;
  align-items: center;
  gap: 8rpx;
  font-size: 26rpx;
  color: #0f766e;
}
.pref-star {
  color: #cbd5e1;
  font-size: 32rpx;
}
.pref-star.on {
  color: #f59e0b;
}
.input {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 10px;
  margin: 8px 0;
}
.input-sm {
  width: 100%;
  font-size: 22rpx;
  margin-top: 4rpx;
  border: 1px solid #e2e8f0;
  border-radius: 4px;
  padding: 4px;
}
.slot-code {
  font-weight: 600;
  display: block;
}
.action-row {
  display: flex;
  gap: 12px;
  margin-top: 16px;
}
.action-btn {
  flex: 1;
  margin-top: 0;
}
.err {
  color: #ef4444;
}
</style>
