<template>
  <view class="page">
    <view class="tabs">
      <text class="tab" :class="{ active: tab === 'submit' }" @click="tab = 'submit'"
        >提交反馈</text
      >
      <text class="tab" :class="{ active: tab === 'mine' }" @click="onMineTab">我的反馈</text>
    </view>

    <view v-if="tab === 'submit'">
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

    <view v-else>
      <view v-if="historyLoading" class="state">加载中…</view>
      <view v-else-if="historyError" class="state">
        <text class="err">{{ historyError }}</text>
        <button class="retry" size="mini" @click="loadHistory">重试</button>
      </view>
      <empty-state
        v-else-if="!history.length"
        icon="馈"
        title="暂无反馈记录"
        hint="提交后可在这里查看处理进度与回复"
      />
      <view v-else class="history-list">
        <view v-for="item in history" :key="item.feedbackId" class="card history-card">
          <view class="history-head">
            <text class="history-type">{{ typeLabel(item.feedbackType) }}</text>
            <text class="history-status" :class="statusClass(item.status)">{{
              statusLabel(item.status)
            }}</text>
          </view>
          <text class="history-content">{{ item.content }}</text>
          <text class="history-time">{{ formatTime(item.createdAt) }}</text>
          <view v-if="item.reply" class="reply-box">
            <text class="reply-label">运营回复</text>
            <text class="reply-body">{{ item.reply }}</text>
            <text v-if="item.handledAt" class="reply-time">{{ formatTime(item.handledAt) }}</text>
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app';
import { ref } from 'vue';
import { dictOptions, displayLabel } from '@aicabinet/shared-dict';
import { consumerApi, ensureConsumerAuth } from '@/utils/consumer-api';
import { eventInputValue, readDomFieldValue, readDomTextarea } from '@/utils/form-bind';
import { formatDateTimeMinute } from '@aicabinet/shared-uni/format';
import type { UserFeedbackDto } from '@aicabinet/shared-types';

const tab = ref<'submit' | 'mine'>('submit');
const feedbackType = ref('SUGGESTION');
const content = ref('');
const contactInfo = ref('');
const deviceId = ref('');
const submitting = ref(false);
const err = ref('');
const historyLoading = ref(false);
const historyError = ref('');
const history = ref<UserFeedbackDto[]>([]);

const typeOptions = dictOptions('feedback_type');

onLoad((opts) => {
  const fromQuery = (opts?.deviceId as string) || '';
  const fromStorage = uni.getStorageSync('last_device_id') || '';
  deviceId.value = fromQuery || fromStorage || '';
  if (String(opts?.tab || '') === 'mine') {
    tab.value = 'mine';
  }
});

onShow(() => {
  if (tab.value === 'mine') void loadHistory();
});

function typeLabel(t?: string) {
  return displayLabel('feedback_type', t, '反馈');
}

function statusLabel(s?: string) {
  return displayLabel('feedback_status', s, '处理中');
}

function statusClass(s?: string) {
  if (s === 'HANDLED' || s === 'REPLIED') return 'ok';
  if (s === 'CLOSED') return 'muted';
  return 'pending';
}

function formatTime(t?: string) {
  return formatDateTimeMinute(t, '');
}

async function onMineTab() {
  tab.value = 'mine';
  await loadHistory();
}

async function loadHistory() {
  historyLoading.value = true;
  historyError.value = '';
  try {
    if (!(await ensureConsumerAuth())) {
      uni.navigateTo({
        url:
          '/pages/login/login?redirect=' + encodeURIComponent('/pages/feedback/feedback?tab=mine')
      });
      return;
    }
    history.value = (await consumerApi.listMyFeedback()) || [];
  } catch (e) {
    history.value = [];
    historyError.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    historyLoading.value = false;
  }
}

async function onSubmit() {
  let text = content.value.trim();
  if (!text) text = readDomTextarea();
  if (text.length < 4) {
    err.value = '请至少填写 4 个字';
    return;
  }
  if (submitting.value) return;
  if (!(await ensureConsumerAuth())) {
    uni.navigateTo({
      url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/feedback/feedback')
    });
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
    content.value = '';
    tab.value = 'mine';
    await loadHistory();
  } catch (e) {
    err.value = e instanceof Error ? e.message : '提交失败';
  } finally {
    submitting.value = false;
  }
}
</script>

