<template>
  <view class="page">
    <app-nav-bar title="备货单" home-url="/pages/home/home" />
    <view class="page-body">
      <view class="tabs">
        <view
          role="button"
          aria-label="待确认"
          class="tab"
          :class="{ on: tab === 'pending' }"
          @click="tab = 'pending'"
          >待确认{{ pendingCabinetCount ? `（${pendingCabinetCount}）` : '' }}</view
        >
        <view
          role="button"
          aria-label="已生成"
          class="tab"
          :class="{ on: tab === 'generated' }"
          @click="tab = 'generated'"
          >已生成{{ generatedGroups.length ? `（${generatedGroups.length}）` : '' }}</view
        >
      </view>

      <input
        v-model="keyword"
        class="filter-input"
        type="text"
        placeholder="按柜机名称或编码筛选"
        aria-label="按柜机名称或编码筛选柜机"
      />

      <!-- ── 待确认：柜内在架商品全集 + 确认量可下调（补满口径） ─────────── -->
      <block v-if="tab === 'pending'">
        <view class="summary">
          <view class="summary-copy">
            <text class="summary-title">{{
              visiblePendingGroups.length
                ? `${visiblePendingGroups.length} 台柜机待备货`
                : '暂无待备货柜机'
            }}</text>
            <text class="summary-sub">{{
              pendingSkuCount ? `${pendingSkuCount} 种缺货商品` : '各柜已补满，无需备货'
            }}</text>
          </view>
          <!-- 手动生成：逐行看柜内全量 SKU 的库存明细与上限，单独一页（2026-10-30 用户要求保留） -->
          <view class="summary-tools">
            <text class="tool-link" role="button" aria-label="手动生成" @click="goPrepGenerate"
              >手动生成</text
            >
          </view>
        </view>

        <view v-if="previewLoading" class="empty-inline">{{ loadingLabel('备货单') }}</view>
        <block v-else-if="visiblePendingGroups.length">
          <view v-for="group in visiblePendingGroups" :key="group.deviceId" class="card">
            <view class="group-head" role="button" @click="toggleGroup(group)">
              <view class="check" :class="{ on: group.checked }">{{
                group.checked ? '✓' : ''
              }}</view>
              <view class="group-copy">
                <text class="group-name">{{ group.deviceName }}</text>
                <text class="group-meta"
                  >待补 {{ group.items.length }} 种 · 确认 {{ groupUnits(group)
                  }}{{ groupUnit(group) }}</text
                >
              </view>
            </view>
            <!-- 库存按柜汇总（2026-10-30 用户拍板）：明细行不再逐行重复 -->
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
                  }}<text v-if="item.qty !== item.suggestQty"> · 已下调</text></text
                >
                <text class="sku-stock"
                  >当前库存 {{ item.currentQty }}{{ item.unit }} · 容量
                  {{ item.capacity > 0 ? `${item.capacity}${item.unit}` : '未设' }}</text
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
                <text class="qty-val">{{ item.qty }}{{ item.unit }}</text>
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
            :disabled="submitting || !submittableCount || !canRequest"
            :loading="submitting"
            :label="submitLabel"
            @click="submit"
          />
          <text v-if="!canRequest" class="err">当前账号无补货发起权限，仅可查看</text>
        </block>
        <view v-else class="card empty-card">
          <text class="empty-title">{{
            pendingGroups.length ? '没有匹配的柜机' : '暂无待备货商品'
          }}</text>
          <text class="empty-hint">{{
            pendingGroups.length ? '换个名称或编码试试' : '各柜内商品已补满'
          }}</text>
        </view>
      </block>

      <!-- ── 已生成：进行中补货任务的领货清单（事后反推） ──────────────── -->
      <block v-else>
        <view v-if="generatedLoading" class="empty-inline">{{ loadingLabel('备货单') }}</view>
        <block v-else>
          <view v-if="generatedGroups.length" class="sort-bar">
            <text class="sort-label">生成时间</text>
            <view
              role="button"
              aria-label="最新在前"
              class="sort-btn"
              :class="{ on: generatedSort === 'desc' }"
              @click="generatedSort = 'desc'"
              >最新在前</view
            >
            <view
              role="button"
              aria-label="最早在前"
              class="sort-btn"
              :class="{ on: generatedSort === 'asc' }"
              @click="generatedSort = 'asc'"
              >最早在前</view
            >
          </view>
          <view v-for="group in visibleGeneratedGroups" :key="group.deviceId" class="card">
            <view
              class="group-head"
              role="button"
              :aria-label="`${group.deviceName} ${group.taskCount} 个任务 去补货`"
              @click="goReplenishment(group.deviceId || '')"
            >
              <view class="group-copy">
                <text class="group-name">{{ group.deviceName }}</text>
                <text class="group-meta"
                  >{{ group.deviceId }} · {{ group.taskCount }} 个补货任务</text
                >
                <text v-if="taskTimeText(group)" class="group-time">
                  生成于 {{ taskTimeText(group) }}
                </text>
              </view>
              <text class="group-go app-link-chevron">去补货</text>
            </view>
            <view
              v-for="row in group.items"
              :key="row.skuId"
              class="line-row"
              role="button"
              :aria-label="`备货 ${row.skuName} ${prepPackText(row)}`"
              @click="goReplenishment(group.deviceId || '')"
            >
              <view class="line-copy">
                <text class="sku-name">{{ row.skuName }}</text>
                <text class="sku-meta">
                  仓库库存 {{ row.stockUnits ?? 0 }}{{ row.unit
                  }}{{ row.stockShort ? ' · 库存不足' : '' }}
                </text>
              </view>
              <text class="qty-val">备 {{ prepPackText(row) }}</text>
            </view>
          </view>
          <view v-if="!visibleGeneratedGroups.length" class="card empty-card">
            <text class="empty-title">{{
              generatedGroups.length ? '没有匹配的柜机' : '暂无待备货商品'
            }}</text>
            <text class="empty-hint">{{
              generatedGroups.length ? '换个名称或编码试试' : '切到「待确认」生成备货任务'
            }}</text>
          </view>
        </block>
      </block>
    </view>
  </view>
