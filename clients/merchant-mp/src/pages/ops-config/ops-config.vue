<script setup lang="ts">
import { computed, ref } from 'vue';
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
const merchantOptions = ref<{ merchantId: string; merchantName?: string }[]>([]);
const cfg = ref<OpsConfig | null>(null);

const merchantNames = computed(() =>
  merchantOptions.value.map((m) => m.merchantName || m.merchantId)
);
const merchantIndex = computed(() =>
  Math.max(
    0,
    merchantOptions.value.findIndex((m) => m.merchantId === merchantId.value)
  )
);

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
    merchantOptions.value = list;
    // CB-070：/merchant/me 本身要 merchant:portal:access——缺权限/超管误登时根本拿不到资料，
    // 不能把「资料未到」报成「账号未绑定商户」。
    if (!me.value) {
      loadError.value = '账号资料未加载，请稍后重试';
      cfg.value = null;
      return;
    }
    if (!list.length) {
      loadError.value = '账号未绑定商户';
      cfg.value = null;
      return;
    }
    // 多商户（2026-10-23 第四轮审计）：此前恒取 merchants[0]，第二个商户的
    // 缺货阈值/拍照开关不可见不可改。保留当前选择，仅当选择不在列表里才回落第一个。
    if (!list.some((m) => m.merchantId === merchantId.value)) {
      merchantId.value = list[0].merchantId;
      merchantName.value = list[0].merchantName || '';
    }
    await loadConfig();
  } catch (e) {
    cfg.value = null;
    loadError.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

async function loadConfig() {
  cfg.value = await merchantApi.opsConfig(merchantId.value);
}

function onMerchantChange(e: unknown) {
  const idx = Number((e as { detail?: { value?: number } })?.detail?.value ?? -1);
  const picked = merchantOptions.value[idx];
  if (!picked || picked.merchantId === merchantId.value) return;
  merchantId.value = picked.merchantId;
  merchantName.value = picked.merchantName || '';
  // 🔴 同步清空旧商户配置（2026-10-23 第五轮审计）：异步加载完成前 save() 会把
  // 旧商户的 photoReplenish 等开关写进新商户（切换窗口写穿）。cfg=null 时
  // 模板显示加载态、save() 与 onFlagChange 均有 !cfg 守卫，窗口内无法提交。
  cfg.value = null;
  loading.value = true;
  loadError.value = '';
  loadConfig()
    .catch((err) => {
      cfg.value = null;
      loadError.value = err instanceof Error ? err.message : '加载失败';
    })
    .finally(() => {
      loading.value = false;
    });
}

function onFlagChange(key: 'photoReplenish' | 'photoStocktake' | 'useStockingList', e: unknown) {
  if (!cfg.value) return;
  const on = !!(e as { detail?: { value?: boolean } }).detail?.value;
  cfg.value = { ...cfg.value, [key]: on };
}

async function save() {
  if (!cfg.value) return;
  saving.value = true;
  try {
    // 缺货阈值不再由商户端设置（2026-10-28 拍板「按后台阈值为准统一」）：
    // 显式剔除该字段，服务端收到 0 会保留库中现值，不会被商户侧保存动作冲成默认 50。
    const payload = { ...cfg.value };
    delete payload.stockoutThresholdPct;
    cfg.value = await merchantApi.saveOpsConfig(payload, merchantId.value);
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
        <text class="intro-desc">拍照与是否按补货单执行。缺货阈值、签到定位由平台统一设置。</text>
      </view>

      <view v-if="loading && !cfg" class="card empty">正在加载配置…</view>
      <error-state v-else-if="loadError && !cfg" :title="loadError" @retry="load" />
      <view v-else-if="cfg" class="card">
        <view v-if="merchantOptions.length > 1" class="status-row">
          <text class="field-label">当前商户</text>
          <picker :value="merchantIndex" :range="merchantNames" @change="onMerchantChange">
            <text class="status-val picker-val">{{ merchantName || '未命名商户' }} ▾</text>
          </picker>
        </view>
        <view v-else class="status-row">
          <text class="field-label">当前商户</text>
          <text class="status-val">{{ merchantName || '未命名商户' }}</text>
        </view>

        <view class="row">
          <view class="row-copy">
            <text class="row-title">缺货提醒阈值</text>
            <text class="row-desc">由平台在柜机「库存台账」按商品统一设置，商户端不可修改</text>
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
