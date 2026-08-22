<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">争议审核</span>
            <span class="hint"
              >识别争议可按低置信 / 模拟识别 / 重力错配分拣；同屏对照录像改 SKU
              后一键落账或免单</span
            >
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-hasPermi="['ops:dispute:export']" @click="onExport">{{
            exportButtonLabel
          }}</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load(false)">刷新</el-button>
        </div>
      </div>
    </template>

    <el-tabs v-model="categoryTab" class="status-tabs" @tab-change="onCategoryTab">
      <el-tab-pane label="全部类型" name="ALL" />
      <el-tab-pane label="识别争议" name="RECOGNITION" />
    </el-tabs>

    <el-radio-group
      v-if="categoryTab === 'RECOGNITION'"
      v-model="reviewCodeTab"
      size="small"
      class="review-code-tabs"
      @change="onReviewCodeTab"
    >
      <el-radio-button value="ALL">全部识别</el-radio-button>
      <el-radio-button value="LOW_CONF">低置信</el-radio-button>
      <el-radio-button value="MOCK">模拟识别</el-radio-button>
      <el-radio-button value="GRAVITY_MISMATCH">重力错配</el-radio-button>
      <el-radio-button value="GRAVITY_FILL">重力回填</el-radio-button>
      <el-radio-button value="UNMAPPED">未映射</el-radio-button>
      <el-radio-button value="EMPTY">空识别</el-radio-button>
      <el-radio-button value="NEED_REVIEW">需复核</el-radio-button>
      <el-radio-button value="WHITELIST">白名单</el-radio-button>
    </el-radio-group>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="状态">
        <el-select
          v-model="status"
          clearable
          placeholder="全部"
          style="width: 140px"
          @change="search"
        >
          <el-option
            v-for="item in dictOptions('dispute_status')"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="关键词">
        <el-input
          v-model="keyword"
          clearable
          placeholder="工单 / 设备 / 会话 / 订单…"
          style="width: 260px"
          @keyup.enter="search"
          @clear="search"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
    </el-form>

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <el-table
          v-loading="loading"
          :data="items"
          stripe
          border
          class="report-table"
          empty-text=" "
          row-key="ticketId"
          :default-sort="idDefaultSort"
          @sort-change="onIdSortChange"
          @selection-change="onSelectionChange"
        >
          <template #empty>
            <el-empty v-if="listHydrated && !loading" :description="emptyHint" />
          </template>
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column
            prop="ticketId"
            label="工单号"
            min-width="140"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
            sortable="custom"
          >
            <template #default="{ row }">
              <span class="cell-id">{{ row.ticketId }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="工单"
            min-width="160"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              <button type="button" class="link-cell" @click="openDetail(row)">
                {{ row.reason || '无' }}
              </button>
            </template>
          </el-table-column>
          <el-table-column label="置信度" width="100" align="center">
            <template #default="{ row }">
              <el-tag
                v-if="confidenceHint(row)"
                size="small"
                :type="reviewChipType(row)"
                effect="plain"
                >{{ confidenceHint(row) }}</el-tag
              >
              <span v-else class="muted">无</span>
            </template>
          </el-table-column>
          <el-table-column
            label="设备"
            min-width="110"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              <button
                v-if="row.deviceId"
                type="button"
                class="link-cell"
                @click="goDevice(row.deviceId)"
              >
                {{ row.deviceId }}
              </button>
              <span v-else class="muted">无</span>
            </template>
          </el-table-column>
          <el-table-column
            label="会话"
            min-width="130"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              <button
                v-if="row.sessionId"
                type="button"
                class="link-cell mono"
                @click="goSessions(row.deviceId, row.sessionId)"
              >
                {{ displayBizNo(row.sessionId, '无') }}
              </button>
              <span v-else class="muted">无</span>
            </template>
          </el-table-column>
          <el-table-column
            label="关联订单"
            min-width="130"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              <button
                v-if="row.orderId"
                type="button"
                class="link-cell mono"
                @click="goOrders(row.deviceId, row.orderId)"
              >
                {{ displayBizNo(row.orderId) }}
              </button>
              <span v-else class="muted">无</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="96" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="disputeStatusType(row.status)">
                {{ displayLabel('dispute_status', row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column
            label="分类"
            width="100"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              {{ dictLabel('dispute_category', row.category) || row.category || '未知' }}
            </template>
          </el-table-column>
          <el-table-column label="优先级" width="88" align="center">
            <template #default="{ row }">
              <el-tag
                v-if="row.priority"
                size="small"
                :type="row.priority === 'HIGH' || row.priority === 'URGENT' ? 'danger' : 'info'"
              >
                {{ dictLabel('dispute_priority', row.priority) || row.priority }}
              </el-tag>
              <span v-else class="muted">无</span>
            </template>
          </el-table-column>
          <el-table-column label="已扣金额" width="110" align="center" class-name="col-money">
            <template #default="{ row }">¥{{ money(row.billedAmountCents) }}</template>
          </el-table-column>
          <el-table-column label="证据" width="88" align="center">
            <template #default="{ row }">
              <el-tag
                size="small"
                :type="evidenceCount(row) > 0 ? 'success' : 'info'"
                effect="plain"
              >
                {{ evidenceCount(row) > 0 ? `${evidenceCount(row)} 件` : '无' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="168" align="center" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="结案时间" width="168" align="center" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.resolvedAt) || '无' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="220" class-name="col-action" align="center">
            <template #default="{ row }">
              <TableActions :actions="rowActions(row)" @action="(key) => onRowAction(key, row)" />
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <PagePager
      :hydrated="listHydrated"
      v-model:current-page="page"
      v-model:page-size="size"
      :total="total"
      :page-sizes="[10, 20, 50]"
      layout="total, sizes, prev, pager, next"
      background
      @current-change="() => load(false)"
      @size-change="onSizeChange"
    />

    <el-drawer
      v-if="detailVisible"
      v-model="detailVisible"
      title="争议审单工作台"
      size="880px"
      append-to-body
      destroy-on-close
      class="dispute-workbench drawer-workbench"
      @closed="onDetailClosed"
    >
      <el-alert
        v-if="resolveFeedback"
        type="success"
        title="已处理"
        :description="resolveFeedback.message"
        show-icon
        :closable="false"
        class="resolve-feedback"
      />

      <div v-if="selected" class="workbench-grid">
        <section class="workbench-media">
          <div class="items-title">会话录像</div>
          <div v-if="embedVideoUrl" class="video-wrap">
            <video :src="embedVideoUrl" controls playsinline class="session-video" />
          </div>
          <el-empty
            v-else-if="videoAttempted && !videoLoading"
            description="暂无录像或加载失败"
            :image-size="72"
          />
          <div v-else-if="videoLoading" class="video-loading">录像加载中…</div>
          <div v-else class="video-loading muted">尚未加载录像</div>
          <el-alert
            v-if="!embedVideoUrl && videoAttempted && !videoLoading"
            type="info"
            :closable="false"
            show-icon
            class="no-video-guide"
            title="无录像时的结案步骤"
            description="先点「重新加载录像」或「新窗口打开」再试；仍无法播放时，勾选「无录像 / 无法播放，仍结案」，再勾选「已对照录像核对」，然后处理结案。有录像时必须先观看并勾选核对。"
          />
          <div class="drawer-actions drawer-actions--review">
            <el-button
              v-if="
                selected.sessionId &&
                (auth.hasPerm('ops:session:list') || auth.hasPerm('ops:session:upload'))
              "
              type="warning"
              :loading="videoLoading"
              @click="loadEmbedVideo(selected.sessionId, true)"
              >重新加载录像</el-button
            >
            <el-button
              v-if="
                selected.sessionId &&
                (auth.hasPerm('ops:session:list') || auth.hasPerm('ops:session:upload'))
              "
              link
              type="primary"
              @click="playVideo(selected.sessionId)"
              >新窗口打开</el-button
            >
            <el-checkbox v-model="videoReviewed" :disabled="!embedVideoUrl && !noVideoAck"
              >已对照录像核对</el-checkbox
            >
            <el-checkbox
              v-if="!embedVideoUrl && videoAttempted && !videoLoading"
              v-model="noVideoAck"
              >无录像 / 无法播放，仍结案</el-checkbox
            >
            <el-button
              v-if="selected.deviceId && canAccessPath('/exceptions')"
              @click="goExceptions(selected.deviceId)"
              >异常中心</el-button
            >
            <el-button
              v-if="selected.orderId || selected.deviceId"
              @click="goOrders(selected.deviceId, selected.orderId)"
              >关联订单</el-button
            >
          </div>
        </section>

        <section class="workbench-meta">
          <el-alert
            v-if="selected.reviewCode === 'MOCK' || /模拟|非生产精度/.test(selected.reason || '')"
            type="warning"
            :closable="false"
            show-icon
            title="当前为模拟/兜底识别，不是生产级视觉精度；请对照录像人工确认后再落账。"
            class="suggest-alert"
          />
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="工单">
              <span class="cell-id">{{ selected.ticketId }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="会话">
              <button
                v-if="selected.sessionId"
                type="button"
                class="link-cell mono"
                @click="goSessions(selected.deviceId, selected.sessionId)"
              >
                {{ displayBizNo(selected.sessionId, '无') }}
              </button>
              <span v-else>-</span>
            </el-descriptions-item>
            <el-descriptions-item label="设备">
              <button
                v-if="selected.deviceId"
                type="button"
                class="link-cell"
                @click="goDevice(selected.deviceId)"
              >
                {{ selected.deviceId }}
              </button>
              <span v-else>-</span>
            </el-descriptions-item>
            <el-descriptions-item label="原因">
              <div class="reason-block">
                <span>{{ selected.reason || '无' }}</span>
                <el-tag
                  v-if="confidenceHint(selected)"
                  size="small"
                  :type="reviewChipType(selected)"
                  effect="plain"
                >
                  {{ confidenceHint(selected) }}
                </el-tag>
              </div>
            </el-descriptions-item>
            <el-descriptions-item v-if="selected.detectedClasses?.length" label="检出类">
              <div class="detected-classes">
                {{ selected.detectedClasses.join('、') }}
                <el-button
                  v-if="selected.reviewCode === 'UNMAPPED' || selected.detectedClasses.length"
                  link
                  type="primary"
                  @click="goVisionMapping(selected)"
                  >去映射</el-button
                >
              </div>
            </el-descriptions-item>
            <el-descriptions-item label="已扣金额"
              >¥{{ money(selected.billedAmountCents) }}</el-descriptions-item
            >
            <el-descriptions-item label="状态">
              <el-tag
                v-if="resolveFeedback || selected.status !== 'OPEN'"
                type="success"
                effect="light"
                size="small"
              >
                {{ resolveFeedback ? '已处理' : dictLabel('dispute_status', selected.status) }}
              </el-tag>
              <el-tag v-else size="small" type="warning">
                {{ dictLabel('dispute_status', selected.status) }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item v-if="selected.resolvedAt" label="处理时间">
              {{ formatDateTime(selected.resolvedAt) }}
            </el-descriptions-item>
            <el-descriptions-item v-if="selected.orderId" label="关联订单">
              <button
                type="button"
                class="link-cell mono"
                @click="goOrders(selected.deviceId, selected.orderId)"
              >
                {{ displayBizNo(selected.orderId) }}
              </button>
            </el-descriptions-item>
          </el-descriptions>

          <div v-if="selected.suggestedItems?.length" class="items-block">
            <div class="items-title">识别建议（只读）</div>
            <el-table :data="selected.suggestedItems" size="small" stripe border>
              <el-table-column
                prop="skuName"
                label="商品"
                min-width="120"
                align="center"
                class-name="col-text"
              />
              <el-table-column
                prop="skuId"
                label="SKU"
                min-width="100"
                align="center"
                class-name="col-text"
                show-overflow-tooltip
              />
              <el-table-column prop="quantity" label="数量" width="72" align="center" />
              <el-table-column label="单价" width="88" align="center">
                <template #default="{ row }">¥{{ money(row.unitPriceCents) }}</template>
              </el-table-column>
              <el-table-column label="小计" width="88" align="center">
                <template #default="{ row }">¥{{ money(row.lineAmountCents) }}</template>
              </el-table-column>
              <el-table-column prop="slotId" label="货道" width="72" align="center">
                <template #default="{ row }">{{ row.slotId || '—' }}</template>
              </el-table-column>
            </el-table>
          </div>
        </section>
      </div>

      <div v-if="selected?.status === 'OPEN'" class="drawer-actions">
        <div class="items-block adjust-block">
          <div class="items-title">调整明细（落账依据）</div>
          <el-alert
            type="info"
            :closable="false"
            show-icon
            title="对照左侧录像修改 SKU / 数量后，用「按调整明细落账」写回账单差额。"
            class="suggest-alert"
          />
          <div class="manual-lines">
            <div v-for="(line, index) in draftLines" :key="index" class="manual-line">
              <el-select v-model="line.skuId" filterable placeholder="选择商品" style="flex: 1">
                <el-option
                  v-for="sku in skus"
                  :key="sku.skuId"
                  :label="`${sku.skuName}（¥${((sku.priceCents || 0) / 100).toFixed(2)}）`"
                  :value="sku.skuId"
                />
              </el-select>
              <el-input-number v-model="line.quantity" :min="1" :max="99" />
              <el-button type="danger" link @click="removeDraftLine(index)">删除</el-button>
            </div>
            <el-button @click="draftLines.push({ skuId: '', quantity: 1 })">添加商品</el-button>
            <el-button link type="primary" @click="resetDraftFromSuggested"
              >从识别建议填充</el-button
            >
          </div>
        </div>
        <div class="ai-suggest-block">
          <div class="items-title">智能识别建议</div>
          <input
            ref="disputeImageInput"
            type="file"
            accept="image/*"
            class="hidden-input"
            @change="onDisputeImagePick"
          />
          <el-button size="small" :loading="suggestingDispute" @click="triggerDisputeImage">
            上传关键帧获取商品建议
          </el-button>
          <el-alert
            v-if="disputeSuggestHint"
            :title="disputeSuggestHint"
            type="info"
            show-icon
            :closable="false"
            class="suggest-alert"
          />
        </div>
        <el-button
          v-if="hasPriorBill"
          v-hasPermi="['ops:dispute:resolve']"
          type="primary"
          :loading="resolving"
          @click="resolveSelected('KEEP')"
          >维持原账单</el-button
        >
        <el-button
          v-hasPermi="['ops:dispute:resolve']"
          type="success"
          :loading="resolving"
          :disabled="!draftConfirmItems.length"
          @click="resolveSelected('ADJUST')"
          >按调整明细落账</el-button
        >
        <el-button
          v-hasPermi="['ops:dispute:resolve']"
          type="danger"
          plain
          :loading="resolving"
          @click="resolveSelected('WAIVE')"
          >免单并退款</el-button
        >
      </div>
    </el-drawer>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import {
  CircleClose,
  Link,
  Refresh,
  RefreshLeft,
  VideoCamera,
  View,
  Warning
} from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { dictLabel, dictOptions, displayLabel } from '@aicabinet/shared-dict';
import { api, authFetch } from '@/api/client';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import PagePager from '@/components/PagePager.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useNavAccess } from '@/composables/useNavAccess';
import { useSessionVideo } from '@/composables/useSessionVideo';
import { useTableSelection } from '@/composables/useTableSelection';
import type {
  DevRecognitionPreviewDto,
  DisputeTicketDto,
  OrderLineDto,
  PageResult
} from '@aicabinet/shared-types';
import { displayBizNo, formatDateTime } from '@aicabinet/shared-uni/format';
import { useIdColumnSort } from '@/composables/useIdColumnSort';

interface ResolveDisputeResultDto {
  order?: { orderId?: string } | null;
  resolutionType?: string;
  originalAmountCents?: number;
  finalAmountCents?: number;
  adjustmentCents?: number;
  message?: string;
}

type SkuOption = { skuId: string; skuName: string; priceCents?: number };

const route = useRoute();
const router = useRouter();
const { auth, canAccessPath, goPath } = useNavAccess();
const { playSessionVideo, fetchSessionVideoBlob } = useSessionVideo();
const loading = ref(false);
const listHydrated = ref(false);
const videoLoading = ref(false);
const videoAttempted = ref(false);
const embedVideoUrl = ref('');
let embedVideoRevoke: (() => void) | null = null;
const status = ref('OPEN');
const categoryTab = ref('ALL');
const reviewCodeTab = ref('ALL');
const keyword = ref('');
const focusDisputeId = ref('');
const items = ref<DisputeTicketDto[]>([]);
const { idDefaultSort, onIdSortChange, sortById } = useIdColumnSort('ticketId', {
  onChange: () => {
    items.value = sortById([...items.value], 'ticketId');
  }
});
const page = ref(1);
const size = ref(20);
const total = ref(0);
const selected = ref<DisputeTicketDto | null>(null);
const detailVisible = ref(false);
const resolving = ref(false);
const suggestingDispute = ref(false);
const disputeSuggestHint = ref('');
const disputeImageInput = ref<HTMLInputElement | null>(null);
const resolveFeedback = ref<{ message: string } | null>(null);
const videoReviewed = ref(false);
const noVideoAck = ref(false);
const skus = ref<SkuOption[]>([]);
const draftLines = ref<{ skuId: string; quantity: number }[]>([]);

const { onSelectionChange, pickSelected, exportButtonLabel, clearSelection } =
  useTableSelection<DisputeTicketDto>((r) => r.ticketId);

const { onExport } = useListCsv({
  filePrefix: '争议',
  headers: [
    '工单',
    '设备',
    '会话',
    '关联订单',
    '状态',
    '分类',
    '优先级',
    '已扣金额',
    '原因',
    '创建时间',
    '结案时间'
  ],
  toRows: () =>
    pickSelected(items.value).map((row) => [
      row.ticketId,
      row.deviceId,
      row.sessionId,
      row.orderId,
      dictLabel('dispute_status', row.status),
      dictLabel('dispute_category', row.category) || row.category,
      dictLabel('dispute_priority', row.priority) || row.priority,
      money(row.billedAmountCents),
      row.reason,
      formatDateTime(row.createdAt),
      formatDateTime(row.resolvedAt)
    ])
});

const emptyHint = computed(() => {
  if (categoryTab.value === 'RECOGNITION' && reviewCodeTab.value !== 'ALL') {
    return `当前无「${confidenceHint({ reviewCode: reviewCodeTab.value, category: 'RECOGNITION' })}」待审工单`;
  }
  return status.value === 'OPEN' ? '当前无待审核工单，可切换「已结案」查看历史' : '暂无数据';
});

const hasPriorBill = computed(() => (selected.value?.billedAmountCents || 0) > 0);

const draftConfirmItems = computed(() =>
  draftLines.value
    .filter((line) => line.skuId && (line.quantity || 0) > 0)
    .map((line) => ({ skuId: line.skuId, quantity: line.quantity }))
);

function resetDraftFromSuggested() {
  const suggested = selected.value?.suggestedItems || [];
  draftLines.value = suggested
    .filter((line: OrderLineDto) => line.skuId && (line.quantity || 0) > 0)
    .map((line: OrderLineDto) => ({
      skuId: String(line.skuId),
      quantity: Number(line.quantity) || 1
    }));
  if (!draftLines.value.length) {
    draftLines.value = [{ skuId: '', quantity: 1 }];
  }
}

async function removeDraftLine(index: number) {
  try {
    await ElMessageBox.confirm('确定删除该商品行吗？', '删除商品', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    });
  } catch {
    return;
  }
  draftLines.value.splice(index, 1);
  if (!draftLines.value.length) {
    draftLines.value = [{ skuId: '', quantity: 1 }];
  }
}

async function ensureSkusLoaded() {
  if (skus.value.length) return;
  try {
    skus.value = await api.request<SkuOption[]>('/api/v2/ops/admin/skus', 'GET');
  } catch {
    skus.value = [];
  }
}

function clearEmbedVideo() {
  if (embedVideoRevoke) {
    embedVideoRevoke();
    embedVideoRevoke = null;
  }
  embedVideoUrl.value = '';
}

function onDetailClosed() {
  resolveFeedback.value = null;
  videoReviewed.value = false;
  noVideoAck.value = false;
  videoAttempted.value = false;
  clearEmbedVideo();
  draftLines.value = [];
}

async function loadEmbedVideo(sessionId?: string, force = false) {
  if (!sessionId) return;
  if (embedVideoUrl.value && !force) return;
  videoLoading.value = true;
  try {
    clearEmbedVideo();
    const { url, revoke } = await fetchSessionVideoBlob(sessionId);
    embedVideoUrl.value = url;
    embedVideoRevoke = revoke;
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '录像加载失败');
  } finally {
    videoAttempted.value = true;
    videoLoading.value = false;
  }
}

function money(cents?: number) {
  return ((cents || 0) / 100).toFixed(2);
}

function evidenceCount(row: { evidence?: unknown[] | null }) {
  return Array.isArray(row.evidence) ? row.evidence.length : 0;
}

function disputeStatusType(s?: string) {
  if (s === 'OPEN') return 'warning';
  if (s === 'RESOLVED') return 'success';
  if (s === 'CLOSED') return 'info';
  return '';
}

function confidenceHint(
  row?: DisputeTicketDto | null | { reviewCode?: string; category?: string; reason?: string }
) {
  if (!row) return '';
  const code = String(row.reviewCode || '').toUpperCase();
  if (code === 'LOW_CONF') return '低置信';
  if (code === 'MOCK') return '模拟识别';
  if (code === 'GRAVITY_MISMATCH') return '重力错配';
  if (code === 'GRAVITY_FILL') return '重力回填';
  if (code === 'UNMAPPED') return '未映射';
  if (code === 'EMPTY') return '空识别';
  if (code === 'WHITELIST') return '白名单';
  if (code === 'NEED_REVIEW') return '需复核';
  const text = `${row.reason || ''} ${row.category || ''}`;
  if (row.category === 'RECOGNITION' || /识别|置信|未映射|存疑|模拟|重力/.test(text)) {
    if (/模拟|非生产精度|mock/i.test(text)) return '模拟识别';
    if (/视觉与重力|重力.*不一致|错配/.test(text)) return '重力错配';
    if (/仅有重力|重力信号/.test(text)) return '重力回填';
    if (/低置信|置信度|阈值/.test(text)) return '低置信';
    if (/未映射|检出类/.test(text)) return '未映射';
    if (/未识别/.test(text)) return '空识别';
    if (/白名单|视觉状态/.test(text)) return '白名单';
    if (row.category === 'RECOGNITION') return '识别争议';
  }
  return '';
}

function reviewChipType(row?: DisputeTicketDto | null) {
  const code = String(row?.reviewCode || '').toUpperCase();
  if (code === 'LOW_CONF' || code === 'EMPTY' || code === 'GRAVITY_MISMATCH') return 'danger';
  if (code === 'MOCK' || code === 'GRAVITY_FILL' || code === 'UNMAPPED' || code === 'WHITELIST')
    return 'warning';
  return 'info';
}

function rowActions(row: DisputeTicketDto): TableAction[] {
  const actions: TableAction[] = [{ key: 'detail', label: '详情', icon: View, type: 'primary' }];
  if (row.sessionId && (auth.hasPerm('ops:session:list') || auth.hasPerm('ops:session:upload'))) {
    actions.push({
      key: 'video',
      label: '录像',
      icon: VideoCamera,
      type: 'warning',
      overflow: true
    });
  }
  if (row.reviewCode === 'UNMAPPED' || (row.detectedClasses && row.detectedClasses.length)) {
    actions.push({ key: 'mapping', label: '去映射', icon: Link, overflow: true });
  }
  if (row.deviceId && canAccessPath('/exceptions')) {
    actions.push({ key: 'exception', label: '异常', icon: Warning, overflow: true });
  }
  if (row.orderId || row.deviceId) {
    actions.push({ key: 'order', label: '订单', icon: Link, overflow: true });
  }
  if (row.status === 'RESOLVED' && auth.hasPerm('ops:dispute:resolve')) {
    actions.push({ key: 'close', label: '关闭', icon: CircleClose, overflow: true });
  }
  if (row.status === 'CLOSED' && auth.hasPerm('ops:dispute:resolve')) {
    actions.push({ key: 'reopen', label: '重开', icon: RefreshLeft, overflow: true });
  }
  return actions;
}

function onRowAction(key: string, row: DisputeTicketDto) {
  if (key === 'detail') openDetail(row);
  if (key === 'video') playVideo(row.sessionId);
  if (key === 'exception') goExceptions(row.deviceId);
  if (key === 'order') goOrders(row.deviceId, row.orderId);
  if (key === 'mapping') goVisionMapping(row);
  if (key === 'close') void closeTicket(row);
  if (key === 'reopen') void reopenTicket(row);
}

async function closeTicket(row: DisputeTicketDto) {
  try {
    await ElMessageBox.confirm(`确认关闭争议 ${row.ticketId}？关闭后仍可重开。`, '关闭争议', {
      type: 'warning'
    });
  } catch {
    return;
  }
  try {
    await api.request(`/api/v2/ops/disputes/${encodeURIComponent(row.ticketId)}/close`, 'POST', {});
    ElMessage.success('已关闭');
    await load(false);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '关闭失败');
  }
}

async function reopenTicket(row: DisputeTicketDto) {
  try {
    await ElMessageBox.confirm(`确认重开争议 ${row.ticketId}？`, '重开争议', { type: 'warning' });
  } catch {
    return;
  }
  try {
    await api.request(
      `/api/v2/ops/disputes/${encodeURIComponent(row.ticketId)}/reopen`,
      'POST',
      {}
    );
    ElMessage.success('已重开');
    await load(false);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '重开失败');
  }
}

function goVisionMapping(row?: DisputeTicketDto | null) {
  if (!canAccessPath('/vision-mappings')) {
    ElMessage.warning('无识别映射访问权限');
    return;
  }
  const query: Record<string, string> = {};
  const cls = row?.detectedClasses?.[0];
  if (cls) query.keyword = cls;
  goPath('/vision-mappings', query);
}

async function playVideo(sessionId?: string) {
  videoLoading.value = true;
  try {
    await playSessionVideo(sessionId);
  } finally {
    videoLoading.value = false;
  }
}

function goDevice(id: string) {
  if (!canAccessPath('/devices')) {
    ElMessage.warning('无访问权限');
    return;
  }
  router.push(`/devices/${encodeURIComponent(id)}`);
}
function goSessions(device?: string, sessionId?: string) {
  const query: Record<string, string> = {};
  if (device) query.deviceId = device;
  if (sessionId) query.sessionId = sessionId;
  goPath('/sessions', query);
}
function goOrders(device?: string, orderId?: string) {
  const query: Record<string, string> = {};
  if (orderId) query.orderId = orderId;
  if (device) query.deviceId = device;
  goPath('/orders', query);
}
function goExceptions(device?: string) {
  const query: Record<string, string> = { status: 'OPEN' };
  if (device) query.deviceId = device;
  goPath('/exceptions', query);
}

function openDetail(row: DisputeTicketDto) {
  resolveFeedback.value = null;
  disputeSuggestHint.value = '';
  videoReviewed.value = false;
  noVideoAck.value = false;
  videoAttempted.value = false;
  clearEmbedVideo();
  selected.value = row;
  detailVisible.value = true;
  resetDraftFromSuggested();
  void ensureSkusLoaded();
  if (row.sessionId && (auth.hasPerm('ops:session:list') || auth.hasPerm('ops:session:upload'))) {
    void loadEmbedVideo(row.sessionId);
  }
}

function triggerDisputeImage() {
  disputeImageInput.value?.click();
}

async function onDisputeImagePick(ev: Event) {
  if (!selected.value?.deviceId) {
    ElMessage.warning('工单缺少设备 ID');
    return;
  }
  const input = ev.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  suggestingDispute.value = true;
  disputeSuggestHint.value = '';
  try {
    const base = (import.meta.env.VITE_API_BASE || '').replace(/\/$/, '') || window.location.origin;
    const form = new FormData();
    form.append('deviceId', selected.value.deviceId);
    form.append('image', file);
    const res = await authFetch(`${base}/api/v2/ops/dispute-suggest`, {
      method: 'POST',
      body: form
    });
    const json = await res.json().catch(() => ({}));
    if (!res.ok || json.code !== 0) {
      throw new Error(json.message || `请求失败 (${res.status})`);
    }
    const preview = json.data as DevRecognitionPreviewDto;
    disputeSuggestHint.value = preview.hint || '未返回建议';
    if (preview.items?.length && selected.value) {
      selected.value = {
        ...selected.value,
        suggestedItems: preview.items.map((i) => ({
          skuId: i.skuId,
          skuName: i.skuName,
          quantity: i.quantity,
          unitPriceCents: 0,
          lineAmountCents: 0
        }))
      };
      resetDraftFromSuggested();
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '智能识别建议失败');
  } finally {
    suggestingDispute.value = false;
    input.value = '';
  }
}

function applyResolvedTicket(result: ResolveDisputeResultDto) {
  if (!selected.value) return;
  const ticketId = selected.value.ticketId;
  const patched: DisputeTicketDto = {
    ...selected.value,
    status: 'RESOLVED',
    resolvedAt: new Date().toISOString(),
    orderId: result.order?.orderId ?? selected.value.orderId,
    billedAmountCents: result.finalAmountCents ?? selected.value.billedAmountCents
  };
  selected.value = patched;
  if (status.value === 'OPEN') {
    items.value = items.value.filter((t) => t.ticketId !== ticketId);
    total.value = Math.max(0, total.value - 1);
  } else {
    items.value = items.value.map((t) => (t.ticketId === ticketId ? patched : t));
  }
  resolveFeedback.value = { message: result.message || '争议已结案' };
}

async function resolveSelected(resolutionType: 'KEEP' | 'WAIVE' | 'CONFIRM' | 'ADJUST') {
  if (!selected.value || resolving.value) return;
  if (!videoReviewed.value && !noVideoAck.value) {
    ElMessage.warning(
      embedVideoUrl.value
        ? '请先观看录像并勾选「已对照录像核对」'
        : '请先勾选「无录像 / 无法播放，仍结案」，再勾选「已对照录像核对」后结案'
    );
    return;
  }
  if (noVideoAck.value && !videoReviewed.value) {
    ElMessage.warning('已确认无录像后，仍需勾选「已对照录像核对」表示人工已知情结案');
    return;
  }
  const action =
    resolutionType === 'KEEP'
      ? '维持原账单'
      : resolutionType === 'WAIVE'
        ? '免单并退回全部已扣余额'
        : '按调整明细落账（可能补扣或退差）';
  if (
    (resolutionType === 'ADJUST' || resolutionType === 'CONFIRM') &&
    !draftConfirmItems.value.length
  ) {
    ElMessage.warning('请先填写至少一行有效商品');
    return;
  }
  try {
    await ElMessageBox.confirm(
      `确认${action}？该操作会写入资金与审计记录。${noVideoAck.value ? '\n（已确认无录像仍结案）' : '\n（已确认对照录像）'}`,
      '确认争议处理',
      {
        type: resolutionType === 'WAIVE' ? 'warning' : 'info',
        confirmButtonText: '确认处理',
        cancelButtonText: '取消'
      }
    );
  } catch (e: any) {
    if (e === 'cancel' || e === 'close') return;
    ElMessage.error(e instanceof Error ? e.message : '确认失败');
    return;
  }
  let restoreInventory: boolean | undefined;
  if (resolutionType === 'WAIVE') {
    try {
      await ElMessageBox.confirm(
        '免单库存处理：\n「退货退款」= 误识别/货仍在柜，回库\n「仅退款」= 顾客已拿走，不回库',
        '免单是否回库',
        {
          distinguishCancelAndClose: true,
          confirmButtonText: '退货退款（回库）',
          cancelButtonText: '仅退款（不回库）',
          type: 'warning'
        }
      );
      restoreInventory = true;
    } catch (action) {
      if (action === 'cancel') {
        restoreInventory = false;
      } else {
        return;
      }
    }
  }
  resolving.value = true;
  try {
    const result = await api.request<ResolveDisputeResultDto>(
      `/api/v2/ops/disputes/${encodeURIComponent(selected.value.ticketId)}/resolve`,
      'POST',
      {
        resolutionType,
        restoreInventory,
        items:
          resolutionType === 'ADJUST' || resolutionType === 'CONFIRM' ? draftConfirmItems.value : []
      }
    );
    applyResolvedTicket(result);
    ElMessage.success(result.message || '争议已处理');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '处理失败');
  } finally {
    resolving.value = false;
  }
}

function classifyKeyword(raw: string): { orderId?: string; sessionId?: string; deviceId?: string } {
  const v = raw.trim();
  if (!v) return {};
  if (/^O/i.test(v)) return { orderId: v };
  // 会话号：历史 S 前缀，或雪花数字（看板关联里常见）
  if (/^S/i.test(v) || /^\d{10,}$/.test(v)) return { sessionId: v };
  // 设备号多含字母/短横线（如 CAB-001）
  return { deviceId: v };
}

function syncRouteQuery() {
  const query: Record<string, string> = {};
  if (status.value) query.status = status.value;
  if (categoryTab.value && categoryTab.value !== 'ALL') query.category = categoryTab.value;
  if (categoryTab.value === 'RECOGNITION' && reviewCodeTab.value && reviewCodeTab.value !== 'ALL') {
    query.reviewCode = reviewCodeTab.value;
  }
  if (keyword.value.trim()) query.keyword = keyword.value.trim();
  // 看板深链工单号：tab 变更触发的 replace 不得冲掉，否则详情无法自动打开
  if (focusDisputeId.value) query.ticketId = focusDisputeId.value;
  router.replace({ query });
}

function onCategoryTab(name: string | number) {
  categoryTab.value = String(name);
  if (categoryTab.value !== 'RECOGNITION') reviewCodeTab.value = 'ALL';
  page.value = 1;
  syncRouteQuery();
  load(false);
}

function onReviewCodeTab() {
  page.value = 1;
  syncRouteQuery();
  load(false);
}

async function load(showToast = false) {
  loading.value = true;
  try {
    const q = new URLSearchParams({
      page: String(page.value - 1),
      size: String(size.value)
    });
    if (status.value) q.set('status', status.value);
    if (categoryTab.value && categoryTab.value !== 'ALL') {
      q.set('category', categoryTab.value);
    }
    if (
      categoryTab.value === 'RECOGNITION' &&
      reviewCodeTab.value &&
      reviewCodeTab.value !== 'ALL'
    ) {
      q.set('reviewCode', reviewCodeTab.value);
    }
    const classified = classifyKeyword(keyword.value);
    if (classified.sessionId) q.set('sessionId', classified.sessionId);
    if (classified.deviceId) q.set('deviceId', classified.deviceId);
    if (classified.orderId) q.set('orderId', classified.orderId);
    const data = await api.request<PageResult<DisputeTicketDto>>(
      `/api/v2/ops/disputes?${q}`,
      'GET'
    );
    items.value = sortById(data.items || [], 'ticketId');
    total.value = data.total || 0;
    // 关键词若是工单号（雪花）被当成 sessionId 会空；回退按 ticketId 拉详情
    if (!items.value.length && /^\d{10,}$/.test(keyword.value.trim())) {
      try {
        const byTicket = await api.request<DisputeTicketDto>(
          `/api/v2/ops/disputes/${encodeURIComponent(keyword.value.trim())}`,
          'GET'
        );
        if (byTicket?.ticketId) {
          items.value = [byTicket];
          total.value = 1;
        }
      } catch {
        /* 非工单号则保持空列表 */
      }
    }
    clearSelection();
    await openFocusedTicket();
    if (showToast) ElMessage.success('已刷新');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    listHydrated.value = true;
    loading.value = false;
  }
}

function onSizeChange() {
  page.value = 1;
  load(false);
}

function search() {
  page.value = 1;
  syncRouteQuery();
  load(false);
}

function reset() {
  status.value = 'OPEN';
  categoryTab.value = 'ALL';
  reviewCodeTab.value = 'ALL';
  keyword.value = '';
  page.value = 1;
  syncRouteQuery();
  load(false);
}

/** 看板/深链带入筛选时，清掉本地粘住的分拣 tab，避免 MOCK 把 EMPTY 工单滤没 */
function hasInboundDeepLink() {
  return (
    typeof route.query.status === 'string' ||
    typeof route.query.sessionId === 'string' ||
    typeof route.query.ticketId === 'string' ||
    typeof route.query.disputeId === 'string' ||
    typeof route.query.keyword === 'string' ||
    typeof route.query.orderId === 'string' ||
    typeof route.query.deviceId === 'string' ||
    typeof route.query.category === 'string' ||
    typeof route.query.reviewCode === 'string'
  );
}

function applyRouteQuery() {
  let changed = false;
  const inbound = hasInboundDeepLink();
  const allowedStatus = new Set(['OPEN', 'RESOLVED', 'CLOSED']);
  if (typeof route.query.status === 'string' && route.query.status) {
    const next = route.query.status.trim().toUpperCase();
    if (allowedStatus.has(next) && next !== status.value) {
      status.value = next;
      changed = true;
    }
  }
  if (typeof route.query.category === 'string') {
    const next = route.query.category || 'ALL';
    if (next !== categoryTab.value) {
      categoryTab.value = next === 'RECOGNITION' ? 'RECOGNITION' : next === 'ALL' ? 'ALL' : next;
      changed = true;
    }
  } else if (inbound && categoryTab.value !== 'ALL') {
    categoryTab.value = 'ALL';
    changed = true;
  }
  if (typeof route.query.reviewCode === 'string') {
    const next = route.query.reviewCode || 'ALL';
    if (next !== reviewCodeTab.value) {
      reviewCodeTab.value = next;
      if (next !== 'ALL') categoryTab.value = 'RECOGNITION';
      changed = true;
    }
  } else if (inbound && reviewCodeTab.value !== 'ALL') {
    reviewCodeTab.value = 'ALL';
    changed = true;
  }
  const routeKeyword =
    typeof route.query.keyword === 'string'
      ? route.query.keyword
      : typeof route.query.orderId === 'string'
        ? route.query.orderId
        : typeof route.query.sessionId === 'string'
          ? route.query.sessionId
          : typeof route.query.deviceId === 'string'
            ? route.query.deviceId
            : '';
  if (routeKeyword !== keyword.value) {
    keyword.value = routeKeyword;
    changed = true;
  }
  const focusId =
    typeof route.query.ticketId === 'string'
      ? route.query.ticketId
      : typeof route.query.disputeId === 'string'
        ? route.query.disputeId
        : '';
  if (focusId !== focusDisputeId.value) {
    focusDisputeId.value = focusId;
    changed = true;
  }
  return changed;
}

async function openFocusedTicket() {
  if (!focusDisputeId.value || detailVisible.value) return;
  let row = items.value.find((it) => String(it.ticketId ?? '') === String(focusDisputeId.value));
  if (!row) {
    try {
      row = await api.request<DisputeTicketDto>(
        `/api/v2/ops/disputes/${encodeURIComponent(focusDisputeId.value)}`,
        'GET'
      );
    } catch {
      row = undefined;
    }
  }
  if (row) openDetail(row);
}

async function reloadFromRouteQuery() {
  if (!applyRouteQuery()) return;
  page.value = 1;
  await load(false);
}

watch(
  () =>
    [
      route.query.status,
      route.query.category,
      route.query.reviewCode,
      route.query.keyword,
      route.query.sessionId,
      route.query.deviceId,
      route.query.orderId,
      route.query.ticketId,
      route.query.disputeId
    ] as const,
  () => {
    void reloadFromRouteQuery();
  }
);

onActivated(async () => {
  detailVisible.value = false;
  selected.value = null;
  resolveFeedback.value = null;
  clearEmbedVideo();
  // keep-alive 复用时必须按本次 query 重置分拣 tab，再拉列表
  applyRouteQuery();
  page.value = 1;
  await load(false);
  await openFocusedTicket();
});
onDeactivated(() => {
  detailVisible.value = false;
  selected.value = null;
  clearEmbedVideo();
});
onMounted(async () => {
  applyRouteQuery();
  syncRouteQuery();
  await load(false);
  await openFocusedTicket();
});
</script>

<style scoped>
.page-card-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  flex-wrap: wrap;
}
.page-card-head__meta {
  min-width: 0;
}
.page-card-head__title {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.title {
  font-weight: 600;
  font-size: 15px;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.4;
}
.page-card-head__actions {
  display: flex;
  gap: 8px;
}
.review-code-tabs {
  margin: 0 0 12px;
}
.detected-classes {
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.4;
}
.reason-block {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}
.ticket-cell:hover strong {
  text-decoration: underline;
}
.link-cell {
  appearance: none;
  border: 0;
  padding: 0;
  margin: 0;
  background: transparent;
  color: var(--el-color-primary);
  cursor: pointer;
  text-align: center;
  font: inherit;
}
.link-cell:hover {
  text-decoration: underline;
}
.link-cell.mono {
  font-family: inherit;
  font-size: inherit;
}
.muted {
  color: var(--el-text-color-secondary);
}
.resolve-feedback {
  margin-bottom: 16px;
}
.drawer-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 24px;
}
.drawer-actions--review {
  margin-top: 16px;
  margin-bottom: 0;
}
.status-tabs {
  margin: 0 0 10px;
}
.items-block {
  margin-top: 20px;
}
.items-title {
  font-weight: 600;
  margin-bottom: 8px;
}
.ai-suggest-block {
  width: 100%;
  margin-bottom: 12px;
}
.suggest-alert {
  margin-top: 8px;
}
.no-video-guide {
  margin: 8px 0;
}
.hidden-input {
  display: none;
}
.workbench-grid {
  display: grid;
  grid-template-columns: minmax(280px, 1.1fr) minmax(280px, 1fr);
  gap: 16px;
  align-items: start;
}
.workbench-media,
.workbench-meta {
  min-width: 0;
}
.video-wrap {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  overflow: hidden;
  background: #0f172a;
}
.session-video {
  display: block;
  width: 100%;
  max-height: 360px;
  background: #0f172a;
}
.video-loading {
  padding: 48px 12px;
  text-align: center;
  color: var(--el-text-color-secondary);
  border: 1px dashed var(--el-border-color);
  border-radius: 8px;
}
.adjust-block {
  width: 100%;
}
.manual-lines {
  display: grid;
  gap: 10px;
  margin-top: 10px;
}
.manual-line {
  display: flex;
  gap: 8px;
  align-items: center;
}
@media (max-width: 900px) {
  .workbench-grid {
    grid-template-columns: 1fr;
  }
}
</style>
