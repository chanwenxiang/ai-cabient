<template>
  <view class="page">
    <app-nav-bar title="生成备货单" home-url="/pages/home/home" />
    <view class="page-body">
      <input
        v-model="keyword"
        class="filter-input"
        type="text"
        placeholder="按柜机名称或编码筛选"
        aria-label="按柜机名称或编码筛选柜机"
      />

      <view v-if="loading" class="empty-inline">{{ loadingLabel('预览') }}</view>
      <view v-else-if="!groups.length" class="card empty-card">
        <text class="empty-title">暂无可备货柜机</text>
        <text class="empty-hint">名下柜机里没有已上架商品</text>
      </view>
      <view v-else-if="!visibleGroups.length" class="card empty-card">
        <text class="empty-title">没有匹配的柜机</text>
        <text class="empty-hint">换个名称或编码试试</text>
      </view>

      <view v-for="group in visibleGroups" :key="group.deviceId" class="card">
        <view class="group-head" role="button" @click="toggleGroup(group)">
          <view class="check" :class="{ on: group.checked }">{{ group.checked ? '✓' : '' }}</view>
          <view class="group-copy">
            <text class="group-name">{{ group.deviceName }}</text>
            <text class="group-meta"
              >{{ group.deviceId }} · 待补 {{ groupLineCount(group) }}/{{ group.items.length }} 种 ·
              合计 {{ groupUnits(group) }}{{ groupUnit(group) }}</text
            >
          </view>
        </view>
        <view class="group-totals">
          <view class="total-cell">
            <text class="total-label">当前库存合计</text>
            <text class="total-val">{{ groupCurrentTotal(group) }}{{ groupUnit(group) }}</text>
          </view>
          <view class="total-cell">
            <text class="total-label">配送后库存合计</text>
            <text class="total-val is-after"
              >{{ groupAfterTotal(group) }}{{ groupUnit(group) }}</text
            >
          </view>
        </view>
        <view v-for="item in group.items" :key="item.skuId" class="line-row">
          <view class="line-copy">
            <text class="sku-name">{{ item.skuName }}</text>
            <text class="sku-suggest"
              >建议 {{ item.suggestQty }}{{ item.unit
              }}<text v-if="item.suggestQty === 0"> · 已满</text
              ><text v-else-if="item.qty !== item.suggestQty"> · 已下调</text></text
            >
            <text class="sku-stock"
              >当前库存 {{ item.currentQty }}{{ item.unit }} · 容量
              {{ item.capacity > 0 ? `${item.capacity}${item.unit}` : '未设'
              }}<text v-if="item.inTransitQty > 0">
                · 已领未上柜 {{ item.inTransitQty }}{{ item.unit }}</text
              ></text
            >
          </view>
          <!-- 确认量可下调不可上调：上限就是「补满」，再多放不进柜 -->
          <view class="stepper">
            <view
              role="button"
              class="step-btn"
              :class="{ 'is-off': item.qty <= 0 }"
              aria-label="减少确认量"
              @click="setQty(item, item.qty - 1)"
              >−</view
            >
            <text class="qty-val">{{ item.qtyText }}</text>
            <view
              role="button"
              class="step-btn"
              :class="{ 'is-off': item.qty >= item.suggestQty }"
              aria-label="增加确认量"
              @click="setQty(item, item.qty + 1)"
              >+</view
            >
          </view>
        </view>
      </view>

      <app-button
        v-if="visibleGroups.length"
        :disabled="submitting || !submittableCount || !canRequest"
        :loading="submitting"
        :label="submitLabel"
        @click="submit"
      />
      <text v-if="visibleGroups.length && !canRequest" class="err"
        >当前账号无补货发起权限，仅可查看预览</text
      >
    </view>
  </view>
</template>

<script setup lang="ts">
/**
 * 手动生成备货单（A 方案，2026-10-29）：不依赖阈值、可提前生成。
 * 预览 = GET /merchant/replenishment/prep-preview?includeZero=true（柜内全量在架 SKU，
 * 按柜分组，建议量 0 的已满商品也列出）；确认 = 逐柜 POST /merchant/replenishment/self-initiate
 * （复用自主补货链路：出库扣账 + 补货任务 + clientRequestNo 幂等），不加备货单实体。
 * 与「备货单」页分工（2026-10-30 用户拍板）：那页只列缺口行，这页看整柜并按名称/编码筛选。
 */
