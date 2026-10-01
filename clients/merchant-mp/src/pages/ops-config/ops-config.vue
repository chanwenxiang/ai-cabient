<script setup lang="ts">
import { ref } from 'vue';
import { onShow } from '@dcloudio/uni-app';
import { showError, showSuccess } from '@/utils/notify';
import { merchantApi } from '@/utils/merchant-api';
import { useMerchantMe } from '@/composables/useMerchantMe';

type OpsConfig = import('@aicabinet/shared-types').OpenApiMerchantOpsConfigDto;

const { me, refresh: refreshMe } = useMerchantMe();
const loading = ref(false);
const saving = ref(false);
const merchantId = ref('');
const merchantName = ref('');
const cfg = ref<OpsConfig | null>(null);

const thresholdPct = ref(50);

onShow(async () => {
  loading.value = true;
  try {
    await refreshMe();
    const list = (me.value?.merchants || []) as { merchantId: string; merchantName?: string }[];
    const first = list[0];
    if (!first) {
      showError('账号未绑定商户');
      return;
    }
    merchantId.value = first.merchantId;
    merchantName.value = first.merchantName || '';
    cfg.value = await merchantApi.opsConfig(merchantId.value);
    thresholdPct.value = cfg.value?.stockoutThresholdPct ?? 50;
  } catch (e) {
    showError(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
});

async function save() {
  if (!cfg.value) return;
  const pct = Number(thresholdPct.value);
  if (!Number.isFinite(pct) || pct < 1 || pct > 100) {
    showError('缺货提醒阈值需在 1-100 之间');
    return;
  }
  saving.value = true;
  try {
    cfg.value = await merchantApi.saveOpsConfig(
      { ...cfg.value, stockoutThresholdPct: pct },
      merchantId.value
    );
    showSuccess('已保存');
  } catch (e) {
    showError(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <view class="page">
    <app-nav-bar title="补货配置" />
    <view v-if="merchantName" class="merchant-line">商户：{{ merchantName }}</view>

    <view v-if="loading && !cfg" class="empty">加载中…</view>
    <template v-else-if="cfg">
      <view class="group">
        <view class="row">
          <view class="row-copy">
            <text class="row-title">缺货提醒阈值</text>
            <text class="row-desc"
              >货道库存低于容量的 {{ thresholdPct }}% 时记为缺货，进入补货建议</text
            >
          </view>
          <input
            class="pct-input"
            type="number"
            :value="String(thresholdPct)"
            maxlength="3"
            @input="
              (e: any) => {
                const n = Number(e.detail.value);
                thresholdPct = Number.isFinite(n) ? Math.max(1, Math.min(100, n)) : 50;
              }
            "
          />
          <text class="pct-sign">%</text>
        </view>

        <view class="row">
          <view class="row-copy">
            <text class="row-title">补货后拍照</text>
            <text class="row-desc">完成补货前需拍摄现场照片留存</text>
          </view>
          <switch
            :checked="cfg.photoReplenish"
            color="#0f766e"
            @change="(e: any) => (cfg!.photoReplenish = e.detail.value)"
          />
        </view>

        <view class="row">
          <view class="row-copy">
            <text class="row-title">盘点拍照</text>
            <text class="row-desc">提交盘点结果时需拍照核对</text>
          </view>
          <switch
            :checked="cfg.photoStocktake"
            color="#0f766e"
            @change="(e: any) => (cfg!.photoStocktake = e.detail.value)"
          />
        </view>

        <view class="row">
          <view class="row-copy">
            <text class="row-title">按补货单补货</text>
            <text class="row-desc">开启后补货须按系统生成的补货单执行</text>
          </view>
          <switch
            :checked="cfg.useStockingList"
            color="#0f766e"
            @change="(e: any) => (cfg!.useStockingList = e.detail.value)"
          />
        </view>
      </view>

      <view class="group tips">
        <text class="tip">签到定位、开门核验要求由平台统一设置；如需调整请联系运营。</text>
      </view>

      <button class="save-btn" :loading="saving" @click="save">保存</button>
    </template>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx;
  box-sizing: border-box;
  background: var(--page-bg, #f6f8f7);
}
.merchant-line {
  margin-bottom: 16rpx;
  font-size: 13px;
  color: var(--text-subtle);
}
.group {
  background: var(--card-bg, #fff);
  border-radius: 18rpx;
  padding: 8rpx 20rpx;
  margin-bottom: 20rpx;
}
.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
  padding: 22rpx 0;
  border-bottom: 1rpx solid var(--color-border, #eef2f1);
}
.row:last-child {
  border-bottom: none;
}
.row-copy {
  flex: 1;
  min-width: 0;
}
.row-title {
  display: block;
  font-size: 15px;
  font-weight: 600;
}
.row-desc {
  display: block;
  margin-top: 4rpx;
  font-size: 12px;
  color: var(--text-subtle);
  line-height: 1.4;
}
.pct-input {
  width: 96rpx;
  height: 60rpx;
  border: 1rpx solid var(--color-border, #eef2f1);
  border-radius: 10rpx;
  text-align: center;
  font-size: 15px;
}
.pct-sign {
  font-size: 13px;
  color: var(--text-subtle);
}
.tips {
  padding: 16rpx 20rpx;
}
.tip {
  font-size: 12px;
  color: var(--text-subtle);
  line-height: 1.5;
}
.save-btn {
  margin-top: 8rpx;
  background: linear-gradient(135deg, #0f766e, #14b8a6);
  color: #fff;
  border: none;
  border-radius: 999rpx;
  font-size: 15px;
  font-weight: 600;
  box-shadow: 0 8rpx 20rpx rgba(13, 148, 136, 0.32);
}
.save-btn[disabled] {
  opacity: 0.6;
}
.empty {
  padding: 60rpx 0;
  text-align: center;
  color: var(--text-subtle);
  font-size: 14px;
}
</style>
