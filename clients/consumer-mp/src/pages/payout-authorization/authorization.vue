<script setup lang="ts">
/**
 * MA-407：微信免确认收款授权页。
 * 用户点「开通免密收款」→ 后端拿 package_info → wx.requestMerchantTransfer 拉起微信授权页。
 * 授权一次长期有效，以后商户提现打款无需逐笔点确认。
 */
import { ref } from 'vue';
import { consumerApi } from '@/utils/consumer-api';

const busy = ref(false);
const message = ref('');
const success = ref(false);

async function onAuthorize() {
  if (busy.value) return;
  busy.value = true;
  message.value = '';
  // #ifdef MP-WEIXIN
  try {
    const res = await consumerApi.requestWechatPayoutAuthorization();
    if (!res.packageInfo || !res.mchId) {
      throw new Error('微信未返回授权参数，请稍后重试');
    }
    // wx 为小程序专有全局；requestMerchantTransfer 官方 JSAPI（4015930512），无 uni 包装
    const wxApi = globalThis as unknown as {
      wx?: {
        canIUse: (name: string) => boolean;
        requestMerchantTransfer: (o: {
          mchId: string;
          appId: string;
          package: string;
          success: (r: { errMsg?: string }) => void;
          fail: (r: { errMsg?: string }) => void;
        }) => void;
        getAccountInfoSync: () => { miniProgram: { appId: string } };
        showModal: (o: Record<string, unknown>) => void;
      };
    };
    const wx = wxApi.wx;
    if (!wx || !wx.canIUse('requestMerchantTransfer')) {
      wx?.showModal({ content: '你的微信版本过低，请更新至最新版微信后重试。', showCancel: false });
      return;
    }
    wx.requestMerchantTransfer({
      mchId: res.mchId,
      appId: wx.getAccountInfoSync().miniProgram.appId,
      package: res.packageInfo,
      success: (r) => {
        // err_msg:ok 仅代表授权页展示成功，不等于用户已同意；授权结果以微信回调为准
        success.value = true;
        message.value = r.errMsg?.includes(':ok')
          ? '已拉起微信授权页，请在微信中完成确认'
          : '已发起授权，请在微信中完成确认';
      },
      fail: (r) => {
        message.value = r.errMsg?.includes(':cancel')
          ? '已取消授权，下次仍可重新开通'
          : '拉起微信授权失败，请稍后重试';
      }
    });
  } catch (e) {
    message.value = e instanceof Error ? e.message : '开通失败，请稍后重试';
  } finally {
    busy.value = false;
  }
  // #endif
  // #ifndef MP-WEIXIN
  message.value = '请在微信中打开本页完成开通';
  busy.value = false;
  // #endif
}
</script>

<template>
  <view class="page-root">
    <app-nav-bar title="开通免密收款" />
    <view class="page-body">
      <view class="intro-card">
        <text class="intro-title">开通后，提现到账更快</text>
        <text class="intro-desc"
          >授权一次后，商户提现时无需每次再到微信点确认，审核通过即打到你的微信零钱。你可以随时在微信支付设置里关闭。</text
        >
      </view>

      <view class="steps">
        <text class="step">1. 点击下方按钮，拉起微信授权页</text>
        <text class="step">2. 在微信中确认开通免密收款</text>
        <text class="step">3. 完成后即可在商户端提现</text>
      </view>

      <app-button
        variant="primary"
        block
        :disabled="busy"
        :loading="busy"
        :label="busy ? '提交中…' : '开通免密收款'"
        @click="onAuthorize"
      />

      <text v-if="message" class="hint" :class="{ ok: success }">{{ message }}</text>
    </view>
  </view>
</template>

<style scoped>
.page-root {
  min-height: 100vh;
  background: var(--bg-page, #f5f6f8);
}
.page-body {
  padding: 32rpx;
}
.intro-card {
  background: var(--bg-card, #fff);
  border-radius: 20rpx;
  padding: 32rpx;
  margin-bottom: 32rpx;
}
.intro-title {
  display: block;
  font-size: 34rpx;
  font-weight: 600;
  color: var(--text-primary, #1a1a1a);
  margin-bottom: 16rpx;
}
.intro-desc {
  display: block;
  font-size: 26rpx;
  line-height: 1.6;
  color: var(--text-secondary, #666);
}
.steps {
  margin-bottom: 40rpx;
}
.step {
  display: block;
  font-size: 28rpx;
  color: var(--text-secondary, #555);
  line-height: 2;
}
.hint {
  display: block;
  margin-top: 24rpx;
  font-size: 26rpx;
  color: var(--text-danger, #d4380d);
  line-height: 1.6;
}
.hint.ok {
  color: var(--text-success, #389e0d);
}
</style>
