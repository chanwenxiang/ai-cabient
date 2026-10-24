<template>
  <view class="page">
    <app-nav-bar title="税号" home-url="/pages/home/home" />
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
                maxlength="18"
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
                maxlength="11"
                placeholder="联系电话"
              />
            </view>
          </view>
          <view v-if="canEditProfile" class="actions">
            <app-button
              variant="primary"
              block
              :loading="taxSaving || taxLoading"
              :disabled="taxSaving || taxLoading"
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
import { showConfirm, showError, showSuccess } from '@/utils/notify';
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
/** 切换商户后档案加载中的门闩：加载完成前禁止保存（第五轮审计 M-2 跨商户写穿窗口） */
const taxLoading = ref(false);
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

/** 请求序号守卫（2026-10-23 审计 H1）：多商户快速切换时，迟到的旧商户响应不得灌进表单——
 * 否则「picker 显示 B、表单是 A」，用户一保存就把 A 的开票抬头写进商户 B（跨商户写穿） */
let loadTaxSeq = 0;

/** 表单「已加载基线」快照：onShow 重拉前比对，用户有未保存编辑时不被服务端值覆盖
 * （2026-10-23 第四轮审计：编辑中切后台再回前台，编辑被静默冲掉）。
 * 🔴 初值必须取当前表单的序列化：若为 ''，首屏 taxForm=emptyTaxProfileForm()
 * 就与 '' 不等 → taxDirty 永真 → onShow 永远跳过加载，税号页空表单（第五轮自查回归）。 */
const taxBaseline = ref(JSON.stringify(taxForm.value));

function captureTaxBaseline() {
  taxBaseline.value = JSON.stringify(taxForm.value);
}

const taxDirty = computed(() => JSON.stringify(taxForm.value) !== taxBaseline.value);

async function loadTaxProfile() {
  const mid = taxMerchantId.value;
  if (!mid) return;
  const seq = ++loadTaxSeq;
  try {
    const p = await merchantApi.getTaxProfile(mid);
    if (seq !== loadTaxSeq) return;
    taxForm.value = mapTaxProfileToForm(p);
    captureTaxBaseline();
  } catch {
    if (seq !== loadTaxSeq) return;
    taxForm.value = emptyTaxProfileForm();
    // 失败分支也要重置基线：否则空表单与初始 '' 不等，dirty 永真，
    // 之后 onShow 再也不重拉，失败态会被钉死在本页。
    captureTaxBaseline();
  }
}

function onMerchantPick(e: { detail?: { value?: string | number } }) {
  const next = merchantOptions.value[Number(e.detail?.value)]?.id || '';
  if (!next || next === taxMerchantId.value) return;
  if (taxDirty.value) {
    // 切商户=放弃未保存编辑（2026-10-23 第五轮审计 L-1）：显式确认，不静默冲掉
    void showConfirm({
      title: '放弃未保存的修改？',
      content: '切换开票商户后，当前未保存的编辑将丢失',
      confirmText: '放弃并切换'
    }).then((ok) => {
      if (ok) switchTaxMerchant(next);
    });
    return;
  }
  switchTaxMerchant(next);
}

function switchTaxMerchant(next: string) {
  taxMerchantId.value = next;
  // 🔴 同步清空表单+置加载中（第五轮审计 M-2）：档案异步到达前 saveTax 会把旧商户
  // 抬头写进新商户（跨商户写穿窗口）。空表单+重置基线=不 dirty；taxLoading 门闩
  // 拦保存，按钮同步置灰，加载完成才恢复。
  taxForm.value = emptyTaxProfileForm();
  captureTaxBaseline();
  taxLoading.value = true;
  loadTaxProfile().finally(() => {
    taxLoading.value = false;
  });
}

async function saveTax() {
  const mid = taxMerchantId.value;
  if (!mid || taxSaving.value || taxLoading.value) return;
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
    // 保存成功=服务端已认可当前表单，重置基线避免残留 dirty 拦之后的重拉
    captureTaxBaseline();
  } catch (e) {
    showError(e instanceof Error ? e.message : '保存失败');
  } finally {
    taxSaving.value = false;
  }
}

onShow(async () => {
  if (!(await ensureLogin())) return;
  if (!taxMerchantId.value) taxMerchantId.value = merchantOptions.value[0]?.id || '';
  // 有未保存编辑时不重拉（2026-10-23 第四轮审计）：切后台回前台不该冲掉用户输入；
  // 表单干净时才重拉，保证他人/他端修改能同步进来。切商户走 onMerchantPick 显式重拉。
  if (taxDirty.value) return;
  await loadTaxProfile();
});
</script>

<style scoped src="./tax.page.css"></style>
