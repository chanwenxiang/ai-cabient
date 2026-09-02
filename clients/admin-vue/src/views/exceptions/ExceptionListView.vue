<template>
  <div class="page-fill">
    <el-card shadow="never" class="page-card report-page">
      <template #header>
        <div class="page-card-head">
          <div class="page-card-head__meta">
            <div class="page-card-head__title">
              <span class="title">异常中心</span>
              <span class="hint">交易履约异常（录像结算）；设备离线/锁机等请看「设备运维」</span>
            </div>
          </div>
          <div class="page-card-head__actions">
            <el-button v-if="canAccessPath('/device-ops')" @click="goPath('/device-ops')"
              >设备运维</el-button
            >
            <el-button v-hasPermi="['ops:exception:export']" @click="onExport">{{
              exportButtonLabel
            }}</el-button>
            <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
          </div>
        </div>
      </template>

      <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
        <el-form-item label="级别">
          <el-select
            v-model="severity"
            clearable
            placeholder="全部"
            style="width: 120px"
            @change="search"
          >
            <el-option
              v-for="item in dictOptions('exception_severity')"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="时限">
          <el-checkbox v-model="overdueOnly" @change="onOverdueToggle">仅超时</el-checkbox>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="search">查询</el-button>
          <el-button @click="reset">重置</el-button>
        </el-form-item>
      </el-form>

      <el-alert
        v-if="listHydrated && (overdueOnly ? total > 0 : pageOverdueCount > 0)"
        type="error"
        :closable="false"
        show-icon
        class="sla-banner"
        :title="
          overdueOnly
            ? `共 ${total} 条已超时（服务端过滤，分页准确）`
            : `本页 ${pageOverdueCount} 条已超时，可勾选「仅超时」查看全部`
        "
      />

      <el-tabs v-model="status" class="status-tabs" @tab-change="onStatusTab">
        <el-tab-pane
          v-for="item in statusTabOptions"
          :key="item.value"
          :label="statusTabLabel(item.label, item.value)"
          :name="item.value"
        />
        <el-tab-pane :label="archivedTabLabel" name="ARCHIVED" />
        <el-tab-pane label="全部" name="ALL" />
      </el-tabs>

      <div class="table-scroll">
        <div class="table-scroll-inner">
          <el-table
            v-loading="loading"
            :data="displayItems"
            stripe
            border
            class="report-table"
            empty-text=" "
            :row-class-name="rowClassName"
            row-key="exceptionId"
            :default-sort="idDefaultSort"
            @sort-change="onIdSortChange"
            @selection-change="onSelectionChange"
          >
            <template #empty>
              <el-empty v-if="listHydrated && !loading" :description="emptyHint" />
            </template>
            <el-table-column type="selection" width="48" align="center" />
            <el-table-column
              prop="exceptionId"
              label="异常编号"
              min-width="140"
              align="center"
              class-name="col-text"
              show-overflow-tooltip
              sortable="custom"
            >
              <template #default="{ row }">
                <span class="cell-id">{{ displayBizNo(row.exceptionId) }}</span>
              </template>
            </el-table-column>
            <el-table-column
              label="异常"
              min-width="160"
              align="center"
              class-name="col-text"
              show-overflow-tooltip
            >
              <template #default="{ row }">
                <button type="button" class="link-cell" @click="openDetail(row)">
                  {{ row.title || displayLabel('exception_type', row.exceptionType, '暂无') }}
                </button>
              </template>
            </el-table-column>
            <el-table-column label="级别" width="80" align="center">
              <template #default="{ row }">
                <el-tag :type="dictTagType(row.severity)" size="small">
                  {{ dictLabel('exception_severity', row.severity) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column
              label="类型"
              min-width="120"
              align="center"
              class-name="col-text"
              show-overflow-tooltip
            >
              <template #default="{ row }">{{
                dictLabel('exception_type', row.exceptionType)
              }}</template>
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
                  {{ displayBizNo(row.sessionId) }}
                </button>
                <span v-else class="muted" :title="emptyRefHint(row)">{{
                  emptyRefLabel(row)
                }}</span>
              </template>
            </el-table-column>
            <el-table-column
              label="订单"
              min-width="120"
              align="center"
              class-name="col-text"
              show-overflow-tooltip
            >
              <template #default="{ row }">
                <button
                  v-if="row.orderId"
                  type="button"
                  class="link-cell mono"
                  @click="goOrders(row.deviceId)"
                >
                  {{ displayBizNo(row.orderId) }}
                </button>
                <span v-else class="muted" :title="emptyRefHint(row)">{{
                  emptyRefLabel(row)
                }}</span>
              </template>
            </el-table-column>
            <el-table-column label="用户" width="88" align="center">
              <template #default="{ row }">
                <span v-if="row.userId">{{ row.userId }}</span>
                <span v-else class="muted" :title="emptyRefHint(row)">{{
                  emptyRefLabel(row)
                }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="92" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.archived" type="info" size="small">已归档</el-tag>
                <el-tag v-else :type="dictTagType(row.status)" size="small">
                  {{ dictLabel('exception_status', row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="处理时限" min-width="168" align="center">
              <template #default="{ row }">
                <div class="sla-cell">
                  <template v-if="row.slaOverdue">
                    <el-tag type="danger" size="small">已超时</el-tag>
                    <small class="sla-meta danger"
                      >超 {{ formatDurationSince(row.slaDueAt) }}</small
                    >
                  </template>
                  <template v-else-if="isSlaPastDue(row.slaDueAt)">
                    <el-tag type="info" size="small">时限已过</el-tag>
                    <span class="cell-datetime">{{ formatDateTime(row.slaDueAt) }}</span>
                    <small class="sla-meta danger"
                      >超 {{ formatDurationSince(row.slaDueAt) }}</small
                    >
                  </template>
                  <template v-else-if="row.slaDueAt">
                    <el-tag v-if="isSlaDueSoon(row.slaDueAt)" type="warning" size="small"
                      >即将到期</el-tag
                    >
                    <span class="cell-datetime">{{ formatDateTime(row.slaDueAt) }}</span>
                    <small class="sla-meta">剩 {{ formatDurationUntil(row.slaDueAt) }}</small>
                  </template>
                  <span v-else class="muted">无</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="负责人" width="88" align="center">
              <template #default="{ row }">{{ row.assigneeUserId || '未领取' }}</template>
            </el-table-column>
            <el-table-column label="创建时间" width="160" align="center">
              <template #default="{ row }">
                <span class="cell-datetime">{{ formatDateTime(row.createdAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column
              label="操作"
              width="220"
              class-name="col-action"
              align="center"
              fixed="right"
            >
              <template #default="{ row }">
                <TableActions
                  :actions="exceptionActions(row)"
                  @action="(key) => onExceptionAction(key, row)"
                />
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
    </el-card>

    <ResizableDrawer
      v-if="drawer"
      v-model="drawer"
      title="异常审单工作台"
      storage-key="admin.drawer.exceptions.workbench"
      :default-width="880"
      :min-width="560"
      :max-width="1400"
      append-to-body
      destroy-on-close
      class="exception-workbench drawer-workbench"
      @closed="onDrawerClosed"
    >
      <div v-loading="detailLoading" class="exception-drawer-body">
        <template v-if="detail">
          <el-alert
            v-if="resolveFeedback"
            type="success"
            title="已处理"
            :description="resolveFeedback"
            show-icon
            :closable="false"
            class="resolve-feedback"
          />

          <div class="workbench-grid">
            <section class="workbench-media">
              <div class="items-title">会话录像</div>
              <div v-if="inlineVideoUrl" class="review-video-wrap">
                <video
                  class="review-video"
                  :src="inlineVideoUrl"
                  controls
                  playsinline
                  preload="metadata"
                >
                  <track
                    kind="captions"
                    srclang="zh"
                    label="现场录像无对白字幕"
                    src="data:text/vtt,WEBVTT"
                  />
                  <track
                    kind="descriptions"
                    srclang="zh"
                    label="异常复核监控录像"
                    src="data:text/vtt,WEBVTT"
                  />
                </video>
              </div>
              <el-empty
                v-else-if="videoAttempted && !videoLoading && detail.exception.sessionId"
                description="暂无录像或加载失败"
                :image-size="72"
              />
              <el-empty
                v-else-if="!detail.exception.sessionId"
                description="无关联会话"
                :image-size="72"
              />
              <div v-else-if="videoLoading" class="video-loading">录像加载中…</div>
              <div v-else class="video-loading muted">尚未加载录像</div>
              <div v-if="detail.exception.sessionId" class="drawer-actions drawer-actions--tight">
                <el-button
                  v-if="auth.hasPerm('ops:session:list') || auth.hasPerm('ops:session:upload')"
                  type="warning"
                  :loading="videoLoading"
                  @click="loadInlineVideo(detail.exception.sessionId, true)"
                  >{{ inlineVideoUrl ? '重新加载录像' : '加载会话录像' }}</el-button
                >
                <el-button
                  v-if="inlineVideoUrl"
                  link
                  type="primary"
                  @click="playVideo(detail.exception.sessionId)"
                  >新窗口打开</el-button
                >
                <el-button
                  @click="goSessions(detail.exception.deviceId, detail.exception.sessionId)"
                  >开门记录</el-button
                >
                <el-button
                  v-if="detail.exception.sessionId && canAccessPath('/disputes')"
                  @click="goDisputes(detail.exception.sessionId)"
                  >打开争议审单</el-button
                >
              </div>
              <el-alert
                v-if="inlineVideoError"
                type="warning"
                :closable="false"
                show-icon
                :title="inlineVideoError"
                class="suggest-alert"
              />
            </section>

            <section class="workbench-meta">
              <el-descriptions :column="1" border size="small">
                <el-descriptions-item label="异常编号">
                  <span class="cell-id">{{ displayBizNo(detail.exception.exceptionId) }}</span>
                </el-descriptions-item>
                <el-descriptions-item label="异常类型">
                  <el-tag type="info" size="small">{{
                    dictLabel('exception_type', detail.exception.exceptionType)
                  }}</el-tag>
                </el-descriptions-item>
                <el-descriptions-item label="严重级别">
                  <el-tag :type="dictTagType(detail.exception.severity)" size="small">
                    {{ dictLabel('exception_severity', detail.exception.severity) }}
                  </el-tag>
                </el-descriptions-item>
                <el-descriptions-item label="处理状态">
                  <el-tag :type="dictTagType(detail.exception.status)" size="small">
                    {{ dictLabel('exception_status', detail.exception.status) }}
                  </el-tag>
                </el-descriptions-item>
                <el-descriptions-item label="异常内容">{{
                  detail.exception.title
                }}</el-descriptions-item>
                <el-descriptions-item label="详细信息">{{
                  formatExceptionDetail(detail.exception.detail)
                }}</el-descriptions-item>
                <el-descriptions-item label="关联设备">
                  <button
                    v-if="detail.exception.deviceId"
                    type="button"
                    class="link-cell"
                    @click="goDevice(detail.exception.deviceId)"
                  >
                    {{ detail.exception.deviceId }}
                  </button>
                  <span v-else class="muted">暂无</span>
                </el-descriptions-item>
                <el-descriptions-item label="关联会话">
                  <button
                    v-if="detail.exception.sessionId"
                    type="button"
                    class="link-cell mono"
                    @click="goSessions(detail.exception.deviceId, detail.exception.sessionId)"
                  >
                    {{ detail.exception.sessionId }}
                  </button>
                  <span v-else class="muted">暂无</span>
                </el-descriptions-item>
                <el-descriptions-item label="关联订单">
                  <button
                    v-if="detail.exception.orderId"
                    type="button"
                    class="link-cell mono"
                    @click="goOrders(detail.exception.deviceId)"
                  >
                    {{ detail.exception.orderId }}
                  </button>
                  <span v-else class="muted">暂无</span>
                </el-descriptions-item>
                <el-descriptions-item label="时限截止">
                  <div class="sla-cell">
                    <template v-if="detail.exception.slaOverdue">
                      <el-tag type="danger" size="small">已超时</el-tag>
                      <small class="sla-meta danger"
                        >超 {{ formatDurationSince(detail.exception.slaDueAt) }}</small
                      >
                    </template>
                    <template v-else-if="isSlaPastDue(detail.exception.slaDueAt)">
                      <el-tag type="info" size="small">时限已过</el-tag>
                      <span>{{ formatDateTime(detail.exception.slaDueAt) }}</span>
                      <small class="sla-meta danger"
                        >超 {{ formatDurationSince(detail.exception.slaDueAt) }}</small
                      >
                    </template>
                    <template v-else-if="detail.exception.slaDueAt">
                      <el-tag
                        v-if="isSlaDueSoon(detail.exception.slaDueAt)"
                        type="warning"
                        size="small"
                        >即将到期</el-tag
                      >
                      <span>{{ formatDateTime(detail.exception.slaDueAt) }}</span>
                      <small class="sla-meta"
                        >剩 {{ formatDurationUntil(detail.exception.slaDueAt) }}</small
                      >
                    </template>
                    <span v-else class="muted">暂无</span>
                  </div>
                </el-descriptions-item>
              </el-descriptions>
            </section>
          </div>

          <div
            v-if="
              canHandle &&
              detail.exception.status !== 'RESOLVED' &&
              canManualSettle &&
              canManualResolve(detail.exception)
            "
            class="adjust-block"
          >
            <div class="items-title">调整明细（落账依据）</div>
            <el-alert
              type="info"
              :closable="false"
              show-icon
              title="对照左侧录像修改 SKU / 数量后，用「按调整明细落账」写回账单；也可免单退款。"
              class="suggest-alert"
            />
            <div class="manual-lines">
              <div v-for="(line, index) in manualLines" :key="index" class="manual-line">
                <el-select v-model="line.skuId" filterable placeholder="选择商品" style="flex: 1">
                  <el-option
                    v-for="sku in skus"
                    :key="sku.skuId"
                    :label="`${sku.skuName}（¥${(sku.priceCents / 100).toFixed(2)}）`"
                    :value="sku.skuId"
                  />
                </el-select>
                <el-input-number v-model="line.quantity" :min="1" :max="99" />
                <el-button type="danger" link @click="removeManualLine(index)">删除</el-button>
              </div>
              <el-button @click="manualLines.push({ skuId: '', quantity: 1 })">添加商品</el-button>
            </div>
            <el-input
              v-model="manualReason"
              type="textarea"
              :rows="2"
              maxlength="500"
              show-word-limit
              placeholder="必须填写判断依据和处理原因"
              class="manual-reason"
            />
            <div class="drawer-actions">
              <el-button
                type="success"
                :loading="manualSubmitting"
                :disabled="!manualConfirmItems.length"
                @click="submitManualResolve"
                >按调整明细落账</el-button
              >
              <el-button type="danger" plain :loading="manualSubmitting" @click="waiveOrder"
                >免单/全额退回</el-button
              >
            </div>
          </div>

          <div v-if="canHandle && detail.exception.status !== 'RESOLVED'" class="drawer-actions">
            <el-button type="primary" @click="addNote">添加备注</el-button>
            <el-button @click="transfer">转派</el-button>
            <el-button
              v-if="canResolveWithRepair(detail.exception)"
              type="success"
              :loading="repairResolving"
              @click="resolveWithRepair"
              >建维修工单并结案</el-button
            >
            <el-button v-if="canRetry(detail.exception)" type="warning" @click="retryException"
              >重试识别/结算</el-button
            >
            <el-button
              v-if="detail.exception.sessionId && auth.hasPerm('ops:session:cancel')"
              type="danger"
              @click="cancelSession"
              >取消会话并释放设备</el-button
            >
          </div>

          <h3 class="section-title">处理记录</h3>
          <el-timeline>
            <el-timeline-item
              v-for="action in detail.actions"
              :key="action.actionId"
              :timestamp="formatDateTime(action.createdAt)"
            >
              <strong>{{ auditActionLabel(action.action) }}</strong>
              · 操作人 {{ actorDisplayName({ operatorId: action.operatorId }) }}
              <div class="action-detail">{{ formatOpsActionDetail(action.detail) }}</div>
            </el-timeline-item>
          </el-timeline>
        </template>
      </div>
    </ResizableDrawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import {
  CircleCheck,
  FolderOpened,
  Monitor,
  Refresh,
  UserFilled,
  VideoCamera,
  View
} from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import PagePager from '@/components/PagePager.vue';
import ResizableDrawer from '@/components/ResizableDrawer.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useNavAccess } from '@/composables/useNavAccess';
import { useSessionVideo } from '@/composables/useSessionVideo';
import { useTableSelection } from '@/composables/useTableSelection';
import { useAuthStore } from '@/stores/auth';
import {
  actorDisplayName,
  auditActionLabel,
  dictLabel,
  dictOptions,
  dictTagType,
  displayLabel,
  formatExceptionDetail,
  formatOpsActionDetail
} from '@aicabinet/shared-dict';
import type { PageResult } from '@aicabinet/shared-types';
import { displayBizNo, formatDateTime } from '@aicabinet/shared-uni/format';
import { useDictOptions } from '@/composables/useDictOptions';
import { useIdColumnSort } from '@/composables/useIdColumnSort';

const route = useRoute();
const router = useRouter();
const { canAccessPath, goPath } = useNavAccess();
const { playSessionVideo, fetchSessionVideoBlob } = useSessionVideo();
const auth = useAuthStore();
const canHandle = computed(() => auth.hasPerm('ops:exception:handle'));
/** 与后端 manual-resolve 一致：需同时具备争议解决权限 */
const canManualSettle = computed(() => canHandle.value && auth.hasPerm('ops:dispute:resolve'));

interface OpsException {
  exceptionId: string;
  exceptionType: string;
  severity: string;
  status: string;
  title: string;
  detail?: string;
  deviceId?: string;
  sessionId?: string;
  orderId?: string;
  userId?: number;
  assigneeUserId?: number;
  archived?: boolean;
  archivedAt?: string;
  createdAt?: string;
  updatedAt?: string;
  slaDueAt?: string;
  slaOverdue?: boolean;
}
interface OpsAction {
  actionId: number;
  operatorId: number;
  action: string;
  detail?: string;
  createdAt: string;
}
interface OpsDetail {
  exception: OpsException;
  actions: OpsAction[];
}
interface Sku {
  skuId: string;
  skuName: string;
  priceCents: number;
}

const loading = ref(false);
const videoLoading = ref(false);
const videoAttempted = ref(false);
const inlineVideoUrl = ref('');
const inlineVideoError = ref('');
let inlineVideoRevoke: (() => void) | null = null;
const status = ref('OPEN');
const statusTabOptions = useDictOptions('exception_status');
const severity = ref('');
const overdueOnly = ref(false);
const page = ref(1);
const size = ref(20);
const total = ref(0);
const items = ref<OpsException[]>([]);
const { idDefaultSort, onIdSortChange, sortById } = useIdColumnSort('exceptionId');
const statusCounts = reactive({ OPEN: 0, PROCESSING: 0, RESOLVED: 0, CLOSED: 0, ARCHIVED: 0 });
/** 首屏未完成加载前不展示「0 / 暂无」，避免与工作台计数短暂不一致 */
const listHydrated = ref(false);
const drawer = ref(false);
const detailLoading = ref(false);
const detail = ref<OpsDetail | null>(null);
const repairResolving = ref(false);
const manualSubmitting = ref(false);
const manualReason = ref('');
const manualLines = ref<{ skuId: string; quantity: number }[]>([{ skuId: '', quantity: 1 }]);
const skus = ref<Sku[]>([]);
const resolveFeedback = ref('');

const manualConfirmItems = computed(() =>
  manualLines.value.filter((line) => line.skuId && line.quantity > 0)
);

const { onSelectionChange, pickSelected, exportButtonLabel, clearSelection } =
  useTableSelection<OpsException>((r) => r.exceptionId);

const displayItems = computed(() => sortById(items.value, 'exceptionId'));

const pageOverdueCount = computed(() => items.value.filter((r) => r.slaOverdue).length);

const { onExport } = useListCsv({
  filePrefix: '异常',
  headers: [
    '异常编号',
    '级别',
    '类型',
    '异常',
    '设备',
    '会话',
    '订单',
    '用户',
    '状态',
    '时限截止',
    '超时',
    '负责人',
    '创建时间'
  ],
  toRows: () =>
    pickSelected(displayItems.value).map((row) => [
      row.exceptionId,
      dictLabel('exception_severity', row.severity),
      dictLabel('exception_type', row.exceptionType),
      row.title,
      row.deviceId,
      row.sessionId,
      row.orderId,
      row.userId,
      dictLabel('exception_status', row.status),
      formatDateTime(row.slaDueAt),
      row.slaOverdue ? '是' : '否',
      row.assigneeUserId || '未领取',
      formatDateTime(row.createdAt)
    ])
});

const emptyHint = computed(() => {
  if (overdueOnly.value) return '当前筛选下无超时异常，可关闭「仅超时」或切换状态';
  if (status.value === 'ARCHIVED') return '暂无归档异常';
  return status.value === 'OPEN' ? '当前无待处理异常，可切换「全部」查看历史' : '暂无异常';
});

function statusTabLabel(label: string, value: string) {
  if (!listHydrated.value) return `${label} (…)`;
  const key = value as keyof typeof statusCounts;
  return `${label} (${statusCounts[key] || 0})`;
}

const archivedTabLabel = computed(() => {
  if (!listHydrated.value) return '已归档 (…)';
  return `已归档 (${statusCounts.ARCHIVED || 0})`;
});

const DUE_SOON_MS = 2 * 60 * 60 * 1000;

function isSlaDueSoon(dueAt?: string) {
  if (!dueAt) return false;
  const due = Date.parse(dueAt);
  if (Number.isNaN(due)) return false;
  const left = due - Date.now();
  return left > 0 && left <= DUE_SOON_MS;
}

/** 截止时间已过（含已结案：后端此时 slaOverdue=false，需前端单独识别） */
function isSlaPastDue(dueAt?: string) {
  if (!dueAt) return false;
  const due = Date.parse(dueAt);
  if (Number.isNaN(due)) return false;
  return Date.now() > due;
}

function formatDurationParts(ms: number) {
  const abs = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(abs / 3600);
  const m = Math.floor((abs % 3600) / 60);
  if (h >= 48) return `${Math.floor(h / 24)} 天`;
  if (h > 0) return `${h} 小时 ${m} 分`;
  if (m > 0) return `${m} 分钟`;
  return '不到 1 分钟';
}

function formatDurationUntil(dueAt?: string) {
  if (!dueAt) return '无';
  const due = Date.parse(dueAt);
  if (Number.isNaN(due)) return '无';
  const left = due - Date.now();
  if (left <= 0) return '已过期';
  return formatDurationParts(left);
}

function formatDurationSince(dueAt?: string) {
  if (!dueAt) return '无';
  const due = Date.parse(dueAt);
  if (Number.isNaN(due)) return '无';
  return formatDurationParts(Date.now() - due);
}

function rowClassName({ row }: { row: OpsException }) {
  if (row.slaOverdue) return 'is-overdue';
  if (isSlaDueSoon(row.slaDueAt)) return 'is-due-soon';
  return '';
}

/** 设备类异常本身无会话/订单，空值展示「无」避免误以为丢字段 */
function isDeviceScopedException(row: OpsException) {
  const t = String(row.exceptionType || '').toUpperCase();
  return t === 'DEVICE_FAULT' || t === 'DEVICE_OFFLINE' || t === 'DOOR_OPEN_TOO_LONG';
}

function emptyRefLabel(row: OpsException) {
  return '暂无';
}

function emptyRefHint(row: OpsException) {
  return isDeviceScopedException(row) ? '设备类异常无关联会话/订单/用户' : '暂无关联数据';
}

function pushOpenExceptionActions(acts: TableAction[], row: OpsException) {
  if (canHandle.value && row.status === 'OPEN') {
    acts.push({ key: 'claim', label: '领取', icon: UserFilled, type: 'primary' });
  }
}

function pushResolveExceptionAction(acts: TableAction[], row: OpsException) {
  if (!canHandle.value || row.status === 'RESOLVED') return;
  if (canResolveWithRepair(row)) {
    acts.push({
      key: 'repair',
      label: '建工单结案',
      icon: CircleCheck,
      type: 'success',
      overflow: true
    });
    return;
  }
  acts.push({ key: 'resolve', label: '解决', icon: CircleCheck, type: 'success' });
}

function pushArchiveExceptionAction(acts: TableAction[], row: OpsException) {
  if (!canHandle.value || row.status !== 'RESOLVED') return;
  acts.push({
    key: row.archived ? 'unarchive' : 'archive',
    label: row.archived ? '取消归档' : '归档',
    icon: FolderOpened,
    overflow: true
  });
}

function exceptionActions(row: OpsException): TableAction[] {
  // 对齐库存健康：主区放详情/设备/处理，次要进「更多」
  const acts: TableAction[] = [{ key: 'detail', label: '详情', icon: View, type: 'primary' }];
  if (row.deviceId && canAccessPath('/devices')) {
    acts.push({ key: 'device', label: '设备', icon: Monitor });
  }
  pushOpenExceptionActions(acts, row);
  pushResolveExceptionAction(acts, row);
  pushArchiveExceptionAction(acts, row);
  if (row.sessionId && (auth.hasPerm('ops:session:list') || auth.hasPerm('ops:session:upload'))) {
    acts.push({ key: 'video', label: '录像', icon: VideoCamera, type: 'warning', overflow: true });
  }
  return acts;
}

function onExceptionAction(key: string, row: OpsException) {
  if (key === 'detail') openDetail(row);
  else if (key === 'device' && row.deviceId) goDevice(row.deviceId);
  else if (key === 'claim') claim(row);
  else if (key === 'resolve') resolve(row);
  else if (key === 'repair') resolveWithRepairRow(row);
  else if (key === 'archive') archiveRow(row);
  else if (key === 'unarchive') unarchiveRow(row);
  else if (key === 'video') playVideo(row.sessionId);
}

async function archiveRow(row: OpsException) {
  try {
    await ElMessageBox.confirm(
      '归档后将从默认列表隐藏，可在「已归档」中查看。确定归档该异常吗？',
      '归档异常',
      { type: 'warning', confirmButtonText: '归档', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  try {
    await api.request(`/api/v2/ops/admin/exceptions/${row.exceptionId}/archive`, 'POST');
    ElMessage.success('已归档');
    await load();
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '归档失败');
    }
  }
}

async function unarchiveRow(row: OpsException) {
  try {
    await ElMessageBox.confirm(
      '取消归档后，该异常将重新出现在「已解决」列表中。确定取消归档吗？',
      '取消归档',
      { type: 'warning', confirmButtonText: '取消归档', cancelButtonText: '返回' }
    );
  } catch {
    return;
  }
  try {
    await api.request(`/api/v2/ops/admin/exceptions/${row.exceptionId}/unarchive`, 'POST');
    ElMessage.success('已取消归档');
    await load();
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '取消归档失败');
    }
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

function clearInlineVideo() {
  if (inlineVideoRevoke) {
    inlineVideoRevoke();
    inlineVideoRevoke = null;
  }
  inlineVideoUrl.value = '';
  inlineVideoError.value = '';
}

function onDrawerClosed() {
  videoAttempted.value = false;
  clearInlineVideo();
  resolveFeedback.value = '';
  manualReason.value = '';
  manualLines.value = [{ skuId: '', quantity: 1 }];
}

async function removeManualLine(index: number) {
  try {
    await ElMessageBox.confirm('确定删除该商品行吗？', '删除商品', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    });
  } catch {
    return;
  }
  manualLines.value.splice(index, 1);
  if (!manualLines.value.length) {
    manualLines.value = [{ skuId: '', quantity: 1 }];
  }
}

async function loadInlineVideo(sessionId?: string, force = false) {
  if (!sessionId) return;
  if (inlineVideoUrl.value && !force) return;
  clearInlineVideo();
  videoLoading.value = true;
  try {
    const { url, revoke } = await fetchSessionVideoBlob(sessionId);
    inlineVideoUrl.value = url;
    inlineVideoRevoke = revoke;
  } catch (e) {
    inlineVideoError.value = e instanceof Error ? e.message : '播放失败';
    ElMessage.error(inlineVideoError.value);
  } finally {
    videoAttempted.value = true;
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
function goOrders(device?: string) {
  const query: Record<string, string> = {};
  if (device) query.deviceId = device;
  goPath('/orders', query);
}
function goDisputes(sessionId?: string) {
  if (!canAccessPath('/disputes')) {
    ElMessage.warning('无争议审核访问权限');
    return;
  }
  const query: Record<string, string> = { category: 'RECOGNITION' };
  if (sessionId) query.sessionId = sessionId;
  goPath('/disputes', query);
}

function syncRouteQuery() {
  const query: Record<string, string> = {};
  if (status.value === 'ARCHIVED') query.archived = '1';
  else if (status.value && status.value !== 'ALL') query.status = status.value;
  if (severity.value) query.severity = severity.value;
  if (overdueOnly.value) query.overdue = '1';
  router.replace({ query });
}

async function refreshStatusCounts() {
  const keys = ['OPEN', 'PROCESSING', 'RESOLVED', 'CLOSED'] as const;
  await Promise.all(
    keys.map(async (key) => {
      try {
        const q = new URLSearchParams({ page: '0', size: '1', status: key });
        if (severity.value) q.set('severity', severity.value);
        const data = await api.request<PageResult<OpsException>>(
          `/api/v2/ops/admin/exceptions?${q}`,
          'GET'
        );
        statusCounts[key] = data.total || 0;
      } catch {
        /* keep previous */
      }
    })
  );
  try {
    const q = new URLSearchParams({ page: '0', size: '1', archived: 'true' });
    const data = await api.request<PageResult<OpsException>>(
      `/api/v2/ops/admin/exceptions?${q}`,
      'GET'
    );
    statusCounts.ARCHIVED = data.total || 0;
  } catch {
    /* keep previous */
  }
}

function onStatusTab(name: string | number) {
  status.value = String(name);
  page.value = 1;
  syncRouteQuery();
  load();
}

async function load() {
  loading.value = true;
  try {
    const q = new URLSearchParams({ page: String(page.value - 1), size: String(size.value) });
    const apiStatus = status.value === 'ALL' ? '' : status.value;
    if (status.value === 'ARCHIVED') q.set('archived', 'true');
    if (apiStatus) q.set('status', apiStatus);
    if (severity.value) q.set('severity', severity.value);
    if (overdueOnly.value) q.set('overdue', '1');
    const data = await api.request<PageResult<OpsException>>(
      `/api/v2/ops/admin/exceptions?${q}`,
      'GET'
    );
    items.value = data.items || [];
    total.value = data.total || 0;
    if (!overdueOnly.value && apiStatus && apiStatus in statusCounts) {
      statusCounts[apiStatus as keyof typeof statusCounts] = data.total || 0;
    }
    clearSelection();
    await refreshStatusCounts();
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

function onOverdueToggle() {
  search();
}
function reset() {
  status.value = 'OPEN';
  severity.value = '';
  overdueOnly.value = false;
  page.value = 1;
  syncRouteQuery();
  load();
}
function onSizeChange() {
  page.value = 1;
  load();
}

async function claim(row: OpsException) {
  try {
    await api.request(`/api/v2/ops/admin/exceptions/${row.exceptionId}/claim`, 'POST');
    ElMessage.success('已领取');
    await load();
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '领取失败');
    }
  }
}
async function resolve(row: OpsException) {
  try {
    const { value } = await ElMessageBox.prompt('请填写处理结果，记录将进入审计日志', '解决异常', {
      inputValidator: (v) => !!String(v || '').trim() || '必须填写处理结果',
      confirmButtonText: '确认解决',
      cancelButtonText: '取消'
    });
    await api.request(`/api/v2/ops/admin/exceptions/${row.exceptionId}/resolve`, 'POST', {
      resolution: value
    });
    ElMessage.success('异常已解决');
    await load();
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '解决失败');
    }
  }
}
async function openDetail(row: OpsException) {
  clearInlineVideo();
  videoAttempted.value = false;
  resolveFeedback.value = '';
  manualReason.value = '';
  manualLines.value = [{ skuId: '', quantity: 1 }];
  // 切换异常才清空，同单 refreshDetail 保留内容 + loading 遮罩
  if (detail.value?.exception?.exceptionId !== row.exceptionId) detail.value = null;
  drawer.value = true;
  detailLoading.value = true;
  try {
    detail.value = await api.request<OpsDetail>(
      `/api/v2/ops/admin/exceptions/${row.exceptionId}`,
      'GET'
    );
    if (
      canManualSettle.value &&
      detail.value?.exception &&
      canManualResolve(detail.value.exception) &&
      !skus.value.length
    ) {
      skus.value =
        (await api.request<{ items: Sku[] }>('/api/v2/ops/admin/skus?page=0&size=500', 'GET'))
          .items || [];
    }
    const sid = detail.value?.exception?.sessionId;
    if (sid && (auth.hasPerm('ops:session:list') || auth.hasPerm('ops:session:upload'))) {
      void loadInlineVideo(sid);
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '详情加载失败');
  } finally {
    detailLoading.value = false;
  }
}
async function refreshDetail() {
  if (detail.value) await openDetail(detail.value.exception);
}
async function addNote() {
  if (!detail.value) return;
  try {
    const { value } = await ElMessageBox.prompt('请输入处理备注', '添加备注', {
      inputValidator: (v) => !!String(v || '').trim() || '备注不能为空'
    });
    await api.request(
      `/api/v2/ops/admin/exceptions/${detail.value.exception.exceptionId}/notes`,
      'POST',
      { note: value }
    );
    ElMessage.success('备注已记录');
    await refreshDetail();
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '添加备注失败');
    }
  }
}
async function transfer() {
  if (!detail.value) return;
  try {
    const { value } = await ElMessageBox.prompt('请输入接收人的用户 ID', '转派异常', {
      inputPattern: /^\d+$/,
      inputErrorMessage: '请输入有效用户 ID'
    });
    await api.request(
      `/api/v2/ops/admin/exceptions/${detail.value.exception.exceptionId}/transfer`,
      'POST',
      {
        assigneeUserId: Number(value),
        reason: '运营工作台转派'
      }
    );
    ElMessage.success('已转派');
    await Promise.all([load(), refreshDetail()]);
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '转派失败');
    }
  }
}
async function cancelSession() {
  if (!detail.value) return;
  const item = detail.value.exception;
  try {
    const { value } = await ElMessageBox.prompt(
      `将终止会话 ${item.sessionId} 并释放设备 ${item.deviceId || '无'}，请填写原因`,
      '危险操作确认',
      {
        type: 'warning',
        confirmButtonText: '确认终止',
        cancelButtonText: '取消',
        inputValidator: (v) => !!String(v || '').trim() || '必须填写原因'
      }
    );
    await api.request(`/api/v2/ops/admin/exceptions/${item.exceptionId}/cancel-session`, 'POST', {
      reason: value,
      idempotencyKey: `ops-cancel-${item.exceptionId}`
    });
    ElMessage.success('会话已终止，设备占用已释放');
    await Promise.all([load(), refreshDetail()]);
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '终止会话失败');
    }
  }
}
function canRetry(item: OpsException) {
  return (
    !!item.sessionId &&
    ['RECOGNITION_UNAVAILABLE', 'RECOGNITION_FAILED', 'SETTLEMENT_FAILED'].includes(
      item.exceptionType
    )
  );
}
function canResolveWithRepair(item: OpsException) {
  return (
    item.exceptionType === 'DEVICE_FAULT' && !!item.deviceId && auth.hasPerm('ops:repair:edit')
  );
}
async function resolveWithRepairRow(row: OpsException): Promise<boolean> {
  try {
    const { value } = await ElMessageBox.prompt(
      `将为设备 ${row.deviceId} 创建维修工单并结案本异常。请填写处理说明。`,
      '建维修工单并结案',
      {
        inputValidator: (v) => !!String(v || '').trim() || '必须填写说明',
        confirmButtonText: '确认',
        type: 'warning'
      }
    );
    await api.request(
      `/api/v2/ops/admin/exceptions/${row.exceptionId}/resolve-with-repair`,
      'POST',
      {
        resolution: String(value).trim()
      }
    );
    ElMessage.success('已建维修工单并结案');
    await load();
    return true;
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '结案失败');
    }
    return false;
  }
}
async function resolveWithRepair() {
  if (!detail.value) return;
  repairResolving.value = true;
  try {
    const ok = await resolveWithRepairRow(detail.value.exception);
    if (ok) {
      resolveFeedback.value = '已创建维修工单并结案';
      await refreshDetail();
    }
  } finally {
    repairResolving.value = false;
  }
}
async function retryException() {
  if (!detail.value) return;
  const item = detail.value.exception;
  try {
    await ElMessageBox.confirm(
      `将重新处理会话 ${item.sessionId}，系统仍会执行订单、库存和余额幂等校验。`,
      '确认重试',
      {
        type: 'warning',
        confirmButtonText: '开始重试'
      }
    );
    await api.request(`/api/v2/ops/admin/exceptions/${item.exceptionId}/retry`, 'POST', {
      reason: '运营人工触发重试',
      idempotencyKey: `ops-retry-${item.exceptionId}-${Date.now()}`
    });
    ElMessage.success('重试请求已执行');
    await Promise.all([load(), refreshDetail()]);
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '重试失败');
    }
  }
}
function canManualResolve(item: OpsException) {
  return (
    !!item.sessionId &&
    [
      'BALANCE_INSUFFICIENT',
      'RECOGNITION_UNAVAILABLE',
      'RECOGNITION_FAILED',
      'SETTLEMENT_FAILED'
    ].includes(item.exceptionType)
  );
}
async function submitManualResolve() {
  if (!detail.value) return;
  const lines = manualConfirmItems.value;
  if (!lines.length) {
    ElMessage.warning('请至少选择一个商品');
    return;
  }
  if (!manualReason.value.trim()) {
    ElMessage.warning('必须填写处理原因');
    return;
  }
  try {
    await ElMessageBox.confirm(
      '确认按当前商品清单结算？系统将自动计算补扣或退差金额。',
      '资金操作二次确认',
      {
        type: 'warning',
        confirmButtonText: '确认结算'
      }
    );
  } catch (e: any) {
    if (e === 'cancel' || e === 'close') return;
    ElMessage.error(e instanceof Error ? e.message : '确认失败');
    return;
  }
  manualSubmitting.value = true;
  try {
    await api.request(
      `/api/v2/ops/admin/exceptions/${detail.value.exception.exceptionId}/manual-resolve`,
      'POST',
      {
        resolutionType: 'CONFIRM',
        items: lines,
        reason: manualReason.value.trim(),
        idempotencyKey: `ops-manual-${detail.value.exception.exceptionId}-${Date.now()}`
      }
    );
    resolveFeedback.value = '人工商品清单已结算并结案';
    ElMessage.success(resolveFeedback.value);
    await Promise.all([load(), refreshDetail()]);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '人工结算失败');
  } finally {
    manualSubmitting.value = false;
  }
}
async function waiveOrder() {
  if (!detail.value) return;
  const item = detail.value.exception;
  let reason = '';
  try {
    const { value } = await ElMessageBox.prompt(
      '该操作会取消本次消费并退回已经扣除的余额，请填写免单原因。',
      '免单与全额退款',
      {
        type: 'warning',
        confirmButtonText: '确认免单',
        inputValidator: (v) => !!String(v || '').trim() || '必须填写免单原因'
      }
    );
    reason = value;
  } catch (e: any) {
    if (e === 'cancel' || e === 'close') return;
    ElMessage.error(e instanceof Error ? e.message : '确认失败');
    return;
  }
  manualSubmitting.value = true;
  try {
    await api.request(`/api/v2/ops/admin/exceptions/${item.exceptionId}/manual-resolve`, 'POST', {
      resolutionType: 'WAIVE',
      items: [],
      reason,
      idempotencyKey: `ops-waive-${item.exceptionId}`
    });
    resolveFeedback.value = '免单处理完成';
    ElMessage.success(resolveFeedback.value);
    await Promise.all([load(), refreshDetail()]);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '免单失败');
  } finally {
    manualSubmitting.value = false;
  }
}