<style scoped>
.page {
  min-height: 100vh;
  background: #f7f7f7;
  padding: 24rpx;
  box-sizing: border-box;
}
.tabs {
  display: flex;
  gap: 12rpx;
  margin-bottom: 16rpx;
}
.tab {
  padding: 12rpx 28rpx;
  border-radius: 999rpx;
  background: #fff;
  color: #666;
  font-size: 26rpx;
}
.tab.active {
  background: #059669;
  color: #fff;
  font-weight: 650;
}
.hero {
  padding: 8rpx 8rpx 24rpx;
}
.hero-title {
  font-size: 40rpx;
  font-weight: 700;
  color: #191919;
  display: block;
}
.hero-sub {
  font-size: 26rpx;
  color: #888;
  margin-top: 8rpx;
  display: block;
}
.card {
  background: #fff;
  border-radius: 24rpx;
  padding: 32rpx;
}
.field-label {
  font-size: 26rpx;
  color: #666;
  display: block;
  margin-bottom: 12rpx;
  margin-top: 8rpx;
}
.input {
  background: #f7f7f7;
  border-radius: 12rpx;
  padding: 22rpx 24rpx;
  font-size: 30rpx;
  margin-bottom: 16rpx;
}
.issue-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-bottom: 20rpx;
}
.issue-chip {
  padding: 14rpx 24rpx;
  border-radius: 32rpx;
  background: #f7f7f7;
  font-size: 26rpx;
  color: #666;
}
.issue-chip.active {
  background: #e8f8ef;
  color: #07c160;
  font-weight: 600;
}
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
.counter {
  display: block;
  text-align: right;
  font-size: 22rpx;
  color: #bbb;
  margin-bottom: 12rpx;
}
.btn-primary {
  margin: 16rpx 0 0;
  background: linear-gradient(135deg, #059669, #0d9488);
  color: #fff;
  border-radius: 44rpx;
  font-size: 32rpx;
  font-weight: 600;
  line-height: 88rpx;
  height: 88rpx;
  border: none;
  box-shadow: 0 8rpx 24rpx rgba(5, 150, 105, 0.22);
}
.btn-primary[disabled] {
  opacity: 0.55;
}
.btn-primary::after {
  border: none;
}
.btn-hover {
  opacity: 0.85;
}
.err {
  color: #fa5151;
  font-size: 26rpx;
  display: block;
  margin-top: 16rpx;
  text-align: center;
}
.state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16rpx;
  padding: 64rpx 24rpx;
  color: #888;
  font-size: 28rpx;
}
.retry {
  background: #059669;
  color: #fff;
  border: none;
}
.history-list {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}
.history-card {
  padding: 28rpx;
}
.history-head {
  display: flex;
  align-items: center;
  gap: 12rpx;
  margin-bottom: 12rpx;
}
.history-type {
  font-size: 28rpx;
  font-weight: 650;
  color: #191919;
}
.history-status {
  margin-left: auto;
  font-size: 22rpx;
  padding: 6rpx 12rpx;
  border-radius: 999rpx;
  font-weight: 600;
}
.history-status.ok {
  color: #047857;
  background: #d1fae5;
}
.history-status.pending {
  color: #b45309;
  background: #fef3c7;
}
.history-status.muted {
  color: #64748b;
  background: #f1f5f9;
}
.history-content {
  display: block;
  font-size: 28rpx;
  color: #334155;
  line-height: 1.55;
}
.history-time {
  display: block;
  margin-top: 10rpx;
  font-size: 22rpx;
  color: #94a3b8;
}
.reply-box {
  margin-top: 16rpx;
  padding: 20rpx;
  border-radius: 16rpx;
  background: #f0fdf4;
}
.reply-label {
  display: block;
  font-size: 22rpx;
  color: #059669;
  font-weight: 650;
  margin-bottom: 8rpx;
}
.reply-body {
  display: block;
  font-size: 26rpx;
  color: #334155;
  line-height: 1.55;
}
.reply-time {
  display: block;
  margin-top: 8rpx;
  font-size: 22rpx;
  color: #94a3b8;
}
</style>
