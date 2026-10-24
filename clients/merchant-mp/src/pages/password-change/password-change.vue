<script setup lang="ts">
/** 修改密码（CB-050 扩展）：商户端自助改密，携带当前密码；成功后跳回「我的」。 */
import { ref } from 'vue';
import { showError, showSuccess } from '@/utils/notify';
import { isMerchantLoggedIn, merchantApi } from '@/utils/merchant-api';

const oldPassword = ref('');
const newPassword = ref('');
const confirmPassword = ref('');
const saving = ref(false);

if (!isMerchantLoggedIn()) {
  uni.reLaunch({ url: '/pages/login/login' });
}

async function submit() {
  if (!oldPassword.value) {
    showError('请输入当前密码');
    return;
  }
  if (newPassword.value.length < 6 || newPassword.value.length > 64) {
    showError('新密码长度须为 6-64 位');
    return;
  }
  if (newPassword.value !== confirmPassword.value) {
    showError('两次输入的新密码不一致');
    return;
  }
  saving.value = true;
  try {
    await merchantApi.changePassword(oldPassword.value, newPassword.value);
    showSuccess('密码已修改');
    setTimeout(() => uni.navigateBack(), 800);
  } catch (e) {
    showError(e instanceof Error ? e.message : '修改失败');
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <view>
    <app-nav-bar title="修改密码" home-url="/pages/mine/mine" />
    <view class="page-body">
      <view class="card">
        <view class="field">
          <text class="label">当前密码</text>
          <input v-model="oldPassword" class="input" password placeholder="请输入当前密码" />
        </view>
        <view class="field">
          <text class="label">新密码</text>
          <input v-model="newPassword" class="input" password placeholder="6-64 位" />
        </view>
        <view class="field">
          <text class="label">确认新密码</text>
          <input v-model="confirmPassword" class="input" password placeholder="再次输入新密码" />
        </view>
        <app-button :loading="saving" :label="saving ? '提交中…' : '确认修改'" @click="submit" />
        <text class="hint">修改成功后下次登录请使用新密码</text>
      </view>
    </view>
  </view>
</template>

<style scoped>
.page-body {
  padding: 24rpx var(--page-gutter, 24rpx) calc(48rpx + env(safe-area-inset-bottom));
}
.card {
  background: #fff;
  border-radius: 16rpx;
  padding: 24rpx;
  display: flex;
  flex-direction: column;
  gap: 20rpx;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}
.label {
  font-size: 26rpx;
  color: #606266;
}
.input {
  border: 1rpx solid #dcdfe6;
  border-radius: 12rpx;
  padding: 16rpx 20rpx;
  font-size: 28rpx;
}
.hint {
  font-size: 24rpx;
  color: #909399;
  text-align: center;
}
</style>
