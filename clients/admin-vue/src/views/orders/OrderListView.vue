<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">订单管理</span>
            <span class="hint"
              >支付 / 退款状态分列；待支付支持账龄追缴；可按订单或按商品行导出</span
            >
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-dropdown v-hasPermi="['ops:order:export']" trigger="click" @command="onExportMode">
            <el-button>{{ exportButtonLabel }}<span class="export-caret"> ▾</span></el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="orders">按订单导出</el-dropdown-item>
                <el-dropdown-item command="lines">按商品导出</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-tabs v-model="statusTab" class="status-tabs" @tab-change="onStatusTab">
      <el-tab-pane label="全部" name="ALL" />
      <el-tab-pane label="待支付" name="PENDING" />
      <el-tab-pane label="已支付" name="PAID" />
      <el-tab-pane label="争议中" name="DISPUTED" />
      <el-tab-pane label="已退款" name="REFUNDED" />
      <el-tab-pane label="已关闭" name="CANCELLED" />
    </el-tabs>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="关键词">
        <el-input
          v-model="keyword"
          clearable
          placeholder="订单号 / 设备 / 会话 / 用户 / 流水…"
          style="width: 260px"
          @keyup.enter="search"
        />
      </el-form-item>
      <el-form-item label="渠道">
        <el-select
          v-model="payChannel"
          clearable
          placeholder="全部"
          style="width: 120px"
          @change="search"
        >
          <el-option
            v-for="item in dictOptions('pay_channel')"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="时间">
        <el-date-picker
          v-model="createdRange"
          type="datetimerange"
          value-format="x"
          start-placeholder="起"
          end-placeholder="止"
          style="width: 340px"
          @change="search"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
      <el-form-item v-if="statusTab === 'PENDING'">
        <el-checkbox v-model="overdueOnly" @change="onOverdueToggle"
          >仅超时未付（≥30 分钟）</el-checkbox
        >
      </el-form-item>
    </el-form>

    <el-alert
      v-if="statusTab === 'PENDING' && overdueOnly"
      type="warning"
      :closable="false"
      show-icon
      class="chase-banner"
      :title="
        listHydrated
          ? `本页 ${displayItems.length} 条超时未付（账龄 ≥ 30 分钟，按账龄降序）`
          : '超时未付 — 加载中…'
      "
    />

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <el-table
          v-loading="loading"
          :data="displayItems"
          :default-sort="idDefaultSort"
          @sort-change="onIdSortChange"
          stripe
          border
          class="report-table"
          row-key="orderId"
          :row-class-name="orderRowClass"
          @selection-change="onSelectionChange"
          empty-text=" "
        >
          <template #empty>
            <el-empty
              v-if="listHydrated && !loading"
              :description="statusTab === 'PENDING' && overdueOnly ? '无超时未付订单' : '暂无订单'"
            />
          </template>
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column
            prop="orderId"
            label="订单号"
            min-width="140"
            align="center"
            sortable="custom"
          >
            <template #default="{ row }">
              <button type="button" class="link-cell" @click="openDetail(row)">
                <span class="cell-id">{{ row.orderId }}</span>
              </button>
            </template>
          </el-table-column>
          <el-table-column label="会话" min-width="110" align="center" show-overflow-tooltip>
            <template #default="{ row }">
              <button
                v-if="row.sessionId"
                type="button"
                class="link-cell mono"
                @click="goSessions(row.deviceId, row.sessionId)"
              >
                {{ row.sessionId }}
              </button>
              <span v-else class="muted">无</span>
            </template>
          </el-table-column>
          <el-table-column label="用户" width="96" align="center">
            <template #default="{ row }">{{ row.userId ?? '无' }}</template>
          </el-table-column>
          <el-table-column label="设备" min-width="100" align="center">
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
            label="流水号"
            min-width="110"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              <span class="mono">{{ row.payTradeNo || row.paymentOperationId || '无' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="订单状态" width="90" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="orderStatusType(row.status)">
                {{ dictLabel('order_status', row.status) || row.status || '未知状态' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="支付状态" width="84" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="paymentStatusType(row.status)" effect="plain">
                {{ paymentStatusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="退款状态" width="84" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="refundTagType(row.status)" effect="plain">
                {{ refundColumnLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="支付渠道" width="84" align="center">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">
                {{ dictLabel('pay_channel', row.payChannel) || row.payChannel || '未知渠道' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="扣库存" width="88" align="center">
            <template #default="{ row }">
              <el-tag
                size="small"
                :type="row.inventoryDeducted ? 'success' : 'info'"
                effect="plain"
              >
                {{ row.inventoryDeducted ? '已扣' : '未扣' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column
            label="商品"
            min-width="140"
            align="center"
            class-name="col-goods"
            label-class-name="col-goods"
          >
            <template #default="{ row }">
              <div
                v-for="(disp, idx) in [goodsDisplay(row)]"
                :key="`${row.orderId}-${idx}`"
                class="goods-cell"
              >
                <template v-if="disp.lines.length">
                  <div v-for="(g, i) in disp.lines" :key="`${row.orderId}-${i}`" class="goods-line">
                    <span class="goods-name">{{ g.title }}</span>
                    <span v-if="g.qty" class="goods-qty">×{{ g.qty }}</span>
                  </div>
                  <div v-if="disp.extraKinds != null" class="goods-meta">
                    等 {{ disp.extraKinds }} 种 · 共 {{ disp.total }} 件
                  </div>
                  <div v-else-if="disp.total > 1 || disp.lines.length > 1" class="goods-meta">
                    共 {{ disp.total }} 件
                  </div>
                </template>
                <span v-else class="muted">—</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="金额" width="100" align="center">
            <template #default="{ row }">¥{{ money(row.totalAmountCents) }}</template>
          </el-table-column>
          <el-table-column v-if="statusTab === 'PENDING'" label="账龄" width="110" align="center">
            <template #default="{ row }">
              <span :class="{ 'is-overdue-age': isUnpaidOverdue(row) }">{{
                formatOrderAge(row.createdAt)
              }}</span>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="140" align="center" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="180" align="center" class-name="col-action">
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
      :page-sizes="[10, 20, 50, 100]"
      layout="total, sizes, prev, pager, next, jumper"
      background
      @current-change="load"
      @size-change="onSizeChange"
    />

    <el-drawer v-model="detailOpen" title="订单详情" size="480px" destroy-on-close>
      <div v-loading="detailLoading">
        <template v-if="detail">
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="订单号">
              <span class="cell-id">{{ detail.orderId }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="会话">
              <button
                v-if="detail.sessionId"
                type="button"
                class="link-cell mono"
                @click="goSessions(detail.deviceId, detail.sessionId)"
              >
                {{ detail.sessionId }}
              </button>
              <span v-else>-</span>
            </el-descriptions-item>
            <el-descriptions-item label="设备">
              <button
                v-if="detail.deviceId"
                type="button"
                class="link-cell"
                @click="goDevice(detail.deviceId)"
              >
                {{ detail.deviceId }}
              </button>
              <span v-else>-</span>
            </el-descriptions-item>
            <el-descriptions-item label="状态">
              {{ dictLabel('order_status', detail.status) }}
            </el-descriptions-item>
            <el-descriptions-item label="金额"
              >¥{{ money(detail.totalAmountCents) }}</el-descriptions-item
            >
            <el-descriptions-item label="支付渠道">
              {{ dictLabel('pay_channel', detail.payChannel) || detail.payChannel || '未知渠道' }}
            </el-descriptions-item>
            <el-descriptions-item label="创建时间">{{
              formatDateTime(detail.createdAt)
            }}</el-descriptions-item>
          </el-descriptions>

          <div class="drawer-actions">
            <el-button
              v-if="
                detail.sessionId &&
                (auth.hasPerm('ops:session:list') || auth.hasPerm('ops:session:upload'))
              "
              type="warning"
              :loading="videoLoading"
              @click="playVideo(detail.sessionId)"
              >播放会话录像</el-button
            >
            <el-button
              v-if="detail.status === 'PENDING' && auth.hasPerm('ops:order:remind')"
              type="warning"
              @click="remindOrder(detail)"
              >催付</el-button
            >
            <el-button
              v-if="
                detail.status === 'PENDING' &&
                (auth.hasPerm('ops:order:remind') ||
                  auth.hasPerm('ops:order:cancel') ||
                  auth.hasPerm('ops:order:refund'))
              "
              type="success"
              plain
              @click="collectUnpaid(detail)"
              >补扣收款</el-button
            >
            <el-button
              v-if="detail.status === 'PENDING' && auth.hasPerm('ops:order:cancel')"
              type="danger"
              plain
              @click="cancelUnpaid(detail)"
              >关单</el-button
            >
            <el-button
              v-if="canRefund(detail.status) && auth.hasPerm('ops:order:refund')"
              type="danger"
              :loading="refundingId === detail.orderId"
              @click="refundOrder(detail)"
              >原路退款</el-button
            >
          </div>

          <h4 class="section-title">商品行</h4>
          <el-table :data="detail.lines || detail.items || []" size="small" border empty-text=" ">
            <template #empty>
              <el-empty v-if="!detailLoading" description="无商品行" :image-size="48" />
            </template>
            <el-table-column
              prop="skuName"
              label="商品"
              min-width="120"
              align="center"
              class-name="col-text"
            />
            <el-table-column prop="quantity" label="数量" width="70" align="center" />
            <el-table-column label="小计" width="90" align="center">
              <template #default="{ row }">
                ¥{{ money(row.lineAmountCents || row.amountCents || 0) }}
              </template>
            </el-table-column>
          </el-table>

          <h4 class="section-title">时间线</h4>
          <el-timeline class="order-timeline">
            <el-timeline-item :timestamp="formatDateTime(detail.createdAt)" type="primary"
              >下单</el-timeline-item
            >
            <el-timeline-item
              v-if="detail.paidAt || detail.status === 'PAID' || detail.status === 'COMPLETED'"
              :timestamp="formatDateTime(detail.paidAt || detail.updatedAt || detail.createdAt)"
              type="success"
              >扣款完成</el-timeline-item
            >
            <el-timeline-item
              v-if="detail.status === 'REFUNDED'"
              :timestamp="formatDateTime(detail.updatedAt)"
              type="warning"
              >已退款</el-timeline-item
            >
            <el-timeline-item
              v-if="detail.sessionId"
              :timestamp="formatDateTime(detail.createdAt)"
              type="info"
              >关联会话 {{ detail.sessionId }}</el-timeline-item
            >
          </el-timeline>
        </template>
      </div>
    </el-drawer>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import {
  CopyDocument,
  Link,
  Refresh,
  VideoCamera,
  View,
  Wallet,
  Bell,
  CircleClose,
  Coin
} from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { dictLabel, dictOptions } from '@aicabinet/shared-dict';
import { api, downloadAuthFile } from '@/api/client';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import PagePager from '@/components/PagePager.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useNavAccess } from '@/composables/useNavAccess';
import { useSessionVideo } from '@/composables/useSessionVideo';
import { useTableSelection } from '@/composables/useTableSelection';
import { useAuthStore } from '@/stores/auth';
import type { OrderSummary, PageResult } from '@aicabinet/shared-types';
import { formatDateTime } from '@aicabinet/shared-uni/format';
import { csvFileName } from '@/utils/csv';
import { useIdColumnSort } from '@/composables/useIdColumnSort';

const UNPAID_OVERDUE_MS = 30 * 60 * 1000;

type GoodsLine = { title: string; qty: string };

function parseGoodsLines(summary: string | null | undefined): GoodsLine[] {
  if (!summary?.trim()) return [];
  const base = summary.replace(/\s*等\d+种\s*$/, '').trim();
  if (!base) return [];
  return base
    .split('、')
    .map((part) => {
      const raw = part.trim();
      // 后端摘要形如：可口可乐 330ml x1 @L07-...；列表只展示名称与数量
      const m = raw.match(/^(.*?)\s+x(\d+)(?:\s+@\S+)?$/i);
      if (m) return { title: m[1].trim(), qty: m[2] };
      return { title: raw.replace(/\s+@\S+$/, '').trim(), qty: '' };
    })
    .filter((g) => g.title);
}

function goodsDisplay(row: OrderSummary) {
  const summary = row.lineSummary || '';
  const extraMatch = summary.match(/等(\d+)种\s*$/);
  return {
    lines: parseGoodsLines(summary),
    extraKinds: extraMatch ? Number(extraMatch[1]) : null,
    total: row.lineCount ?? 0
  };
}

const route = useRoute();
const { router, goPath } = useNavAccess();
const { playSessionVideo } = useSessionVideo();
const auth = useAuthStore();
const loading = ref(false);
const listHydrated = ref(false);
const videoLoading = ref(false);
const refundingId = ref('');
const keyword = ref('');
const payChannel = ref('');
const createdRange = ref<[string, string] | null>(null);
const status = ref('');
const statusTab = ref('ALL');
const overdueOnly = ref(false);
const focusOrderId = ref('');
const items = ref<OrderSummary[]>([]);

const {
  defaultSort: idDefaultSort,
  onSortChange: onIdSortChange,
  sortById
} = useIdColumnSort<OrderSummary>('orderId');
const page = ref(1);
const size = ref(20);
const total = ref(0);
const detailOpen = ref(false);
const detailLoading = ref(false);
const detail = ref<any>(null);

const displayItems = computed(() => {
  let list = [...items.value];
  if (statusTab.value === 'PENDING') {
    // overdueOnly: load() already scans; keep filter as safety net for mixed pages
    if (overdueOnly.value) {
      list = list.filter((row) => isUnpaidOverdue(row));
    }
    list.sort((a, b) => orderAgeMs(b.createdAt) - orderAgeMs(a.createdAt));
    return list;
  }
  return sortById(list);
});

const { onSelectionChange, pickSelected, exportButtonLabel, clearSelection } =
  useTableSelection<OrderSummary>((r) => r.orderId);

const { onExport: exportSelectedCsv } = useListCsv({
  filePrefix: '订单',
  headers: [
    '订单号',
    '会话',
    '用户ID',
    '设备',
    '流水号',
    '订单状态',
    '支付状态',
    '退款状态',
    '支付渠道',
    '扣库存',
    '商品摘要',
    '商品行',
    '金额',
    '创建时间'
  ],
  toRows: () =>
    pickSelected(items.value).map((row) => [
      row.orderId,
      row.sessionId,
      row.userId,
      row.deviceId,
      row.payTradeNo || row.paymentOperationId || '',
      dictLabel('order_status', row.status) || row.status,
      paymentStatusLabel(row.status),
      refundColumnLabel(row.status),
      dictLabel('pay_channel', row.payChannel) || row.payChannel || '未知渠道',
      row.inventoryDeducted ? '已扣' : '未扣',
      row.lineSummary || '',
      row.lineCount,
      money(row.totalAmountCents),
      formatDateTime(row.createdAt)
    ])
});

function appendOrderFilters(q: URLSearchParams) {
  if (keyword.value.trim()) q.set('q', keyword.value.trim());
  if (payChannel.value) q.set('payChannel', payChannel.value);
  if (createdRange.value?.length === 2) {
    const fromMs = Number(createdRange.value[0]);
    const toMs = Number(createdRange.value[1]);
    if (Number.isFinite(fromMs)) q.set('from', new Date(fromMs).toISOString());
    if (Number.isFinite(toMs)) q.set('to', new Date(toMs).toISOString());
  }
}

async function onExportMode(mode: string) {
  const selected = pickSelected(items.value);
  if (mode === 'orders' && selected.length && selected.length < items.value.length) {
    exportSelectedCsv();
    return;
  }
  try {
    const q = new URLSearchParams();
    appendOrderFilters(q);
    if (status.value) q.set('status', status.value);
    q.set('mode', mode === 'lines' ? 'lines' : 'orders');
    const qs = q.toString();
    await downloadAuthFile(
      `/api/v2/ops/admin/orders/export?${qs}`,
      csvFileName(mode === 'lines' ? '订单商品行' : '订单')
    );
    ElMessage.success('已导出');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '导出失败');
  }
}

function money(cents?: number) {
  return ((cents || 0) / 100).toFixed(2);
}

function orderStatusType(s?: string) {
  if (s === 'PAID' || s === 'COMPLETED') return 'success';
  if (s === 'CANCELLED' || s === 'REFUNDED' || s === 'PARTIAL_REFUNDED') return 'info';
  if (s === 'FAILED') return 'danger';
  if (s === 'DISPUTED') return 'warning';
  return 'warning';
}

function refundColumnLabel(s?: string) {
  if (s === 'REFUNDED') return '已退款';
  if (s === 'PARTIAL_REFUNDED') return '部分退款';
  return '无';
}

function refundTagType(s?: string) {
  if (s === 'REFUNDED' || s === 'PARTIAL_REFUNDED') return 'warning';
  return 'info';
}

function paymentStatusLabel(s?: string) {
  if (s === 'PAID' || s === 'COMPLETED') return '已支付';
  if (s === 'REFUNDED') return '已退款';
  if (s === 'PARTIAL_REFUNDED') return '部分退款';
  if (s === 'CANCELLED') return '已关闭';
  if (s === 'PENDING') return '待支付';
  if (s === 'DISPUTED') return '争议中';
  return s ? dictLabel('order_status', s) || s : '无';
}

function paymentStatusType(s?: string) {
  if (s === 'PAID' || s === 'COMPLETED') return 'success';
  if (s === 'PENDING' || s === 'DISPUTED') return 'warning';
  if (s === 'REFUNDED' || s === 'PARTIAL_REFUNDED') return 'info';
  return 'info';
}

function canRefund(s?: string) {
  return s === 'PAID' || s === 'COMPLETED' || s === 'DISPUTED';
}

function orderAgeMs(createdAt?: string) {
  if (!createdAt) return 0;
  const t = new Date(createdAt).getTime();
  if (Number.isNaN(t)) return 0;
  return Math.max(0, Date.now() - t);
}

function isUnpaidOverdue(row: OrderSummary) {
  return row.status === 'PENDING' && orderAgeMs(row.createdAt) >= UNPAID_OVERDUE_MS;
}

function formatOrderAge(createdAt?: string) {
  const ms = orderAgeMs(createdAt);
  if (!ms) return '无';
  const mins = Math.floor(ms / 60000);
  if (mins < 60) return `${mins} 分钟`;
  const hours = Math.floor(mins / 60);
  const rem = mins % 60;
  if (hours < 48) return rem ? `${hours} 小时 ${rem} 分` : `${hours} 小时`;
  const days = Math.floor(hours / 24);
  return `${days} 天`;
}

function orderRowClass({ row }: { row: OrderSummary }) {
  return isUnpaidOverdue(row) ? 'is-unpaid-overdue' : '';
}

function rowActions(row: OrderSummary): TableAction[] {
  const actions: TableAction[] = [{ key: 'detail', label: '详情', icon: View, type: 'primary' }];
  actions.push({ key: 'copy', label: '复制单号', icon: CopyDocument, overflow: true });
  if (row.sessionId) {
    actions.push({ key: 'session', label: '会话', icon: Link, overflow: true });
  }
  if (row.status === 'PENDING') {
    if (auth.hasPerm('ops:order:remind')) {
      actions.push({ key: 'remind', label: '催付', icon: Bell, type: 'warning' });
    }
    if (
      auth.hasPerm('ops:order:remind') ||
      auth.hasPerm('ops:order:cancel') ||
      auth.hasPerm('ops:order:refund')
    ) {
      actions.push({ key: 'collect', label: '补扣', icon: Coin, overflow: true });
    }
    if (auth.hasPerm('ops:order:cancel')) {
      actions.push({
        key: 'cancel',
        label: '关单',
        icon: CircleClose,
        type: 'danger',
        overflow: true
      });
    }
  }
  if (row.sessionId && (auth.hasPerm('ops:session:list') || auth.hasPerm('ops:session:upload'))) {
    actions.push({
      key: 'video',
      label: '录像',
      icon: VideoCamera,
      type: 'warning',
      overflow: true
    });
  }
  if (canRefund(row.status) && auth.hasPerm('ops:order:refund')) {
    actions.push({
      key: 'refund',
      label: '退款',
      icon: Wallet,
      type: 'danger',
      disabled: refundingId.value === row.orderId
    });
  }
  return actions;
}

function onRowAction(key: string, row: OrderSummary) {
  if (key === 'detail') openDetail(row);
  if (key === 'refund') refundOrder(row);
  if (key === 'video') playVideo(row.sessionId);
  if (key === 'copy') copyOrderId(row.orderId);
  if (key === 'session') goSessions(row.deviceId, row.sessionId);
  if (key === 'remind') remindOrder(row);
  if (key === 'cancel') cancelUnpaid(row);
  if (key === 'collect') collectUnpaid(row);
}

async function copyOrderId(orderId?: string) {
  const id = String(orderId || '').trim();
  if (!id) return;
  try {
    await navigator.clipboard.writeText(id);
    ElMessage.success('订单号已复制');
  } catch {
    ElMessage.warning(id);
  }
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
  goPath(`/devices/${encodeURIComponent(id)}`);
}

function goSessions(device?: string, sessionId?: string) {
  const query: Record<string, string> = {};
  if (device) query.deviceId = device;
  if (sessionId) query.sessionId = sessionId;
  goPath('/sessions', query);
}

async function openDetail(row: OrderSummary) {
  detailOpen.value = true;
  detailLoading.value = true;
  // 切换订单才清空，避免同单软刷新（退款后重拉）闪空白抽屉
  if (detail.value?.orderId !== row.orderId) detail.value = null;
  try {
    detail.value = await api.request(
      `/api/v2/ops/admin/orders/${encodeURIComponent(row.orderId)}`,
      'GET'
    );
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '详情加载失败');
    detailOpen.value = false;
  } finally {
    detailLoading.value = false;
  }
}

async function refundOrder(row: { orderId: string; status?: string }) {
  try {
    const { value } = await ElMessageBox.prompt(
      `确认原路退回订单 ${row.orderId} 的全部金额？`,
      '订单退款',
      {
        inputPlaceholder: '请填写退款原因（至少4字）',
        inputValidator: (v) =>
          (!!String(v || '').trim() && String(v).trim().length >= 4) || '请填写至少4字原因',
        confirmButtonText: '确认退款',
        type: 'warning'
      }
    );
    refundingId.value = row.orderId;
    const result = await api.request<{ message?: string; refundedCents?: number }>(
      `/api/v2/ops/admin/orders/${encodeURIComponent(row.orderId)}/refund`,
      'POST',
      { reason: String(value).trim() }
    );
    ElMessage.success(result.message || '退款成功');
    if (detailOpen.value && detail.value?.orderId === row.orderId) {
      await openDetail(row as OrderSummary);
    }
    await load();
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '退款失败');
    }
  } finally {
    refundingId.value = '';
  }
}

async function remindOrder(row: { orderId: string }) {
  try {
    await ElMessageBox.confirm(`向用户发送订单 ${row.orderId} 的催付提醒？`, '催付', {
      confirmButtonText: '发送催付',
      type: 'warning'
    });
    const result = await api.request<{ message?: string; notified?: boolean }>(
      `/api/v2/ops/admin/orders/${encodeURIComponent(row.orderId)}/remind`,
      'POST'
    );
    ElMessage.success(result.message || '催付已处理');
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '催付失败');
    }
  }
}

async function collectUnpaid(row: { orderId: string }) {
  try {
    await ElMessageBox.confirm(
      `确认对订单 ${row.orderId} 立即补扣？需用户余额/免密足够。`,
      '补扣收款',
      { confirmButtonText: '确认补扣', type: 'warning' }
    );
    await api.request(
      `/api/v2/ops/admin/orders/${encodeURIComponent(row.orderId)}/collect`,
      'POST'
    );
    ElMessage.success('补扣成功');
    if (detailOpen.value && detail.value?.orderId === row.orderId) {
      await openDetail(row as OrderSummary);
    }
    await load();
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '补扣失败');
    }
  }
}

async function cancelUnpaid(row: { orderId: string }) {
  try {
    const { value } = await ElMessageBox.prompt(
      `关闭待支付订单 ${row.orderId} 将回滚库存。可选同时拉黑用户。`,
      '关闭待支付',
      {
        inputPlaceholder: '关单原因（至少4字）',
        inputValidator: (v) =>
          (!!String(v || '').trim() && String(v).trim().length >= 4) || '请填写至少4字原因',
        confirmButtonText: '仅关单',
        distinguishCancelAndClose: true,
        type: 'warning'
      }
    );
    let blacklist = false;
    try {
      await ElMessageBox.confirm('是否同时拉黑该用户 30 天？', '风控联动', {
        confirmButtonText: '关单并拉黑',
        cancelButtonText: '仅关单',
        type: 'warning'
      });
      blacklist = true;
    } catch (inner: any) {
      if (inner !== 'cancel' && inner !== 'close') throw inner;
    }
    const result = await api.request<{ message?: string }>(
      `/api/v2/ops/admin/orders/${encodeURIComponent(row.orderId)}/cancel`,
      'POST',
      { reason: String(value).trim(), blacklist }
    );
    ElMessage.success(result.message || '已关单');
    detailOpen.value = false;
    await load();
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '关单失败');
    }
  }
}

function syncRouteQuery() {
  const query: Record<string, string> = {};
  if (keyword.value.trim()) query.keyword = keyword.value.trim();
  if (payChannel.value) query.payChannel = payChannel.value;
  if (status.value) query.status = status.value;
  if (statusTab.value === 'PENDING' && overdueOnly.value) query.overdue = '1';
  if (focusOrderId.value) query.orderId = focusOrderId.value;
  router.replace({ query });
}

function onStatusTab(name: string | number) {
  const tab = String(name);
  statusTab.value = tab;
  status.value = tab === 'ALL' ? '' : tab;
  if (tab !== 'PENDING') overdueOnly.value = false;
  page.value = 1;
  syncRouteQuery();
  load();
}

function onOverdueToggle() {
  page.value = 1;
  syncRouteQuery();
  load();
}

async function load() {
  loading.value = true;
  try {
    const q = new URLSearchParams({
      page: String(page.value - 1),
      size: String(size.value)
    });
    appendOrderFilters(q);
    if (overdueOnly.value && statusTab.value === 'PENDING') {
      q.set('status', 'PENDING');
      q.set('overdue', '1');
    } else if (status.value) {
      q.set('status', status.value);
    }
    const data = await api.request<PageResult<OrderSummary>>(
      `/api/v2/ops/admin/orders?${q}`,
      'GET'
    );
    items.value = data.items || [];
    total.value = data.total || 0;
    clearSelection();
    await maybeOpenFocusedOrder();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    listHydrated.value = true;
    loading.value = false;
  }
}

function search() {
  page.value = 1;
  syncRouteQuery();
  load();
}
function reset() {
  keyword.value = '';
  payChannel.value = '';
  createdRange.value = null;
  status.value = '';
  statusTab.value = 'ALL';
  overdueOnly.value = false;
  focusOrderId.value = '';
  page.value = 1;
  syncRouteQuery();
  load();
}
function onSizeChange() {
  page.value = 1;
  load();
}

function applyRouteQuery() {
  let changed = false;
  const routeKeyword =
    typeof route.query.keyword === 'string'
      ? route.query.keyword
      : typeof route.query.q === 'string'
        ? route.query.q
        : typeof route.query.deviceId === 'string'
          ? route.query.deviceId
          : typeof route.query.qOrderId === 'string'
            ? route.query.qOrderId
            : typeof route.query.qSessionId === 'string'
              ? route.query.qSessionId
              : typeof route.query.userId === 'string'
                ? route.query.userId
                : typeof route.query.payTradeNo === 'string'
                  ? route.query.payTradeNo
                  : '';
  if (routeKeyword !== keyword.value) {
    keyword.value = routeKeyword;
    changed = true;
  }
  if (typeof route.query.payChannel === 'string' && route.query.payChannel !== payChannel.value) {
    payChannel.value = route.query.payChannel;
    changed = true;
  } else if (!route.query.payChannel && payChannel.value) {
    payChannel.value = '';
    changed = true;
  }
  if (typeof route.query.status === 'string' && route.query.status !== status.value) {
    status.value = route.query.status;
    statusTab.value = route.query.status || 'ALL';
    changed = true;
  } else if (!route.query.status && (statusTab.value !== 'ALL' || status.value)) {
    statusTab.value = 'ALL';
    status.value = '';
    if (overdueOnly.value) {
      overdueOnly.value = false;
    }
    changed = true;
  }
  const wantOverdue = route.query.overdue === '1' || route.query.overdue === 'true';
  if (statusTab.value === 'PENDING') {
    if (wantOverdue !== overdueOnly.value) {
      overdueOnly.value = wantOverdue;
      changed = true;
    }
  } else if (overdueOnly.value) {
    overdueOnly.value = false;
    changed = true;
  }
  if (typeof route.query.orderId === 'string') {
    if (route.query.orderId !== focusOrderId.value) {
      focusOrderId.value = route.query.orderId;
      changed = true;
    }
  } else if (focusOrderId.value) {
    focusOrderId.value = '';
    changed = true;
  }
  return changed;
}

async function maybeOpenFocusedOrder() {
  const oid = focusOrderId.value.trim();
  if (!oid) return;
  const hit = items.value.find((r) => r.orderId === oid);
  if (hit) {
    await openDetail(hit);
    return;
  }
  try {
    detailOpen.value = true;
    detailLoading.value = true;
    if (detail.value?.orderId !== oid) detail.value = null;
    detail.value = await api.request(`/api/v2/ops/admin/orders/${encodeURIComponent(oid)}`, 'GET');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '订单详情加载失败');
    detailOpen.value = false;
  } finally {
    detailLoading.value = false;
  }
}

onMounted(() => {
  applyRouteQuery();
  load();
});

async function reloadFromRouteQuery() {
  if (!applyRouteQuery()) return;
  page.value = 1;
  await load();
}

watch(
  () =>
    [
      route.query.keyword,
      route.query.q,
      route.query.deviceId,
      route.query.status,
      route.query.overdue,
      route.query.orderId
    ] as const,
  () => {
    void reloadFromRouteQuery();
  }
);

onActivated(() => {
  void reloadFromRouteQuery();
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
.goods-cell {
  display: grid;
  gap: 4px;
  width: 100%;
  text-align: center;
  justify-items: center;
  line-height: 1.35;
}
.goods-line {
  display: inline-flex;
  align-items: baseline;
  justify-content: center;
  gap: 6px;
  min-width: 0;
  max-width: 100%;
}
.goods-name {
  font-weight: 600;
  color: var(--layout-text, var(--el-text-color-primary));
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}
.goods-qty {
  flex: 0 0 auto;
  font-variant-numeric: tabular-nums;
  color: var(--el-text-color-regular);
  font-size: 12px;
}
.goods-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.muted {
  color: var(--el-text-color-secondary);
}
.chase-banner {
  margin: 0 0 10px;
}
.is-overdue-age {
  color: var(--el-color-danger);
  font-weight: 600;
}
:deep(.el-table .is-unpaid-overdue > td.el-table__cell) {
  background: color-mix(
    in srgb,
    var(--el-color-warning) 8%,
    var(--el-table-bg-color, #fff)
  ) !important;
}
.status-tabs {
  margin: 0 0 8px;
}
.export-caret {
  opacity: 0.7;
  margin-left: 2px;
}
.section-title {
  margin: 16px 0 8px;
  font-size: 14px;
}
.drawer-actions {
  margin-top: 12px;
}
.order-timeline {
  margin-top: 8px;
}
</style>
