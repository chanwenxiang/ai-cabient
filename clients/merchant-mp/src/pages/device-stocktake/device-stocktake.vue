<script setup lang="ts">
/**
 * 柜机扫码盘点（CB-050 扩展，嗨便利对照）：扫码/选择柜机 → 逐货道实盘提交。
 * 复用 admin 同一盘点链路：照片凭证按商户 ops-policy 强制、账实差走 SLOT_DISCREPANCY 闭环。
 * 凭证照片按柜机归档（DEVICE_STOCKTAKE），可选附带定位（写入盘点事件，防伪）。
 */
import { onLoad } from '@dcloudio/uni-app';
import { computed, ref } from 'vue';
import { showError, showSuccess } from '@/utils/notify';
import { hasPerm, isMerchantLoggedIn, merchantApi } from '@/utils/merchant-api';
import { useMerchantMe } from '@/composables/useMerchantMe';
import { scanCabinetDeviceId } from '@/utils/scan-cabinet';
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';
import AppNavBar from '@aicabinet/shared-uni/components/app-nav-bar.vue';
import type { DeviceSlot } from '@aicabinet/shared-types';

const { me } = useMerchantMe();
const deviceId = ref('');
const deviceName = ref('');
const slots = ref<DeviceSlot[]>([]);
const physicalInputs = ref<Record<string, string>>({});
const evidenceUrl = ref('');
const loading = ref(false);
const submitting = ref<Record<string, boolean>>({});

const canStocktake = computed(() => hasPerm(me.value, 'merchant:slots:edit'));

onLoad((opts) => {
  if (!isMerchantLoggedIn()) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  const id = opts?.deviceId ? decodeURIComponent(String(opts.deviceId)) : '';
  if (id) {
    deviceId.value = id;
    void loadSlots();
  }
});

// AU-138：弃用页内正则（EG 改版前旧口径，剥前缀+贪婪抓数字段），改走 shared-uni 权威解析
async function scanDevice() {
  const id = await scanCabinetDeviceId();
  if (!id) {
    return; // 失败/取消/无效编号已在封装内提示或降级手输
  }
  deviceId.value = id;
  void loadSlots();
}

async function loadSlots() {
  if (!deviceId.value) return;
  loading.value = true;
  try {
    const list = await merchantApi.deviceSlots(deviceId.value);
    slots.value = list.filter((s) => !!s.assignedSkuId);
    const inputs: Record<string, string> = {};
    list.forEach((s) => {
      inputs[s.slotCode] = s.bookQty == null ? '' : String(s.bookQty);
    });
    physicalInputs.value = inputs;
    if (!slots.value.length) {
      showError('该柜没有已绑定商品的货道');
    }
  } catch (e) {
    showError(e instanceof Error ? e.message : '加载货道失败');
  } finally {
    loading.value = false;
  }
}

function chooseEvidence() {
  uni.chooseImage({
    count: 1,
    sourceType: ['camera'],
    success: (res) => {
      const path = res.tempFilePaths?.[0];
      if (path) void uploadEvidence(path);
    }
  });
}

async function uploadEvidence(path: string) {
  try {
    const att = await merchantApi.uploadDeviceStocktakeEvidenceFile(deviceId.value, path);
    evidenceUrl.value = att.url || '';
    showSuccess('凭证已上传');
  } catch (e) {
    showError(e instanceof Error ? e.message : '凭证上传失败');
  }
}

function submitStocktake(slot: DeviceSlot) {
  const raw = physicalInputs.value[slot.slotCode];
  const qty = Number(raw);
  if (raw == null || raw === '' || Number.isNaN(qty) || qty < 0) {
    showError(`请填写货道 ${slot.slotCode} 的实盘数量`);
    return;
  }
  uni.showModal({
    title: `盘点货道 ${slot.slotCode}`,
    content: `系统 ${slot.bookQty} → 实盘 ${qty}${evidenceUrl.value ? '（含凭证）' : ''}。确认提交？`,
    success: (res) => {
      if (res.confirm) void doSubmit(slot, qty);
    }
  });
}

async function doSubmit(slot: DeviceSlot, qty: number) {
  submitting.value = { ...submitting.value, [slot.slotCode]: true };
  try {
    const location = await fetchLocation();
    await merchantApi.deviceSlotStocktake(deviceId.value, {
      slotCode: slot.slotCode,
      physicalQty: qty,
      adjustBookQty: false,
      photoEvidenceUrl: evidenceUrl.value || undefined,
      latitude: location?.latitude,
      longitude: location?.longitude
    });
    showSuccess(`货道 ${slot.slotCode} 已记录实盘`);
    await loadSlots();
  } catch (e) {
    showError(e instanceof Error ? e.message : '盘点提交失败');
  } finally {
    submitting.value = { ...submitting.value, [slot.slotCode]: false };
  }
}