function applyRouteQuery() {
  let changed = false;
  const qStatus = typeof route.query.status === 'string' ? route.query.status : '';
  const qSeverity = typeof route.query.severity === 'string' ? route.query.severity : '';
  const qOverdue = route.query.overdue === '1' || route.query.overdue === 'true';
  const qArchived = route.query.archived === '1' || route.query.archived === 'true';
  // Keep default OPEN when query omits status (matches page default).
  const nextStatus = qArchived ? 'ARCHIVED' : qStatus || 'OPEN';
  if (nextStatus !== status.value) {
    status.value = nextStatus;
    changed = true;
  }
  if (qSeverity !== severity.value) {
    severity.value = qSeverity;
    changed = true;
  }
  if (qOverdue !== overdueOnly.value) {
    overdueOnly.value = qOverdue;
    changed = true;
  }
  return changed;
}

async function reloadFromRouteQuery() {
  if (!applyRouteQuery()) return;
  page.value = 1;
  await load();
}

watch(
  () =>
    [route.query.status, route.query.severity, route.query.overdue, route.query.archived] as const,
  () => {
    void reloadFromRouteQuery();
  }
);

onActivated(async () => {
  drawer.value = false;
  detail.value = null;
  resolveFeedback.value = '';
  await reloadFromRouteQuery();
});
onDeactivated(() => {
  drawer.value = false;
  detail.value = null;
  resolveFeedback.value = '';
  clearInlineVideo();
});
onMounted(async () => {
  applyRouteQuery();
  syncRouteQuery();
  await load();
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
.muted {
  color: var(--el-text-color-secondary);
}
.status-tabs {
  margin: 0 0 10px;
}
.sla-banner {
  margin-bottom: 10px;
}
.sla-cell {
  display: grid;
  gap: 2px;
  line-height: 1.35;
  justify-items: center;
  text-align: center;
}
.sla-meta {
  color: var(--el-text-color-secondary);
  font-size: 11px;
  text-align: center;
}
.sla-meta.danger {
  color: var(--el-color-danger);
}
.drawer-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  margin: 16px 0;
}
.drawer-actions--tight {
  margin: 8px 0 0;
}
.resolve-feedback {
  margin-bottom: 16px;
}
.workbench-grid {
  display: grid;
  grid-template-columns: 1.1fr 0.9fr;
  gap: 16px;
  margin-bottom: 12px;
}
.workbench-media,
.workbench-meta {
  min-width: 0;
}
.items-title {
  font-size: 13px;
  font-weight: 600;
  margin: 0 0 8px;
  color: var(--layout-text);
}
.video-loading {
  padding: 24px 0;
  text-align: center;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.suggest-alert {
  margin: 8px 0 12px;
}
.adjust-block {
  margin: 12px 0 4px;
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: color-mix(in srgb, var(--el-fill-color-blank) 70%, transparent);
}
.manual-reason {
  margin-top: 8px;
}
.review-video-wrap {
  margin-top: 4px;
  border-radius: 6px;
  overflow: hidden;
  background: #0f172a;
}
.review-video {
  display: block;
  width: 100%;
  max-height: 320px;
  background: #0f172a;
}
.section-title {
  margin: 16px 0 8px;
  font-size: 14px;
  color: var(--layout-text);
}
.action-detail {
  color: var(--layout-muted);
  margin-top: 5px;
  white-space: pre-wrap;
}
.manual-lines {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin: 12px 0;
}
.manual-line {
  display: flex;
  align-items: center;
  gap: 10px;
}
@media (max-width: 900px) {
  .workbench-grid {
    grid-template-columns: 1fr;
  }
}
:deep(.el-table .is-overdue > td.el-table__cell) {
  /* 必须用不透明底，否则 fixed 操作列会透视出左侧滚动内容 */
  background: color-mix(
    in srgb,
    var(--el-color-danger) 6%,
    var(--el-table-bg-color, #fff)
  ) !important;
}
:deep(.el-table .is-due-soon > td.el-table__cell) {
  background: color-mix(
    in srgb,
    var(--el-color-warning) 7%,
    var(--el-table-bg-color, #fff)
  ) !important;
}
</style>
