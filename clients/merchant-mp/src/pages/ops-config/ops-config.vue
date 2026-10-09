<script setup lang="ts">
import { ref } from 'vue';
import { onShow } from '@dcloudio/uni-app';
import { showError, showSuccess } from '@/utils/notify';
import { isMerchantLoggedIn, merchantApi } from '@/utils/merchant-api';
import { seedMerchantMeDisplayCache, useMerchantMe } from '@/composables/useMerchantMe';

type OpsConfig = import('@aicabinet/shared-types').OpenApiMerchantOpsConfigDto;

const { me, refresh: refreshMe } = useMerchantMe();
const loading = ref(false);
const saving = ref(false);
const loadError = ref('');
const merchantId = ref('');
const merchantName = ref('');
const cfg = ref<OpsConfig | null>(null);
const thresholdPct = ref(50);

onShow(async () => {
  await load();
});

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    if (!isMerchantLoggedIn()) {
      uni.reLaunch({ url: '/pages/login/login' });
      return;
    }
    try {
      await refreshMe();
    } catch {
      if (!isMerchantLoggedIn()) return;
      seedMerchantMeDisplayCache(me);
    }
    const list = (me.value?.merchants || []) as { merchantId: string; merchantName?: string }[];
    const first = list[0];
    if (!first) {
      loadError.value = '账号未绑定商户';
      cfg.value = null;
      return;
    }
    merchantId.value = first.merchantId;
    merchantName.value = first.merchantName || '';
    cfg.value = await merchantApi.opsConfig(merchantId.value);
    thresholdPct.value = cfg.value?.stockoutThresholdPct ?? 50;
  } catch (e) {
    cfg.value = null;
    loadError.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

function onPctInput(e: unknown) {
  const n = Number((e as { detail?: { value?: string } })?.detail?.value);
  thresholdPct.value = Number.isFinite(n) ? Math.max(1, Math.min(100, n)) : 50;
}

function onFlagChange(key: 'photoReplenish' | 'photoStocktake' | 'useStockingList', e: unknown) {
  if (!cfg.value) return;
  const on = !!(e as { detail?: { value?: boolean } }).detail?.value;
  cfg.value = { ...cfg.value, [key]: on };
}

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
    <app-nav-bar title="补货配置" home-url="/pages/home/home" />
    <view class="page-body">
      <view class="intro">
        <text class="intro-title">现场补货规则</text>
        <text class="intro-desc">缺货提醒、拍照和是否按补货单执行。签到定位由平台统一设置。</text>
      </view>

      <view v-if="loading && !cfg" class="card empty">正在加载配置…</view>
      <error-state v-else-if="loadError && !cfg" :title="loadError" @retry="load" />
      <view v-else-if="cfg" class="card">
        <view class="status-row">
          <text class="field-label">当前商户</text>
          <text class="status-val">{{ merchantName || '未命名商户' }}</text>
        </view>

        <view class="row">
          <view class="row-copy">
            <text class="row-title">缺货提醒阈值</text>
            <text class="row-desc">货道库存低于容量的 {{ thresholdPct }}% 时记为缺货</text>
          </view>
          <view class="pct-wrap">
            <input
              class="pct-input"
              type="number"
              :value="String(thresholdPct)"
              maxlength="3"
              @input="onPctInput"
            />
            <text class="pct-sign">%</text>
          </view>
        </view>
        <view class="row">
          <view class="row-copy">
            <text class="row-title">补货后拍照</text>
            <text class="row-desc">完成补货前需拍摄现场照片</text>
          </view>
          <switch
            :checked="cfg.photoReplenish"
            color="var(--brand)"
            @change="(e) => onFlagChange('photoReplenish', e)"
          />
        </view>
        <view class="row">
          <view class="row-copy">
            <text class="row-title">盘点拍照</text>
            <text class="row-desc">提交盘点结果时需拍照核对</text>
          </view>
          <switch
            :checked="cfg.photoStocktake"
            color="var(--brand)"
            @change="(e) => onFlagChange('photoStocktake', e)"
          />
        </view>
        <view class="row">
          <view class="row-copy">
            <text class="row-title">按补货单补货</text>
            <text class="row-desc">开启后须按系统补货单执行</text>
          </view>
          <switch
            :checked="cfg.useStockingList"
            color="var(--brand)"
            @change="(e) => onFlagChange('useStockingList', e)"
          />
        </view>
        <view class="actions">
          <app-button variant="primary" block :loading="saving" label="保存" @click="save" />
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped src="./ops-config.page.css"></style>
