<template>
  <view class="page">
    <app-nav-bar title="税号" />
    <view class="page-body">
      <view class="intro">
        <text class="intro-title">开票抬头</text>
        <text class="intro-desc"
          >月结或运营给您开发票时使用。请填公司全称和纳税人识别号；地址、电话选填，方便写进发票备注。</text
        >
      </view>

      <view v-if="merchantOptions.length" class="card">
        <picker
          v-if="merchantOptions.length > 1"
          mode="selector"
          :range="merchantLabels"
          :value="merchantIndex"
          @change="onMerchantPick"
        >
          <view class="merchant-pick">
            <text class="field-label">开票商户</text>
            <text class="merchant-name">{{ currentMerchantName }}</text>
          </view>
        </picker>
        <view v-else class="merchant-pick static">
          <text class="field-label">开票商户</text>
          <text class="merchant-name">{{ currentMerchantName }}</text>
        </view>

        <view v-if="!taxMerchantId" class="empty">暂无绑定商户</view>
        <view v-else class="tax-form">
          <view class="field">
            <text class="field-label">公司名称</text>
            <view class="field-box">
              <input v-model="taxForm.companyName" class="tax-input" placeholder="与营业执照一致" />
            </view>
          </view>
          <view class="field">
            <text class="field-label">纳税人识别号</text>
            <view class="field-box">
              <input
                v-model="taxForm.taxNo"
                class="tax-input"
                placeholder="18 位统一社会信用代码"
              />
            </view>
          </view>
          <view class="field">
            <text class="field-label">地址（选填）</text>
            <view class="field-box">
              <input v-model="taxForm.address" class="tax-input" placeholder="注册或收票地址" />
            </view>
          </view>
          <view class="field">
            <text class="field-label">电话（选填）</text>
            <view class="field-box">
              <input
                v-model="taxForm.phone"
                class="tax-input"
                type="number"
                placeholder="联系电话"
              />
            </view>
          </view>
          <view v-if="canEditProfile" class="actions">
            <app-button
              variant="primary"
              block
              :loading="taxSaving"
              label="保存"
              @click="saveTax"
            />
          </view>
          <text v-else class="hint">当前账号不能改税号，请联系管理员</text>
        </view>
      </view>
      <view v-else class="card empty">暂无绑定商户</view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { onShow } from '@dcloudio/uni-app';
import { showError, showSuccess } from '@/utils/notify';
import { hasPerm, isMerchantLoggedIn, merchantApi } from '@/utils/merchant-api';
import { seedMerchantMeDisplayCache, useMerchantMe } from '@/composables/useMerchantMe';
import {
  buildSaveTaxProfileBody,
  emptyTaxProfileForm,
  mapTaxProfileToForm,
  taxProfileFormError
} from '@/utils/business-tax';

const { me, refresh: refreshMe } = useMerchantMe();
const taxMerchantId = ref('');
const taxSaving = ref(false);
const taxForm = ref(emptyTaxProfileForm());

const merchantOptions = computed(() =>
  (me.value?.merchants || [])
    .filter((m) => m.merchantId)
    .map((m) => ({
      id: String(m.merchantId),
      name: m.merchantName || String(m.merchantId)
    }))
);
const merchantLabels = computed(() => merchantOptions.value.map((m) => m.name));
const merchantIndex = computed(() => {
  const i = merchantOptions.value.findIndex((m) => m.id === taxMerchantId.value);
  return i < 0 ? 0 : i;
});
const currentMerchantName = computed(() => {
  const hit = merchantOptions.value.find((m) => m.id === taxMerchantId.value);
  return hit?.name || '请选择商户';
});
const canEditProfile = computed(() => hasPerm(me.value, 'merchant:profile:edit'));

async function ensureLogin() {
  if (!isMerchantLoggedIn()) {
    uni.reLaunch({ url: '/pages/login/login' });
    return false;
  }
  try {
    await refreshMe();
  } catch {
    if (!isMerchantLoggedIn()) return false;
    seedMerchantMeDisplayCache(me);
  }
  return true;
}

async function loadTaxProfile() {
  const mid = taxMerchantId.value;
  if (!mid) return;
  try {
    const p = await merchantApi.getTaxProfile(mid);
    taxForm.value = mapTaxProfileToForm(p);
  } catch {
    taxForm.value = emptyTaxProfileForm();
  }
}

function onMerchantPick(e: { detail?: { value?: string | number } }) {
  const next = merchantOptions.value[Number(e.detail?.value)]?.id || '';
  if (!next || next === taxMerchantId.value) return;
  taxMerchantId.value = next;
  void loadTaxProfile();
}

async function saveTax() {
  const mid = taxMerchantId.value;
  if (!mid || taxSaving.value) return;
  if (!canEditProfile.value) {
    showError('无资料编辑权限');
    return;
  }
  const formErr = taxProfileFormError(taxForm.value);
  if (formErr) {
    showError(formErr);
    return;
  }
  taxSaving.value = true;
  try {
    await merchantApi.saveTaxProfile(buildSaveTaxProfileBody(mid, taxForm.value));
    showSuccess('已保存');
  } catch (e) {
    showError(e instanceof Error ? e.message : '保存失败');
  } finally {
    taxSaving.value = false;
  }
}

onShow(async () => {
  if (!(await ensureLogin())) return;
  if (!taxMerchantId.value) taxMerchantId.value = merchantOptions.value[0]?.id || '';
  await loadTaxProfile();
});
</script>

<style scoped src="./tax.page.css"></style>
