<template>
  <view class="page">
    <app-nav-bar title="微信提醒" home-url="/pages/home/home" />
    <view class="page-body">
      <view class="intro">
        <text class="intro-title">订阅推送</text>
        <text class="intro-desc">{{ introDesc }}</text>
      </view>

      <view class="card">
        <view class="status-row">
          <text class="field-label">微信绑定</text>
          <text class="status-val">{{ bindLabel }}</text>
        </view>
        <view v-if="isMpWeixin && subscribeReady" class="actions bind-actions">
          <app-button
            variant="soft"
            block
            :loading="notifyBusy"
            :label="wxBound ? '重新绑定' : '开启提醒'"
            @click="onBindWx"
          />
        </view>
        <text v-else-if="!isMpWeixin" class="hint">仅微信小程序可开启推送</text>
        <text v-else-if="!subscribeReady" class="hint">未配置订阅模板，暂不能推送</text>
      </view>

      <view v-if="subscribeReady" class="card">
        <text class="field-label">提醒类型</text>
        <view class="notify-types">
          <view v-for="t in alertTypeOptions" :key="t.value" class="notify-type">
            <switch
              :checked="enabledTypes.includes(t.value)"
              color="var(--brand)"
              :aria-label="t.label"
              @change="(e) => onToggleType(t.value, switchEnabled(e))"
            />
            <text>{{ t.label }}</text>
          </view>
        </view>
        <view class="actions">
          <app-button
            variant="primary"
            block
            :loading="notifyBusy"
            label="保存"
            @click="onSaveSubscribe"
          />
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { onShow } from '@dcloudio/uni-app';
import {
  hasSubscribeTemplates,
  MERCHANT_ALERT_TYPES,
  requestMerchantSubscribe,
  wxLoginCode,
  showError,
  showSuccess
} from '@/utils/notify';
import { isMerchantLoggedIn, merchantApi } from '@/utils/merchant-api';
import { seedMerchantMeDisplayCache, useMerchantMe } from '@/composables/useMerchantMe';
import { loadMerchantFlags, merchantSubscribeTemplateId } from '@/utils/merchant-config';

const { me, refresh: refreshMe } = useMerchantMe();
const notifyBusy = ref(false);
const wxBound = ref(false);
const enabledTypes = ref<string[]>([]);
const alertTypeOptions = MERCHANT_ALERT_TYPES;
const runtimeSubscribeTmplId = ref('');
const subscribeReady = computed(() =>
  hasSubscribeTemplates(runtimeSubscribeTmplId.value ? [runtimeSubscribeTmplId.value] : [])
);
const isMpWeixin = (() => {
  try {
    const info = uni.getSystemInfoSync() as { uniPlatform?: string };
    return info.uniPlatform === 'mp-weixin';
  } catch {
    return false;
  }
})();
const bindLabel = computed(() => (wxBound.value ? '已绑定' : '未绑定'));
const introDesc = computed(() => {
  if (!isMpWeixin) return '柜机离线、缺货、争议等可发到微信。请在微信小程序中开启。';
  if (!subscribeReady.value) return '当前未配置订阅模板，暂不能向微信推送。';
  return '开启后可接收柜机与订单提醒。勾选要收的类型后保存。';
});

function subscribeTmplIds(): string[] {
  return runtimeSubscribeTmplId.value ? [runtimeSubscribeTmplId.value] : [];
}

function onToggleType(type: string, on: boolean) {
  const set = new Set(enabledTypes.value);
  if (on) set.add(type);
  else set.delete(type);
  enabledTypes.value = [...set];
}

function switchEnabled(e: unknown) {
  const ev = e as { detail?: { value?: boolean } };
  return !!ev?.detail?.value;
}

async function onBindWx() {
  if (!isMpWeixin) {
    showError('请在微信小程序中开启提醒');
    return;
  }
  if (!subscribeReady.value) {
    showError('未配置订阅模板，无法开启推送');
    return;
  }
  notifyBusy.value = true;
  try {
    const sub = await requestMerchantSubscribe(subscribeTmplIds());
    if (sub === 'failed') {
      showError('微信授权未完成，仍可继续绑定账号');
    }
    const code = await wxLoginCode();
    const prefs = await merchantApi.notifyWxBind(code);
    wxBound.value = !!prefs.wxBound;
    enabledTypes.value = [...(prefs.enabledAlertTypes || [])];
    showSuccess('已绑定微信提醒');
  } catch (e) {
    showError(e instanceof Error ? e.message : '绑定失败');
  } finally {
    notifyBusy.value = false;
  }
}

async function onSaveSubscribe() {
  notifyBusy.value = true;
  try {
    if (isMpWeixin && subscribeReady.value) {
      const sub = await requestMerchantSubscribe(subscribeTmplIds());
      if (sub === 'failed') {
        showError('微信授权未完成，偏好仍会保存');
      }
    }
    const prefs = await merchantApi.notifySubscribe(enabledTypes.value);
    enabledTypes.value = [...(prefs.enabledAlertTypes || [])];
    if (!isMpWeixin) {
      showSuccess('偏好已保存（推送请在微信小程序开启）');
    } else {
      showSuccess(subscribeReady.value ? '已保存' : '偏好已保存（未配置推送模板）');
    }
  } catch (e) {
    showError(e instanceof Error ? e.message : '保存失败');
  } finally {
    notifyBusy.value = false;
  }
}

onShow(async () => {
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
  await loadMerchantFlags({ refresh: true });
  runtimeSubscribeTmplId.value = merchantSubscribeTemplateId();
  try {
    const prefs = await merchantApi.notifyPrefs();
    wxBound.value = !!prefs.wxBound;
    enabledTypes.value = [...(prefs.enabledAlertTypes || [])];
  } catch {
    /* ignore — page still usable */
  }
});
</script>

<style scoped src="./notify.page.css"></style>
