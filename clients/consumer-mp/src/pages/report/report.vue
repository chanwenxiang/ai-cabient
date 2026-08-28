<template>
  <view class="page">
    <app-nav-bar title="故障报修" />
    <view class="page-body">
      <view class="hero">
        <text class="hero-title">故障报修</text>
        <text class="hero-sub">柜机异常？提交后运营会尽快处理</text>
      </view>

      <view class="card">
        <text class="field-label">柜机编号</text>
        <input class="input" :value="deviceId" placeholder="例如 CAB-001" @input="onDeviceInput" />

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
        <text class="counter">{{ description.length }}/200</text>

        <text class="field-label">联系电话（选填）</text>
        <input
          class="input"
          type="number"
          maxlength="11"
          :value="contactPhone"
          placeholder="方便运营回访，默认用登录手机号"
          @input="contactPhone = eventInputValue($event)"
        />
      </view>

      <view class="tip-card">
        <text class="tip-title">处理说明</text>
        <text class="tip-body"
          >提交后运营通常在营业时间内跟进；紧急情况可拨打帮助中心客服热线。报修编号将随反馈一并留存。</text
        >
        <text class="tip-link" @click="goHelp">查看帮助中心 ›</text>
      </view>

      <!-- 提交区独立：用 view+role=button，保证 H5 a11y 树可点（OBS-005） -->
      <view class="submit-bar">
        <view
          class="btn-primary"
          role="button"
          tabindex="0"
          aria-label="提交报修"
          :aria-disabled="submitting ? 'true' : 'false'"
          hover-class="btn-hover"
          @tap.stop="onSubmit"
          @click.stop="onSubmit"
        >
          <text class="btn-primary-text">{{ submitting ? '提交中…' : '提交报修' }}</text>
        </view>
        <text v-if="err" class="err">{{ err }}</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app';
import { computed, ref } from 'vue';
import { dictOptions } from '@aicabinet/shared-dict';
import { consumerApi, ensureConsumerAuth } from '@/utils/consumer-api';
import { eventInputValue, readDomFieldValue } from '@/utils/form-bind';

const deviceId = ref('');
const issueType = ref('DOOR_OPEN');
const description = ref('');
const contactPhone = ref('');
const submitting = ref(false);
const err = ref('');

/** 选项来自字典 device_fault_issue（运营可在后台字典管理调整） */
const issueOptions = computed(() => dictOptions('device_fault_issue'));

onLoad((opts) => {
  const fromQuery = (opts?.deviceId as string) || '';
  const fromStorage = uni.getStorageSync('last_device_id') || '';
  deviceId.value = fromQuery || fromStorage || '';
});

function onDeviceInput(e: unknown) {
  deviceId.value = eventInputValue(e);
}

function goHelp() {
  uni.navigateTo({ url: '/pages/help/help' });
}

function onSubmit() {
  let id = deviceId.value.trim().toUpperCase();
  if (!id) id = readDomFieldValue('input').toUpperCase();
  deviceId.value = id;
  if (!id) {
    err.value = '请输入柜机编号';
    return;
  }
  if (!/^[A-Z0-9][A-Z0-9-]{2,31}$/.test(id)) {
    err.value = '柜机编号格式不正确，例如 CAB-001';
    return;
  }
  const phone = contactPhone.value.trim();
  if (phone && !/^1\d{10}$/.test(phone)) {
    err.value = '联系电话需为11位手机号';
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
      const descParts = [description.value.trim()];
      if (phone) descParts.push(`联系电话：${phone}`);
      const res = await consumerApi.reportDeviceFault(id, {
        issueType: issueType.value,
        description: descParts.filter(Boolean).join('\n') || undefined
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
.page {
  min-height: 100%;
  background: #ffffff;
  padding: 0;
  box-sizing: border-box;
}
.page-body {
  padding: 24rpx 24rpx calc(48rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
.hero {
  padding: 16rpx 8rpx 24rpx;
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
  background: #f5f7f8;
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
  background: #f5f7f8;
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
  min-height: 160rpx;
  background: #f5f7f8;
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
  color: #94a3b8;
  margin-bottom: 20rpx;
}
.tip-card {
  margin-top: 8rpx;
  padding: 24rpx;
  border-radius: 20rpx;
  background: #f8fafc;
}
.tip-title {
  display: block;
  font-size: 26rpx;
  font-weight: 650;
  color: #1e293b;
}
.tip-body {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  color: #64748b;
  line-height: 1.5;
}
.tip-link {
  display: block;
  margin-top: 12rpx;
  font-size: 24rpx;
  color: #059669;
  font-weight: 600;
}
.submit-bar {
  margin-top: 24rpx;
  padding: 0 4rpx;
  position: relative;
  z-index: 2;
}
.btn-primary {
  margin: 0;
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  background: linear-gradient(135deg, #047857, #059669);
  color: #fff;
  border-radius: 44rpx;
  font-size: 32rpx;
  font-weight: 600;
  line-height: 1.2;
  min-height: 88rpx;
  height: 88rpx;
  border: none;
  box-shadow: 0 8rpx 24rpx rgba(5, 150, 105, 0.22);
  position: relative;
  z-index: 3;
  pointer-events: auto;
  box-sizing: border-box;
}
.btn-primary-text {
  color: #fff;
  font-size: 32rpx;
  font-weight: 600;
  pointer-events: none;
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
</style>
