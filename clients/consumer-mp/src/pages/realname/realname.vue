<template>
  <view class="page-root">
    <app-nav-bar title="实名认证" />
    <view class="page-body">
      <!--
        2026-10-20：恢复独立实名页（V326 删掉的是「消费者开门流程里的实名一步」，
        不是实名能力本身）。实名唯一用途：商户/线长**提现打款合规**
        （WithdrawEligibilityService 资质门禁）；开门购物不需要实名。
        入口：「我的」页「待实名」标签。
      -->
      <view v-if="verified" class="card done-card">
        <text class="done-icon">✓</text>
        <text class="done-title">已完成实名</text>
        <text class="done-desc">提现打款资质已就绪</text>
      </view>

      <view v-else class="card">
        <text class="card-title">实名认证</text>
        <text class="card-desc"
          >实名用于平台向合作方（商户/线长）打款时的合规校验；开门购物无需实名。
          信息仅用于权威库核验，不用于其它用途。</text
        >
        <text class="field-label">姓名</text>
        <input
          v-model="realName"
          class="input"
          type="text"
          maxlength="32"
          placeholder="请输入本人姓名"
          placeholder-class="input-placeholder"
        />
        <text class="field-label">身份证号</text>
        <input
          v-model="idCard"
          class="input"
          type="idcard"
          maxlength="18"
          placeholder="18 位身份证号（末位可为 X）"
          placeholder-class="input-placeholder"
        />
        <app-button :loading="submitting" label="提交实名" @click="onSubmit" />
        <text v-if="err" class="err">{{ err }}</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app';
import { showSuccess } from '@/utils/notify';
import { ref } from 'vue';
import type { AccountDto } from '@aicabinet/shared-types';
import { consumerApi, ensureConsumerAuth } from '@/utils/consumer-api';

const account = ref<AccountDto | null>(null);
const realName = ref('');
const idCard = ref('');
const submitting = ref(false);
const err = ref('');

const verified = ref(false);

onLoad(async () => {
  const ok = await ensureConsumerAuth();
  if (!ok) {
    uni.navigateTo({
      url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/realname/realname')
    });
    return;
  }
  try {
    account.value = await consumerApi.account();
    verified.value = !!account.value?.verified;
  } catch (e) {
    err.value = e instanceof Error ? e.message : '加载账户失败';
  }
});

/** 18 位身份证（末位 X/x）前端先拦一道，省一次注定失败的往返。 */
function idCardFormatOk(v: string) {
  return /^\d{17}[\dXx]$/.test(v.trim());
}

async function onSubmit() {
  if (submitting.value) return;
  const name = realName.value.trim();
  const id = idCard.value.trim().toUpperCase();
  if (name.length < 2) {
    err.value = '请输入本人姓名';
    return;
  }
  if (!idCardFormatOk(id)) {
    err.value = '请输入 18 位身份证号（末位可为 X）';
    return;
  }
  submitting.value = true;
  err.value = '';
  try {
    // ⚠️ PII：身份证号只在请求体里，不写日志、不落本地存储。
    await consumerApi.verifyIdentity({ realName: name, idCard: id });
    account.value = await consumerApi.account();
    verified.value = !!account.value?.verified;
    if (verified.value) {
      showSuccess('实名完成');
      setTimeout(() => uni.navigateBack({ delta: 1 }), 800);
    } else {
      // 后端返回 200 但未标记（mock 环境语义：只记姓名不置位）
      err.value = '实名未生效，请稍后重试或联系客服';
    }
  } catch (e) {
    err.value = e instanceof Error ? e.message : '实名核验失败';
  } finally {
    submitting.value = false;
  }
}
</script>

<style scoped>
.page-root {
  min-height: 100%;
  background: var(--page-bg, #ededed);
  padding: 0;
  box-sizing: border-box;
}
.page-body {
  padding: 24rpx var(--page-gutter, 24rpx) calc(48rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
.card {
  background: var(--card-bg, #ffffff);
  border-radius: var(--radius-card);
  padding: 32rpx;
  margin: 0;
}
.card-title {
  font-size: var(--font-size-xl);
  font-weight: 600;
  color: var(--color-text-primary);
  display: block;
}
.card-desc {
  font-size: var(--font-size-body);
  color: var(--text-subtle, #888);
  margin: 12rpx 0 28rpx;
  display: block;
  line-height: 1.5;
}
.field-label {
  display: block;
  font-size: var(--font-size-body);
  color: var(--text-muted, #666);
  margin-bottom: 12rpx;
  margin-top: 8rpx;
}
.input {
  width: 100%;
  min-height: 88rpx;
  height: 88rpx;
  line-height: 1.4;
  background: var(--page-bg, #ededed);
  border: 1rpx solid var(--card-border, #e8eeeb);
  border-radius: var(--radius-panel);
  padding: 0 var(--page-gutter, 24rpx);
  margin-bottom: 16rpx;
  font-size: var(--font-size-lg);
  color: var(--color-text-primary);
  box-sizing: border-box;
}
.input-placeholder {
  color: var(--text-subtle, #aaa);
}
.err {
  color: var(--color-danger);
  font-size: var(--font-size-body);
  margin-top: 16rpx;
  display: block;
}
.done-card {
  text-align: center;
  padding: 48rpx 32rpx;
}
.done-icon {
  font-size: 88rpx;
  display: block;
  margin-bottom: 16rpx;
  color: var(--brand);
}
.done-title {
  font-size: var(--font-size-h3);
  font-weight: 600;
  color: var(--color-text-primary);
  display: block;
}
.done-desc {
  font-size: var(--font-size-body);
  color: var(--text-subtle, #888);
  margin-top: 12rpx;
  display: block;
}
</style>