</template>

<script setup lang="ts">
/**
 * 备货单（CB-072 视图页）：
 * - 待确认：只列有缺口的行（建议量 = 上限 − 当前库存 − 已领未上柜 > 0）；
 *   确认量可下调、不能超过建议量；库存按柜汇总（当前 / 配送后）；
 *   底部「确认生成」逐柜调 self-initiate（幂等键/出库/明细复用既有链路）。
 *   柜内全量在架 SKU 属于「手动生成」页，两页分工（2026-10-30 用户拍板）。
 * - 已生成：进行中补货任务的出库行 × 件装换算 × 分仓库存（事后反推）。
 */
import { computed, ref } from 'vue';
import { onShow, onPullDownRefresh } from '@dcloudio/uni-app';
import { loadingLabel } from '@aicabinet/shared-uni/ui-copy';
import AppButton from '@aicabinet/shared-uni/components/app-button.vue';
import AppNavBar from '@aicabinet/shared-uni/components/app-nav-bar.vue';
import { hasPerm, isMerchantLoggedIn, merchantApi } from '@/utils/merchant-api';
import { useMerchantMe } from '@/composables/useMerchantMe';
import { newSelfReplenishRequestNo } from '@/utils/self-replenish-submit';
import { matchDeviceKeyword } from '@/utils/prep-filter';
import { sortByGeneratedTime, taskTimeMs } from '@/utils/prep-order';
import { formatDateTimeShort } from '@aicabinet/shared-uni/format';
import { prepPackText } from '@/utils/prep-pack';
import { showError, showSuccess, showLongError } from '@/utils/notify';
import type {
  OpenApiMerchantPrepGroupDto,
  OpenApiMerchantPrepPreviewGroupDto
} from '@aicabinet/shared-types';

