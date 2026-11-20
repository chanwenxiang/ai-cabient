<template>
  <view class="page-root">
    <app-nav-bar title="实名认证" home-url="/pages/mine/mine" />
    <view class="page-body">
      <!--
        2026-11-20：实名采集入口从消费者小程序归位到商户端。
        用途只有提现打款合规（WithdrawEligibilityService），与消费者开门购物无关（V326/CB-014）。
        提交走与 C 端同源的 /api/v2/account/verify，写的是当前登录用户的 user.verified；
        姓名 + 完整证件号只发权威库二要素核验，本地不落证件号。
      -->
      <view v-if="verified" class="card done-card">
        <text class="done-icon">✓</text>
        <text class="done-title">已完成实名</text>
        <text class="done-desc">提现打款资质已就绪</text>
      </view>

      <view v-else class="card">
        <text class="card-title">实名认证</text>
        <text class="card-desc"
          >实名用于平台向合作方（商户/线长）打款时的合规校验；购物无需实名。姓名与证件号仅提交权威库核验，平台不存储证件号。</text
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
import { ref } from 'vue';
import { showSuccess } from '@/utils/notify';
import { merchantApi, handleUnauthorized, isMerchantLoggedIn } from '@/utils/merchant-api';

const verified = ref(false);
const realName = ref('');
const idCard = ref('');
const submitting = ref(false);
const err = ref('');

onLoad(async () => {
  if (!isMerchantLoggedIn()) {
    handleUnauthorized();
    return;
  }
  try {
    // 运营号段（userId >= 1 亿）走此接口会 400：他们不参与商户提现，软失败即可。
    const account = await merchantApi.account();
    verified.value = !!account?.verified;
  } catch (e) {
    err.value = e instanceof Error ? e.message : '加载实名状态失败';
  }
});

/** 18 位身份证（末位 X/x）前端先拦一道，省一次注定失败的计费核验请求。 */
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
    // PII：证件号只进请求体，不写日志、不落本地存储。
    const account = await merchantApi.verifyIdentity({ realName: name, idCard: id });
    verified.value = !!account?.verified;
    if (verified.value) {
      showSuccess('实名完成');
      setTimeout(() => uni.navigateBack({ delta: 1 }), 800);
    } else {
      // mock 环境：只记姓名不置位（资金出口不能因为「假通过」失守）。
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
  background: var(--page-bg);
  box-sizing: border-box;
}
.page-body {
  padding: 24rpx var(--page-gutter) calc(48rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
.card {
  background: var(--card-bg);
  border-radius: var(--radius-card);
  padding: 32rpx;
  border: 1rpx solid var(--card-border);
}
.card-title {
  font-size: var(--font-size-xl);
  font-weight: 600;
  color: var(--color-text-primary);
  display: block;
}
.card-desc {
  font-size: var(--font-size-body);
  color: var(--text-muted);
  margin: 12rpx 0 28rpx;
  display: block;
  line-height: 1.5;
}
.field-label {
  display: block;
  font-size: var(--font-size-body);
  color: var(--text-muted);
  margin-bottom: 12rpx;
  margin-top: 8rpx;
}
.input {
  width: 100%;
  min-height: 88rpx;
  height: 88rpx;
  line-height: 1.4;
  background: var(--page-bg);
  border: 1rpx solid var(--card-border);
  border-radius: var(--radius-card);
  padding: 0 var(--page-gutter);
  margin-bottom: 16rpx;
  font-size: var(--font-size-lg);
  color: var(--color-text-primary);
  box-sizing: border-box;
}
.input-placeholder {
  color: var(--text-muted);
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
  margin-bottom: 12rpx;
  color: var(--brand);
}
.done-title {
  font-size: var(--font-size-xl);
  font-weight: 600;
  color: var(--color-text-primary);
  display: block;
}
.done-desc {
  font-size: var(--font-size-body);
  color: var(--text-muted);
  margin-top: 12rpx;
  display: block;
}
</style>
