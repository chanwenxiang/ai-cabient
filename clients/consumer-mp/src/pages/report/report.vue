<template>
  <view class="page">
    <view class="hero">
      <text class="hero-title">故障报修</text>
      <text class="hero-sub">柜机异常？提交后运营会尽快处理</text>
    </view>

    <view class="card">
      <text class="field-label">柜机编号</text>
      <input
        class="input"
        :value="deviceId"
        placeholder="例如 CAB-001"
        @input="onDeviceInput"
      />

      <text class="field-label">问题类型</text>
      <view class="issue-grid">
        <view
          v-for="item in issueOptions"
          :key="item.value"
          class="issue-chip"
          :class="{ active: issueType === item.value }"
          @click="issueType = item.value"
        >
          {{ item.label }}
        </view>
      </view>

      <text class="field-label">补充说明（选填）</text>
      <textarea
        v-model="description"
        class="textarea"
        maxlength="200"
        placeholder="描述具体情况，便于快速处理"
      />

      <button class="btn-primary" hover-class="btn-hover" :loading="submitting" :disabled="submitting" @click="onSubmit">
        {{ submitting ? '提交中…' : '提交报修' }}
      </button>
      <text v-if="err" class="err">{{ err }}</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app';
import { ref } from 'vue';
import { consumerApi, ensureConsumerAuth } from '@/utils/consumer-api';
import { eventInputValue, readDomFieldValue } from '@/utils/form-bind';

const deviceId = ref('');
const issueType = ref('DOOR_OPEN');
const description = ref('');
const submitting = ref(false);
const err = ref('');

const issueOptions = [
  { value: 'DOOR_OPEN', label: '打不开门' },
  { value: 'DOOR_CLOSE', label: '门关不上' },
  { value: 'PRODUCT', label: '商品异常' },
  { value: 'PAYMENT', label: '扣款问题' },
  { value: 'OTHER', label: '其他' }
];

onLoad((opts) => {
  const fromQuery = (opts?.deviceId as string) || '';
  const fromStorage = uni.getStorageSync('last_device_id') || '';
  deviceId.value = fromQuery || fromStorage || '';
});

function onDeviceInput(e: unknown) {
  deviceId.value = eventInputValue(e);
}

function onSubmit() {
  let id = deviceId.value.trim().toUpperCase();
  if (!id) id = readDomFieldValue('input').toUpperCase();
  deviceId.value = id;
  if (!id) {
    err.value = '请输入柜机编号';
    return;
  }
  if (!/^[A-Z0-9][A-Z0-9\-]{2,31}$/.test(id)) {
    err.value = '柜机编号格式不正确，例如 CAB-001';
    return;
  }
  if (submitting.value) return;
  submitting.value = true;
  err.value = '';
  ensureConsumerAuth().then(async (ok) => {
    if (!ok) {
      submitting.value = false;
      uni.navigateTo({
        url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/report/report')
      });
      return;
    }
    try {
      const res = await consumerApi.reportDeviceFault(id, {
        issueType: issueType.value,
        description: description.value.trim() || undefined
      });
      uni.showToast({ title: res.message || '已提交', icon: 'success' });
      setTimeout(() => uni.navigateBack(), 800);
    } catch (e) {
      err.value = e instanceof Error ? e.message : '提交失败';
    } finally {
      submitting.value = false;
    }
  });
}
</script>

<style scoped>
.page { min-height: 100vh; background: #f7f7f7; padding: 24rpx; box-sizing: border-box; }
.hero { padding: 16rpx 8rpx 24rpx; }
.hero-title { font-size: 40rpx; font-weight: 700; color: #191919; display: block; }
.hero-sub { font-size: 26rpx; color: #888; margin-top: 8rpx; display: block; }
.card { background: #fff; border-radius: 24rpx; padding: 32rpx; }
.field-label { font-size: 26rpx; color: #666; display: block; margin-bottom: 12rpx; margin-top: 8rpx; }
.input {
  background: #f7f7f7;
  border-radius: 12rpx;
  padding: 22rpx 24rpx;
  font-size: 30rpx;
  margin-bottom: 16rpx;
}
.issue-grid { display: flex; flex-wrap: wrap; gap: 12rpx; margin-bottom: 20rpx; }
.issue-chip {
  padding: 14rpx 24rpx;
  border-radius: 32rpx;
  background: #f7f7f7;
  font-size: 26rpx;
  color: #666;
}
.issue-chip.active { background: #e8f8ef; color: #07c160; font-weight: 600; }
.textarea {
  width: 100%;
  min-height: 160rpx;
  background: #f7f7f7;
  border-radius: 12rpx;
  padding: 20rpx 24rpx;
  font-size: 28rpx;
  box-sizing: border-box;
  margin-bottom: 24rpx;
}
.btn-primary {
  margin: 0;
  background: #07c160;
  color: #fff;
  border-radius: 12rpx;
  font-size: 32rpx;
  font-weight: 600;
  line-height: 88rpx;
  height: 88rpx;
}
.btn-primary::after { border: none; }
.btn-hover { opacity: 0.85; }
.err { color: #fa5151; font-size: 26rpx; display: block; margin-top: 16rpx; text-align: center; }
</style>
