<template>
  <view class="page">
    <app-nav-bar title="隐私政策" />
    <view class="page-body">
      <view class="card">
        <text class="updated">更新日期：2026-09-13 · 商户端</text>
        <text class="p"
          >本政策说明智能柜商户端（含 H5 / 小程序）如何收集与使用账号、设备运营与结算相关信息，用于经营工具、补货协作与客服支持。</text
        >
        <text class="h">我们收集的信息</text>
        <text class="p"
          >登录手机号与身份凭证、设备与库存操作记录、提现与结算相关信息，以及为保障安全所必需的日志。</text
        >
        <text class="h">使用目的</text>
        <text class="p">提供商户经营功能、排查异常、履行合同与合规义务；未经同意不向无关第三方出售个人信息。</text>
        <text class="h">您的权利</text>
        <text class="p">您可通过客服申请查阅、更正或注销账号相关信息；注销后我们将按法规要求删除或匿名化处理。</text>
        <text class="p tip">继续使用本服务即表示您已知悉上述说明。完整条款以运营方公示文本为准。</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts"></script>

<style scoped>
.page-body {
  padding: 24rpx 24rpx calc(48rpx + env(safe-area-inset-bottom));
}
.card {
  background: var(--card-bg, #fff);
  border-radius: var(--card-radius, 22rpx);
  padding: 28rpx;
  border: 1rpx solid var(--card-border, var(--color-border));
}
.updated {
  display: block;
  font-size: var(--font-size-sm);
  color: var(--text-subtle);
  margin-bottom: 20rpx;
}
.h {
  display: block;
  margin-top: 24rpx;
  font-weight: 700;
  color: var(--text-primary);
}
.p {
  display: block;
  margin-top: 12rpx;
  font-size: var(--font-size-body);
  line-height: 1.55;
  color: var(--text-muted);
}
.tip {
  margin-top: 28rpx;
  color: var(--text-subtle);
}
</style>
