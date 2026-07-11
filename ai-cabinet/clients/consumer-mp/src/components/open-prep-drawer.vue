<template>
  <view class="drawer-mask" @click="onCancel">
    <view class="drawer-panel" @click.stop>
      <view class="drawer-handle" />
      <text class="drawer-title">开门前准备</text>
      <text class="drawer-sub">完成以下步骤即可开门取货</text>

      <view class="prep-steps">
        <view class="prep-step" :class="{ done: account?.verified }">
          <view class="prep-dot">{{ account?.verified ? '✓' : '1' }}</view>
          <text>实名</text>
        </view>
        <view class="prep-line" :class="{ done: account?.verified }" />
        <view class="prep-step" :class="{ done: payReady }">
          <view class="prep-dot">{{ payReady ? '✓' : '2' }}</view>
          <text>支付</text>
        </view>
      </view>

      <view v-if="!account?.verified" class="drawer-body">
        <text class="field-label">真实姓名</text>
        <input v-model="realName" class="input" placeholder="与身份证一致" maxlength="32" />
        <text class="field-label">身份证后四位</text>
        <input v-model="idCardLast4" class="input" type="number" maxlength="4" placeholder="0000" />
        <button class="btn-primary" hover-class="btn-hover" :loading="busy" @click="onVerify">
          {{ busy ? '提交中…' : '完成实名认证' }}
        </button>
      </view>

      <view v-else-if="!payReady" class="drawer-body">
        <text class="drawer-desc">灰度运营仅使用测试余额结算。余额不足时请联系现场运营人员发放测试余额。</text>
        <view class="balance-row">
          <text>当前余额</text>
          <text class="balance-val">¥{{ balanceYuan }}</text>
        </view>
        <text class="balance-warning">最低开门余额 ¥5.00，测试余额不代表真实充值资金</text>
      </view>

      <text v-if="err" class="err">{{ err }}</text>
      <text class="cancel-link" @click="onCancel">稍后再说</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type { AccountDto } from '@aicabinet/shared-types';
import { consumerApi } from '@/utils/consumer-api';

const props = defineProps<{
  account: AccountDto | null;
}>();

const emit = defineEmits<{
  done: [];
  cancel: [];
}>();

const account = ref<AccountDto | null>(props.account);
const realName = ref('');
const idCardLast4 = ref('');
const busy = ref(false);
const err = ref('');

watch(
  () => props.account,
  (v) => {
    account.value = v;
  }
);

const balanceYuan = computed(() => ((account.value?.balanceCents || 0) / 100).toFixed(2));
const payReady = computed(
  () => (account.value?.balanceCents || 0) >= 500
);

watch(payReady, (ready) => {
  if (ready && account.value?.verified) {
    emit('done');
  }
});

async function onVerify() {
  const name = realName.value.trim();
  const last4 = idCardLast4.value.trim();
  if (name.length < 2) {
    err.value = '请输入真实姓名';
    return;
  }
  if (!/^\d{4}$/.test(last4)) {
    err.value = '身份证后四位须为 4 位数字';
    return;
  }
  busy.value = true;
  err.value = '';
  try {
    account.value = await consumerApi.verifyIdentity({ realName: name, idCardLast4: last4 });
    if (payReady.value) emit('done');
  } catch (e) {
    err.value = e instanceof Error ? e.message : '认证失败';
  } finally {
    busy.value = false;
  }
}

function onCancel() {
  emit('cancel');
}
</script>

<style scoped>
.drawer-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  z-index: 200;
  display: flex;
  align-items: flex-end;
}
.drawer-panel {
  width: 100%;
  background: #fff;
  border-radius: 24rpx 24rpx 0 0;
  padding: 16rpx 32rpx calc(32rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
.drawer-handle {
  width: 64rpx;
  height: 8rpx;
  background: #e5e5e5;
  border-radius: 4rpx;
  margin: 0 auto 24rpx;
}
.drawer-title {
  font-size: 34rpx;
  font-weight: 700;
  color: #191919;
  display: block;
  text-align: center;
}
.drawer-sub {
  font-size: 26rpx;
  color: #888;
  display: block;
  text-align: center;
  margin-top: 8rpx;
  margin-bottom: 28rpx;
}
.prep-steps {
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 28rpx;
}
.prep-step {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6rpx;
  font-size: 22rpx;
  color: #888;
}
.prep-step.done { color: #07c160; }
.prep-dot {
  width: 48rpx;
  height: 48rpx;
  border-radius: 50%;
  background: #e5e5e5;
  color: #888;
  font-size: 24rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
}
.prep-step.done .prep-dot { background: #07c160; color: #fff; }
.prep-line {
  width: 80rpx;
  height: 4rpx;
  background: #e5e5e5;
  margin: 0 12rpx 20rpx;
}
.prep-line.done { background: #07c160; }
.drawer-body { margin-top: 8rpx; }
.field-label {
  font-size: 26rpx;
  color: #666;
  display: block;
  margin-bottom: 8rpx;
}
.input {
  background: #f7f7f7;
  border-radius: 12rpx;
  padding: 22rpx 24rpx;
  margin-bottom: 20rpx;
  font-size: 30rpx;
}
.drawer-desc {
  font-size: 26rpx;
  color: #888;
  line-height: 1.5;
  display: block;
  margin-bottom: 20rpx;
}
.balance-row {
  display: flex;
  justify-content: space-between;
  padding: 16rpx 0 24rpx;
  font-size: 28rpx;
  color: #666;
}
.balance-val { color: #191919; font-weight: 600; }
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
.hint {
  font-size: 24rpx;
  color: #b2b2b2;
  display: block;
  text-align: center;
  margin-top: 16rpx;
}
.balance-warning { display: block; padding: 20rpx; border-radius: 12rpx; background: #fff7e6; color: #ad6800; font-size: 25rpx; line-height: 1.5; }
.err {
  color: #fa5151;
  font-size: 26rpx;
  display: block;
  text-align: center;
  margin-top: 16rpx;
}
.cancel-link {
  display: block;
  text-align: center;
  color: #888;
  font-size: 28rpx;
  margin-top: 24rpx;
  padding: 12rpx 0;
}
</style>
