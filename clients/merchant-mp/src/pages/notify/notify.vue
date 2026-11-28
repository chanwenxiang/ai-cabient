<template>
  <view class="page">
    <app-nav-bar title="微信提醒" home-url="/pages/home/home" />
    <view class="page-body">
      <view class="intro">
        <text class="intro-title">通知设置</text>
        <text class="intro-desc"
          >服务号接收经营日报和提现结果；小程序订阅仅用于设备与待办提醒。</text
        >
      </view>

      <view class="card">
        <view class="status-row">
          <text class="field-label">小程序微信绑定</text>
          <text class="status-val">{{ bindLabel }}</text>
        </view>
        <text class="section-desc"
          >用于柜机离线、缺货、争议等待办提醒，不影响服务号日报和提现结果。</text
        >
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

      <view v-if="isMpWeixin && officialConfigured" class="card official-card">
        <view class="official-row">
          <view class="official-copy">
            <text class="field-label">服务号通知</text>
            <text class="official-desc"
              >经营日报、提现成功与提现失败统一发送到服务号，点击消息可回到商户小程序。</text
            >
            <view class="official-types">
              <text>经营日报</text>
              <text>提现结果</text>
            </view>
            <text class="official-status">{{
              officialBound ? '已绑定 · 日报/提现已开启' : '未绑定'
            }}</text>
          </view>
          <app-button
            variant="soft"
            :loading="officialBusy"
            :label="officialBound ? '重新绑定' : '去绑定'"
            @click="onOfficialBind"
          />
        </view>
      </view>

      <view v-if="subscribeReady" class="card">
        <text class="field-label">设备与待办提醒</text>
        <text class="section-desc">通过小程序订阅消息发送，可单独开关以下类型。</text>
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
  showSuccess,
  showLongError,
  showSubscribeFeedback,
  subscribeFeedbackText,
  type SubscribeRequestResult
} from '@/utils/notify';
import { isMerchantLoggedIn, merchantApi } from '@/utils/merchant-api';
import { seedMerchantMeDisplayCache, useMerchantMe } from '@/composables/useMerchantMe';
import { loadMerchantFlags, merchantSubscribeTemplateId } from '@/utils/merchant-config';

const { me, refresh: refreshMe } = useMerchantMe();
const notifyBusy = ref(false);
const officialConfigured = ref(false);
const officialBound = ref(false);
const officialBusy = ref(false);
const wxBound = ref(false);
const enabledTypes = ref<string[]>([]);
const alertTypeOptions = MERCHANT_ALERT_TYPES;
const runtimeSubscribeTmplId = ref('');
const subscribeReady = computed(() => hasSubscribeTemplates(subscribeTmplIds()));
/** 编译期平台判定，替代已弃用的 getSystemInfoSync().uniPlatform 运行时探测。 */
let isMpWeixin = false;
// #ifdef MP-WEIXIN
isMpWeixin = true;
// #endif
const bindLabel = computed(() => (wxBound.value ? '已绑定' : '未绑定'));

function subscribeTmplIds(): string[] {
  const id = (runtimeSubscribeTmplId.value || '').trim();
  return id ? [id] : [];
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
  let subscribe: SubscribeRequestResult | null = null;
  try {
    subscribe = await requestMerchantSubscribe(subscribeTmplIds());
    const code = await wxLoginCode();
    const prefs = await merchantApi.notifyWxBind(code);
    wxBound.value = !!prefs.wxBound;
    enabledTypes.value = [...(prefs.enabledAlertTypes || [])];
    if (subscribe.status === 'ok') showSubscribeFeedback(subscribe, '微信提醒已绑定');
    else {
      uni.showModal({
        title: '微信账号已绑定',
        content: subscribeFeedbackText(subscribe),
        showCancel: false,
        confirmText: '我知道了'
      });
    }
  } catch (e) {
    const reason = e instanceof Error ? e.message : '绑定失败';
    if (subscribe) showLongError(`${subscribeFeedbackText(subscribe)}；${reason}`, '绑定失败');
    else showError(reason);
  } finally {
    notifyBusy.value = false;
  }
}

async function onOfficialBind() {
  if (!isMpWeixin || officialBusy.value) return;
  officialBusy.value = true;
  try {
    const ticket = await merchantApi.notifyOfficialAccountBindTicket();
    if (!ticket?.authorizeUrl) {
      showError('服务号授权地址获取失败');
      return;
    }
    uni.navigateTo({
      url: '/pages/notify/official-account-bind?url=' + encodeURIComponent(ticket.authorizeUrl)
    });
  } catch (e) {
    showError(e instanceof Error ? e.message : '服务号绑定失败');
  } finally {
    officialBusy.value = false;
  }
}

async function onSaveSubscribe() {
  notifyBusy.value = true;
  let subscribe: SubscribeRequestResult | null = null;
  try {
    if (isMpWeixin) {
      subscribe = await requestMerchantSubscribe(subscribeTmplIds());
    }
    const prefs = await merchantApi.notifySubscribe(enabledTypes.value);
    enabledTypes.value = [...(prefs.enabledAlertTypes || [])];
    if (!isMpWeixin) {
      showSuccess('偏好已保存（推送请在微信小程序开启）');
    } else if (subscribe?.status === 'ok') {
      showSubscribeFeedback(subscribe, '提醒已开启，偏好已保存');
    } else if (subscribe) {
      uni.showModal({
        title: '偏好已保存',
        content: subscribeFeedbackText(subscribe),
        showCancel: false,
        confirmText: '我知道了'
      });
    } else {
      showSuccess('偏好已保存');
    }
  } catch (e) {
    const reason = e instanceof Error ? e.message : '保存失败';
    if (subscribe) showLongError(`${subscribeFeedbackText(subscribe)}；${reason}`, '保存失败');
    else showError(reason);
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
    const [prefs, official] = await Promise.all([
      merchantApi.notifyPrefs(),
      merchantApi.notifyOfficialAccountStatus()
    ]);
    wxBound.value = !!prefs.wxBound;
    enabledTypes.value = [...(prefs.enabledAlertTypes || [])];
    officialConfigured.value = !!official?.configured;
    officialBound.value = !!official?.bound;
  } catch {
    /* ignore — page still usable */
  }
});
</script>

<style scoped src="./notify.page.css"></style>