/** 待确认页内视图行：勾选态 + 确认量；合计按全柜在架商品算（已满行的库存也要计入） */
interface PrepGroupView {
  deviceId: string;
  deviceName: string;
  checked: boolean;
  /** 全柜在架商品的账面合计（含已满、不在下方列表里的 SKU） */
  currentTotal: number;
  /** 全柜在架商品的已领未上柜合计 */
  inTransitTotal: number;
  /** 只含有缺口的行（建议量 > 0） */
  items: {
    skuId: string;
    skuName: string;
    unit: string;
    /** 确认量：默认=建议量，可下调 */
    qty: number;
    /** 建议量 = 上限 − 当前库存 − 已领未上柜，同时也是上调上限 */
    suggestQty: number;
    currentQty: number;
    capacity: number;
    inTransitQty: number;
  }[];
}

const { me } = useMerchantMe();
const tab = ref<'pending' | 'generated'>('pending');
const keyword = ref('');
const generatedSort = ref<'desc' | 'asc'>('desc');

const previewLoading = ref(true);
const generatedLoading = ref(true);
const submitting = ref(false);
const pendingGroups = ref<PrepGroupView[]>([]);
const generatedGroups = ref<OpenApiMerchantPrepGroupDto[]>([]);

const canRequest = computed(() => hasPerm(me.value, 'merchant:replenishment:request'));
/** 筛选只作用于下方列表与提交范围；tab 上的柜数仍是全集 */
const visiblePendingGroups = computed(() =>
  pendingGroups.value.filter((g) => matchDeviceKeyword(keyword.value, g))
);
/** 已生成按本柜最近一张任务的创建时间排序（正/倒序可切），同时间按编码兜底 */
const sortedGeneratedGroups = computed(() =>
  sortByGeneratedTime(generatedGroups.value, generatedSort.value)
);
const visibleGeneratedGroups = computed(() =>
  sortedGeneratedGroups.value.filter((g) => matchDeviceKeyword(keyword.value, g))
);
const pendingSkuCount = computed(() =>
  visiblePendingGroups.value.reduce((sum, g) => sum + g.items.length, 0)
);
/** 有待补商品的柜数（全满的柜不计） */
const pendingCabinetCount = computed(
  () => pendingGroups.value.filter((g) => groupLineCount(g) > 0).length
);
const submittableCount = computed(
  () => visiblePendingGroups.value.filter((g) => g.checked && groupLineCount(g) > 0).length
);
const submitLabel = computed(() =>
  submitting.value ? '生成中…' : `确认生成（${submittableCount.value} 台柜）`
);

onShow(() => {
  if (!isMerchantLoggedIn()) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  void loadAll();
});

onPullDownRefresh(() => loadAll().finally(() => uni.stopPullDownRefresh()));

async function loadAll() {
  await Promise.all([loadPreview(), loadGenerated()]);
}

async function loadPreview() {
  try {
    // 取全量（includeZero=true）再在页内分流：列表只留缺口行，柜级合计要把已满 SKU 也算进去
    const rows = await merchantApi.replenishmentPrepPreview(true);
    pendingGroups.value = toView(rows || []);
  } catch (e) {
    pendingGroups.value = [];
    showError(e instanceof Error ? e.message : '备货单加载失败');
  } finally {
    previewLoading.value = false;
  }
}

async function loadGenerated() {
  try {
    generatedGroups.value = (await merchantApi.replenishmentPrepList()) || [];
  } catch (e) {
    generatedGroups.value = [];
    showError(e instanceof Error ? e.message : '备货单加载失败');
  } finally {
    generatedLoading.value = false;
  }
}

function toView(rows: OpenApiMerchantPrepPreviewGroupDto[]): PrepGroupView[] {
  return (
    rows
      .map((group) => {
        const all = (group.items ?? []).map((item) => {
          const suggestQty = Number(item.suggestQty) || 0;
          return {
            skuId: String(item.skuId),
            skuName: String(item.skuName || item.skuId),
            unit: String(item.unit || '支'),
            qty: suggestQty,
            suggestQty,
            currentQty: Number(item.currentQty) || 0,
            capacity: Number(item.capacity) || 0,
            inTransitQty: Number(item.inTransitQty) || 0
          };
        });
        const items = all.filter((item) => item.suggestQty > 0);
        return {
          deviceId: String(group.deviceId),
          deviceName: String(group.deviceName || group.deviceId),
          currentTotal: all.reduce((sum, item) => sum + item.currentQty, 0),
          inTransitTotal: all.reduce((sum, item) => sum + item.inTransitQty, 0),
          checked: items.length > 0,
          items
        };
      })
      // 全柜已满的柜在备货单里没有可备的行，不展示
      .filter((group) => group.items.length > 0)
  );
}

