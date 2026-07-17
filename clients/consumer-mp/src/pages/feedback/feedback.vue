<template>
  <view class="page">
    <view class="hero">
      <text class="hero-title">意见反馈</text>
      <text class="hero-sub">投诉、建议或表扬，我们都会认真查看</text>
    </view>

    <view class="card">
      <text class="field-label">反馈类型</text>
      <view class="issue-grid">
        <view
          v-for="item in typeOptions"
          :key="item.value"
          class="issue-chip"
          :class="{ active: feedbackType === item.value }"
          @click="feedbackType = item.value"
        >
          {{ item.label }}
        </view>
      </view>

      <text class="field-label">内容</text>
      <textarea
        class="textarea"
        :value="content"
        maxlength="500"
        placeholder="请描述你的问题或建议"
        @input="content = eventInputValue($event)"
      />
      <text class="counter">{{ content.length }}/500</text>

      <text class="field-label">联系方式（选填）</text>
      <input
        class="input"
        :value="contactInfo"
        placeholder="手机号或微信，方便回访"
        @input="contactInfo = eventInputValue($event)"
      />

      <text class="field-label">柜机编号（选填）</text>
      <input
        class="input"
        :value="deviceId"
        placeholder="例如 CAB-001"
        @input="deviceId = eventInputValue($event)"
      />

      <button
        class="btn-primary"
        hover-class="btn-hover"
        :loading="submitting"
        :disabled="submitting"
        @click="onSubmit"
      >
        {{ submitting ? '提交中…' : '提交反馈' }}
      </button>
      <text v-if="err" class="err">{{ err }}</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app';
import { ref } from 'vue';
import { consumerApi, ensureConsumerAuth } from '@/utils/consumer-api';
import { eventInputValue, readDomFieldValue, readDomTextarea } from '@/utils/form-bind';

const feedbackType = ref('SUGGESTION');
const content = ref('');
const contactInfo = ref('');
const deviceId = ref('');
const submitting = ref(false);
const err = ref('');

const typeOptions = [
  { value: 'COMPLAINT', label: '投诉' },
  { value: 'SUGGESTION', label: '建议' },
  { value: 'BUG', label: '故障/缺陷' },
  { value: 'PRAISE', label: '表扬' }
];

onLoad((opts) => {
  const fromQuery = (opts?.deviceId as string) || '';
  const fromStorage = uni.getStorageSync('last_device_id') || '';
  deviceId.value = fromQuery || fromStorage || '';
});

async function onSubmit() {
  let text = content.value.trim();
  if (!text) text = readDomTextarea();
  if (text.length < 4) {
    err.value = '请至少填写 4 个字';
    return;
  }
  if (submitting.value) return;
  if (!(await ensureConsumerAuth())) {
    err.value = '请先完成微信授权';
    return;
  }
  submitting.value = true;
  err.value = '';
  try {
    let contact = contactInfo.value.trim();
    let device = deviceId.value.trim().toUpperCase();
    if (!contact) contact = readDomFieldValue('input');
    await consumerApi.submitFeedback({
      feedbackType: feedbackType.value,
      content: text,
      contactInfo: contact || undefined,
      deviceId: device || undefined
    });
    uni.showToast({ title: '已提交', icon: 'success' });
    setTimeout(() => uni.navigateBack(), 800);
  } catch (e) {
    err.value = e instanceof Error ? e.message : '提交失败';
  } finally {
    submitting.value = false;
  }
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
  min-height: 180rpx;
  background: #f7f7f7;
  border-radius: 12rpx;
  padding: 20rpx 24rpx;
  font-size: 28rpx;
  box-sizing: border-box;
  margin-bottom: 8rpx;
}
.counter { display: block; text-align: right; font-size: 22rpx; color: #bbb; margin-bottom: 12rpx; }
.btn-primary {
  margin: 16rpx 0 0;
  background: #07c160;
  color: #fff;
  border-radius: 12rpx;
  font-size: 32rpx;
  font-weight: 600;
  line-height: 88rpx;
  height: 88rpx;
}
.btn-primary[disabled] { opacity: 0.55; }
.btn-primary::after { border: none; }
.btn-hover { opacity: 0.85; }
.err { color: #fa5151; font-size: 26rpx; display: block; margin-top: 16rpx; text-align: center; }
</style>