function fetchLocation(): Promise<{ latitude: number; longitude: number } | null> {
  return new Promise((resolve) => {
    uni.getLocation({
      type: 'gcj02',
      success: (res) => resolve({ latitude: res.latitude, longitude: res.longitude }),
      fail: () => resolve(null)
    });
  });
}
</script>

<template>
  <view>
    <app-nav-bar title="柜机盘点" home-url="/pages/home/home" />
    <view class="page-body">
      <view v-if="!canStocktake" class="card"><text class="err">当前账号无盘点权限</text></view>
      <template v-else>
        <view class="card">
          <text class="section">选择柜机</text>
          <view v-if="!deviceId" class="scan-empty">
            <text class="meta">扫描柜机二维码开始盘点</text>
            <app-button label="扫码识别柜机" @click="scanDevice" />
          </view>
          <view v-else class="scan-done">
            <text class="title">柜机 {{ deviceId }}</text>
            <text class="meta">逐货道填写实盘数量后提交；照片凭证按商户要求强制</text>
            <app-button
              variant="secondary"
              compact
              :block="false"
              label="重新扫码"
              @click="scanDevice"
            />
          </view>
        </view>

        <template v-if="deviceId">
          <view class="card">
            <text class="section">盘点凭证</text>
            <view class="evidence-row">
              <app-button
                variant="secondary"
                compact
                :block="false"
                :label="evidenceUrl ? '已上传（重拍替换）' : '拍现场照'"
                @click="chooseEvidence"
              />
              <text v-if="evidenceUrl" class="meta">凭证已随本次盘点提交</text>
            </view>
          </view>

          <view class="card">
            <view class="row">
              <text class="section">货道实盘</text>
              <text v-if="loading" class="meta">{{ UI_COPY.loading }}</text>
            </view>
            <view v-for="s in slots" :key="s.slotCode" class="stock-row">
              <view class="stock-main">
                <text class="stock-code">{{ s.slotCode }}</text>
                <text class="meta">{{ s.assignedSkuName || s.assignedSkuId }}</text>
              </view>
              <text class="stock-book">系统 {{ s.bookQty ?? 0 }}</text>
              <input
                v-model="physicalInputs[s.slotCode]"
                class="input stock-input"
                type="number"
                placeholder="实盘"
              />
              <app-button
                compact
                :block="false"
                :loading="!!submitting[s.slotCode]"
                label="提交"
                @click="submitStocktake(s)"
              />
            </view>
            <text v-if="!loading && !slots.length" class="meta">暂无可盘点的货道</text>
          </view>
        </template>
      </template>
    </view>
  </view>
</template>

<style scoped>
.page-body {
  padding: 24rpx 24rpx calc(48rpx + env(safe-area-inset-bottom));
}
.card {
  background: #fff;
  border-radius: 16rpx;
  padding: 24rpx;
  margin-bottom: 24rpx;
}
.section {
  font-size: 30rpx;
  font-weight: 600;
}
.row {
  display: flex;
  align-items: center;
  gap: 12rpx;
}
.meta {
  font-size: 24rpx;
  color: #909399;
}
.title {
  font-size: 32rpx;
  font-weight: 700;
  display: block;
  margin-bottom: 8rpx;
}
.err {
  color: #d03050;
}
.scan-empty {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
  margin-top: 12rpx;
}
.scan-done {
  margin-top: 12rpx;
  display: flex;
  flex-direction: column;
  gap: 8rpx;
  align-items: flex-start;
}
.evidence-row {
  display: flex;
  align-items: center;
  gap: 16rpx;
  margin-top: 12rpx;
}
.stock-row {
  display: flex;
  align-items: center;
  gap: 12rpx;
  padding: 16rpx 0;
  border-bottom: 1rpx solid #f0f0f0;
}
.stock-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.stock-code {
  font-weight: 600;
}
.stock-book {
  font-size: 24rpx;
  color: #909399;
  font-variant-numeric: tabular-nums;
}
.stock-input {
  width: 120rpx;
  border: 1rpx solid #dcdfe6;
  border-radius: 8rpx;
  padding: 8rpx 12rpx;
  font-size: 26rpx;
}
</style>