/** 确认量钳在 [0, 建议量]：建议量就是「补满」，再多放不进柜 */
function setQty(item: PrepGroupView['items'][number], next: number) {
  item.qty = Math.max(0, Math.min(Math.round(next) || 0, item.suggestQty));
}

/** 勾选的柜机里至少有一件确认量 > 0 才能提交 */
function groupLineCount(group: PrepGroupView): number {
  return group.items.filter((item) => item.qty > 0).length;
}

function groupUnits(group: PrepGroupView): number {
  return group.items.reduce((sum, item) => sum + item.qty, 0);
}

function groupCurrentTotal(group: PrepGroupView): number {
  return group.currentTotal;
}

/** 配送后库存合计 = 全柜账面 + 全柜已领未上柜 + 本次确认量 */
function groupAfterTotal(group: PrepGroupView): number {
  return group.currentTotal + group.inTransitTotal + groupUnits(group);
}

function groupUnit(group: PrepGroupView): string {
  return group.items[0]?.unit || '支';
}

function toggleGroup(group: PrepGroupView) {
  group.checked = !group.checked;
}

function taskTimeText(group: OpenApiMerchantPrepGroupDto): string {
  return taskTimeMs(group) ? formatDateTimeShort(group.lastTaskAt, '') : '';
}

function goReplenishment(deviceId: string) {
  uni.navigateTo({
    url: `/pages/replenishment/replenishment?deviceId=${encodeURIComponent(deviceId)}`
  });
}

/** 手动生成：柜内全量 SKU 逐行明细（当前库存/配送后/上限）+ 勾选确认 */
function goPrepGenerate() {
  uni.navigateTo({ url: '/pages/prep-generate/prep-generate' });
}

/**
 * 逐柜确认建任务（顺序提交；单柜失败不中断其余柜）。
 * 幂等：每柜一次一键（V401 契约）——传输类错误保留该柜键，重试复用同键幂等返回原任务。
 */
async function submit() {
  if (submitting.value) return;
  const targets = visiblePendingGroups.value.filter((g) => g.checked && groupLineCount(g) > 0);
  if (!targets.length) {
    showError('请至少勾选一台柜机，且确认量不能全为 0');
    return;
  }
  submitting.value = true;
  const requestNos = new Map<string, string>();
  const okTasks: string[] = [];
  const failures: string[] = [];
  for (const group of targets) {
    try {
      const body = {
        deviceId: group.deviceId,
        // 下调到 0 的行不建任务行（整柜全 0 的柜已在上面剔除）
        lines: group.items
          .filter((item) => item.qty > 0)
          .map((item) => ({ skuId: item.skuId, qty: item.qty })),
        clientRequestNo: requestNos.get(group.deviceId) || newSelfReplenishRequestNo()
      };
      requestNos.set(group.deviceId, body.clientRequestNo);
      const task = await merchantApi.selfInitiateReplenishment(body);
      okTasks.push(`${group.deviceName}（任务 ${task.taskId}）`);
      requestNos.delete(group.deviceId);
    } catch (e) {
      failures.push(`${group.deviceName}：${e instanceof Error ? e.message : '提交失败'}`);
    }
  }
  submitting.value = false;
  if (okTasks.length && !failures.length) {
    showSuccess(`已生成 ${okTasks.length} 台柜的备货任务`);
    tab.value = 'generated';
    await loadGenerated();
    return;
  }
  if (okTasks.length) {
    showLongError(
      `已生成：${okTasks.join('、')}。失败：${failures.join('；')}`,
      '部分柜机生成失败'
    );
    await loadPreview();
    await loadGenerated();
    return;
  }
  showLongError(failures.join('；') || '生成失败，请稍后重试', '生成失败');
}
</script>

<style scoped src="./prep-list.page.css"></style>