import { computed, ref } from 'vue';
import { onLoad, onPullDownRefresh } from '@dcloudio/uni-app';
import { showError, showSuccess, showLongError } from '@/utils/notify';
import { loadingLabel } from '@aicabinet/shared-uni/ui-copy';
import AppButton from '@aicabinet/shared-uni/components/app-button.vue';
import AppNavBar from '@aicabinet/shared-uni/components/app-nav-bar.vue';
import { hasPerm, isMerchantLoggedIn, merchantApi } from '@/utils/merchant-api';
import { useMerchantMe } from '@/composables/useMerchantMe';
import { isRetriableMpTransportError } from '@aicabinet/shared-uni/request';
import {
  nextPrepRequestNo,
  prepSubmitFingerprint,
  takePrepRequestNo,
  type PrepSubmitOutcome
} from '@/utils/self-replenish-submit';
import { matchDeviceKeyword } from '@/utils/prep-filter';
import type { OpenApiMerchantPrepPreviewGroupDto } from '@aicabinet/shared-types';

/** 页内视图行：勾选态 + 数值量 + 件装换算文案（口径同 CB-061「X 件 + Y 支」） */
interface PrepGroupView {
  deviceId: string;
  deviceName: string;
  checked: boolean;
  items: {
    skuId: string;
    skuName: string;
    unit: string;
    /** 确认量（支）——提交 body 的 qty 取它；默认=建议量，可下调 */
    qty: number;
    /** 建议量（支）= 上限 − 实际 − 已领未上柜，同时也是上调上限 */
    suggestQty: number;
    /** 件装数：把「支」换算成「X 件 + Y 支」展示（CB-061） */
    packQty: number;
    qtyText: string;
    currentQty: number;
    capacity: number;
    inTransitQty: number;
  }[];
}

const { me } = useMerchantMe();
const loading = ref(false);
const submitting = ref(false);
/**
 * 逐柜幂等键（V401）——必须活在页面级：建在 submit() 里会随调用返回即销毁，
 * 部分失败重提时已成功的柜会换新键，后端查重失效 ⇒ 重复建任务 + 重复扣分仓库存。
 * 存续语义见 `nextPrepRequestNo`（成功/传输未知保留，业务失败清）。
 */
const prepRequestNos = new Map<string, string>();
/**
 * 上次**成功**提交的明细指纹（P0-3 残留，2026-11-10）：后端按 key 回放不核对明细，
 * 改量后重提若复用旧键会把旧任务当结果返回、改量被静默吞掉；换新键又会重复建任务。
 * 故指纹不同 ⇒ 显式拦截并指引去「补货任务」页改量。仅成功时写入（传输未知不写）。
 */
const prepSuccessFingerprints = new Map<string, string>();
const groups = ref<PrepGroupView[]>([]);
const keyword = ref('');
const canRequest = computed(() => hasPerm(me.value, 'merchant:replenishment:request'));
/** 柜名或编码模糊匹配（大小写不敏感）；无关键字时就是全部柜机 */
const visibleGroups = computed(() =>
  groups.value.filter((g) => matchDeviceKeyword(keyword.value, g))
);
/** 勾选且确认量不全为 0 的柜——只有这些会真正建任务（筛选出来的才算数） */
const submittableCount = computed(
  () => visibleGroups.value.filter((g) => g.checked && groupLineCount(g) > 0).length
);
const submitLabel = computed(() =>
  submitting.value ? '生成中…' : `确认生成（${submittableCount.value} 台柜）`
);

onLoad(() => {
  if (!isMerchantLoggedIn()) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  void loadPreview();
});

onPullDownRefresh(() => loadPreview().finally(() => uni.stopPullDownRefresh()));

/** 件装换算：「X 件 + Y 支」，整件或拆零为 0 时省略对应段（与首页 prepPackText 同规则） */
function packText(qty: number, packQty: number, unit: string): string {
  const pack = Math.max(1, packQty || 1);
  const cases = Math.floor(qty / pack);
  const remain = qty % pack;
  const parts: string[] = [];
  if (cases > 0) parts.push(`${cases} 件`);
  if (remain > 0) parts.push(`${remain} ${unit}`);
  return parts.length ? parts.join(' + ') : `0 ${unit}`;
}

function toView(rows: OpenApiMerchantPrepPreviewGroupDto[]): PrepGroupView[] {
  return rows.map((group) => {
    const items = (group.items ?? []).map((item) => {
      const unit = String(item.unit || '支');
      const packQty = Number(item.packQty) || 1;
      const suggestQty = Number(item.suggestQty) || 0;
      return {
        skuId: String(item.skuId),
        skuName: String(item.skuName || item.skuId),
        unit,
        qty: suggestQty,
        suggestQty,
        packQty,
        qtyText: packText(suggestQty, packQty, unit),
        currentQty: Number(item.currentQty) || 0,
        capacity: Number(item.capacity) || 0,
        inTransitQty: Number(item.inTransitQty) || 0
      };
    });
    return {
      deviceId: String(group.deviceId),
      deviceName: String(group.deviceName || group.deviceId),
      // 全柜已满的柜默认不勾（勾了也提交不出任务行）
      checked: items.some((item) => item.qty > 0),
      items
    };
  });
}

/** 确认量钳在 [0, 建议量]：建议量就是「补满」，再多放不进柜 */
function setQty(item: PrepGroupView['items'][number], next: number) {
  const clamped = Math.max(0, Math.min(Math.round(next) || 0, item.suggestQty));
  item.qty = clamped;
  item.qtyText = packText(clamped, item.packQty, item.unit);
}

function groupUnit(group: PrepGroupView): string {
  return group.items[0]?.unit || '支';
}

function groupUnits(group: PrepGroupView): number {
  return group.items.reduce((sum, item) => sum + item.qty, 0);
}

function groupCurrentTotal(group: PrepGroupView): number {
  return group.items.reduce((sum, item) => sum + item.currentQty, 0);
}

/** 配送后库存合计 = 全柜账面 + 全柜已领未上柜 + 本次确认量 */
function groupAfterTotal(group: PrepGroupView): number {
  return group.items.reduce((sum, item) => sum + item.currentQty + item.inTransitQty + item.qty, 0);
}

/** 勾选的柜机里至少有一件确认量 > 0 才能提交 */
function groupLineCount(group: PrepGroupView): number {
  return group.items.filter((item) => item.qty > 0).length;
}

async function loadPreview() {
  loading.value = true;
  try {
    // 手动生成要看柜内全量在架商品（建议量 0 也列），备货单页才只列缺货行
    const rows = await merchantApi.replenishmentPrepPreview(true);
    groups.value = toView(rows || []);
  } catch (e) {
    groups.value = [];
    showError(e instanceof Error ? e.message : '预览加载失败');
  } finally {
    loading.value = false;
  }
}

function toggleGroup(group: PrepGroupView) {
  group.checked = !group.checked;
}

/**
 * 逐柜确认建任务（顺序提交；单柜失败不中断其余柜）。
 * 幂等：每柜一次一键（V401 契约）——键存页面级，成功/传输未知都保留，重试复用同键幂等返回原任务。
 */
async function submit() {
  if (submitting.value) return;
  const targets = visibleGroups.value.filter((g) => g.checked && groupLineCount(g) > 0);
  if (!targets.length) {
    showError('请至少勾选一台柜机，且确认量不能全为 0');
    return;
  }
  submitting.value = true;
  const okTasks: string[] = [];
  const failures: string[] = [];
  for (const group of targets) {
    // 下调到 0 的行不建任务行（整柜全 0 的柜已在上面剔除）
    const lines = group.items
      .filter((item) => item.qty > 0)
      .map((item) => ({ skuId: item.skuId, qty: item.qty }));
    // P0-3 残留：该柜已成功建任务且本次改量 ⇒ 显式拦截（复用旧键会静默回放旧数量，
    // 换新键会重复建任务），指引去「补货任务」页调整
    const fingerprint = prepSubmitFingerprint(lines);
    const prevSuccess = prepSuccessFingerprints.get(group.deviceId);
    if (prevSuccess !== undefined && prevSuccess !== fingerprint) {
      failures.push(
        `${group.deviceName}：已按上次提交数量生成任务，本次改量未提交，请到「补货任务」页调整数量`
      );
      continue;
    }
    // 每柜独立幂等键（存在页面级 store）：设备间互不重放；同柜重复提交=同一意图
    const clientRequestNo = takePrepRequestNo(prepRequestNos, group.deviceId);
    let outcome: PrepSubmitOutcome = 'business_error';
    try {
      const body = {
        deviceId: group.deviceId,
        lines,
        clientRequestNo
      };
      const task = await merchantApi.selfInitiateReplenishment(body);
      outcome = 'success';
      prepSuccessFingerprints.set(group.deviceId, fingerprint);
      okTasks.push(`${group.deviceName}（任务 ${task.taskId}）`);
    } catch (e) {
      if (isRetriableMpTransportError(e)) outcome = 'transport_error';
      failures.push(`${group.deviceName}：${e instanceof Error ? e.message : '提交失败'}`);
    } finally {
      // 成功也保留：部分失败时用户会重提，已成功的柜必须复用同键由后端回放，否则重复建任务
      if (!nextPrepRequestNo(clientRequestNo, outcome)) {
        prepRequestNos.delete(group.deviceId);
        prepSuccessFingerprints.delete(group.deviceId);
      }
    }
  }
  submitting.value = false;
  if (okTasks.length && !failures.length) {
    showSuccess(`已生成 ${okTasks.length} 台柜的备货任务`);
    setTimeout(
      () => uni.navigateBack({ fail: () => uni.switchTab({ url: '/pages/home/home' }) }),
      900
    );
    return;
  }
  if (okTasks.length) {
    showLongError(
      `已生成：${okTasks.join('、')}。失败：${failures.join('；')}`,
      '部分柜机生成失败'
    );
    return;
  }
  showLongError(failures.join('；') || '生成失败，请稍后重试', '生成失败');
}
</script>

<style scoped src="./prep-generate.page.css"></style>
