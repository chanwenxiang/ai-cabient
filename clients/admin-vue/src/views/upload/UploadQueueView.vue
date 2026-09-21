<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">录像上传队列</span>
            <span class="hint"
              >设备自动上传状态；滞留超过 {{ SLA_MINUTES }} 分钟高亮，便于日间跟进</span
            >
          </div>
        </div>
      </div>
    </template>

    <div class="list-lead">
      <el-button text type="primary" size="small" class="help-toggle" @click="helpOpen = !helpOpen">
        {{ helpOpen ? '收起说明' : '上传说明' }}
      </el-button>
      <el-collapse-transition>
        <el-alert
          v-show="helpOpen"
          class="upload-hint"
          type="info"
          :closable="false"
          show-icon
          title="本页为设备录像上传状态队列，不是人工上传入口"
          description="购物会话关门后，设备/边缘端会自动上传录像到对象存储；此处仅查询待上传、上传中、失败会话，并可预览已上传文件。处理时限与工作台「录像滞留」一致（30 分钟）。"
        />
      </el-collapse-transition>
    </div>

    <el-alert
      v-if="crud.hydrated && (stuckOnly ? crud.total > 0 : pageStuckCount > 0)"
      :type="stuckOnly || pageStuckCount > 0 ? 'warning' : 'info'"
      :closable="false"
      show-icon
      class="sla-banner"
      :title="
        stuckOnly
          ? `当前筛选共 ${crud.total} 条滞留上传（超过 ${SLA_MINUTES} 分钟）`
          : `本页 ${pageStuckCount} 条已滞留，可勾选「仅滞留」优先处理`
      "
    />

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="关键词">
        <el-input
          v-model="keyword"
          clearable
          placeholder="会话 / 设备…"
          style="width: 260px"
          @keyup.enter="search"
          @clear="search"
        />
      </el-form-item>
      <el-form-item label="上传状态">
        <el-select
          v-model="uploadStatus"
          clearable
          placeholder="全部"
          style="width: 140px"
          @change="search"
        >
          <el-option
            v-for="item in uploadStatusOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-checkbox v-model="stuckOnly" @change="onStuckToggle">仅滞留</el-checkbox>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
    </el-form>

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <CrudTable
          :table="crud"
          row-key="sessionId"
          selectable
          :empty-text="emptyHint"
          sort-field-label="会话编号"
          :csv="csvOptions"
          :row-class-name="rowClassName"
        >
          <el-table-column
            prop="sessionId"
            label="会话编号"
            min-width="168"
            class-name="col-text"
          >
            <template #default="{ row }">
              <button type="button" class="link-cell" @click="goSession(row.sessionId)">
                <span class="cell-id">{{ displayBizNo(row.sessionId) }}</span>
              </button>
            </template>
          </el-table-column>
          <el-table-column label="用户" width="100" class-name="col-text">
            <template #default="{ row }">{{ row.userId || '无' }}</template>
          </el-table-column>
          <el-table-column label="设备" min-width="120" class-name="col-text">
            <template #default="{ row }">
              <button
                v-if="row.deviceId"
                type="button"
                class="link-cell"
                @click="goPath(`/devices/${encodeURIComponent(row.deviceId)}`)"
              >
                {{ row.deviceId }}
              </button>
              <span v-else class="muted">无</span>
            </template>
          </el-table-column>
          <el-table-column label="对象路径" min-width="180" class-name="col-text">
            <template #default="{ row }">
              <span v-if="objectKey(row.videoUri)" class="cell-id">{{
                objectKey(row.videoUri)
              }}</span>
              <span v-else class="muted">无</span>
            </template>
          </el-table-column>
          <el-table-column
            label="上传状态"
            width="110"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag size="small" :type="dictTagType(String(row.uploadStatus || ''))">
                {{ displayLabel('upload_status', row.uploadStatus, '未知状态') }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="等待原因" min-width="200" class-name="col-text">
            <template #default="{ row }">
              <span>{{ waitReason(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column
            align="center"
            label="滞留 / 时限"
            width="150"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <div class="sla-cell">
                <template v-if="isStuck(row)">
                  <el-tag type="danger" size="small">已滞留</el-tag>
                  <small class="sla-meta danger">超 {{ formatAge(overdueMs(row)) }}</small>
                </template>
                <template v-else-if="isDueSoon(row)">
                  <el-tag type="warning" size="small">临近时限</el-tag>
                  <small class="sla-meta"
                    >已等 {{ formatAge(ageMs(row)) }} · 剩 {{ formatAge(remainMs(row)) }}</small
                  >
                </template>
                <template v-else>
                  <span class="cell-datetime">已等 {{ formatAge(ageMs(row)) }}</span>
                  <small class="sla-meta">时限 {{ SLA_MINUTES }} 分</small>
                </template>
              </div>
            </template>
          </el-table-column>
          <el-table-column
            label="预览"
            width="80"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-link v-if="row.videoUri" type="primary" @click.prevent="playVideo(row.sessionId)"
                >播放</el-link
              >
              <span v-else class="muted">无</span>
            </template>
          </el-table-column>
          <el-table-column
            align="center"
            label="关门时间"
            width="168"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.closeTime) }}</span>
            </template>
          </el-table-column>
          <el-table-column
            align="center"
            label="更新时间"
            width="168"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.updatedAt) }}</span>
            </template>
          </el-table-column>
        </CrudTable>
      </div>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed, nextTick, onActivated, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { dictTagType, displayLabel } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import CrudTable, { type CrudCsvOptions } from '@/components/CrudTable.vue';
import { useCrudTable } from '@/composables/useCrudTable';
import { useDictOptions } from '@/composables/useDictOptions';
import { useNavAccess } from '@/composables/useNavAccess';
import { useSessionVideo } from '@/composables/useSessionVideo';
import type { PageResult } from '@aicabinet/shared-types';
import { displayBizNo, formatDateTime } from '@aicabinet/shared-uni/format';

interface SessionRow {
  sessionId: string;
  userId?: string;
  deviceId?: string;
  uploadStatus?: string;
  videoUri?: string;
  videoPreviewUrl?: string;
  closeTime?: string;
  updatedAt?: string;
}

/** Align with AdminDashboardService UPLOAD_STUCK dueAt (updatedAt + 30m). */
const SLA_MINUTES = 30;
const SLA_MS = SLA_MINUTES * 60 * 1000;
const DUE_SOON_MS = 10 * 60 * 1000;

const route = useRoute();
const { router, goPath } = useNavAccess();
const { playSessionVideo } = useSessionVideo();
const helpOpen = ref(false);
const keyword = ref('');
const uploadStatus = ref('');
const stuckOnly = ref(false);
const focusSessionId = ref('');

const uploadStatusDict = useDictOptions('upload_status');
const uploadStatusOptions = computed(() =>
  uploadStatusDict.value.filter((o) =>
    ['NONE', 'LOCAL_QUEUED', 'UPLOADING', 'UPLOADED', 'FAILED'].includes(o.value)
  )
);

function parseTs(value?: string) {
  if (!value) return Number.NaN;
  const t = Date.parse(value);
  return Number.isNaN(t) ? Number.NaN : t;
}

/** Age for display: since door close when available, else last update. */
function ageAnchorMs(row: SessionRow) {
  const close = parseTs(row.closeTime);
  if (!Number.isNaN(close)) return close;
  return parseTs(row.updatedAt);
}

/** Stuck clock matches workbench: updatedAt older than SLA. */
function slaAnchorMs(row: SessionRow) {
  const updated = parseTs(row.updatedAt);
  if (!Number.isNaN(updated)) return updated;
  return ageAnchorMs(row);
}

function ageMs(row: SessionRow) {
  const t = ageAnchorMs(row);
  return Number.isNaN(t) ? 0 : Math.max(0, Date.now() - t);
}

function remainMs(row: SessionRow) {
  const t = slaAnchorMs(row);
  if (Number.isNaN(t)) return SLA_MS;
  return Math.max(0, t + SLA_MS - Date.now());
}

function overdueMs(row: SessionRow) {
  const t = slaAnchorMs(row);
  if (Number.isNaN(t)) return 0;
  return Math.max(0, Date.now() - (t + SLA_MS));
}

function isStuck(row: SessionRow) {
  const t = slaAnchorMs(row);
  if (Number.isNaN(t)) return false;
  return Date.now() - t >= SLA_MS;
}

function isDueSoon(row: SessionRow) {
  if (isStuck(row)) return false;
  const left = remainMs(row);
  return left > 0 && left <= DUE_SOON_MS;
}

// 列表状态机统一交给 CrudTable：分页 / 排序 / 多选 / 竞态 / 空态 / CSV 全部内建。
// autoLoad 关闭：首查依赖 onMounted 中 applyRouteQuery() 先把路由参数写入筛选项。
const crud = useCrudTable<SessionRow>({
  rowKey: (row) => row.sessionId,
  autoLoad: false,
  fetchPage: async (params) => {
    const q = new URLSearchParams({
      page: String(params.page), // 已是 0 起
      size: String(params.size),
      state: 'WAITING_UPLOAD'
    });
    if (keyword.value.trim()) q.set('q', keyword.value.trim());
    if (uploadStatus.value) q.set('uploadStatus', uploadStatus.value);
    if (stuckOnly.value) {
      q.set('stuckOnly', 'true');
      q.set('stuckMinutes', String(SLA_MINUTES));
    }
    const data = await api.request<PageResult<SessionRow>>(AdminEndpoints.sessionsList(q), 'GET');
    const rows = data.items || [];
    const sid = focusSessionId.value.trim();
    // 聚焦会话不在当前页时：扫描等待队列定位后插入页首（保留原行为）
    if (sid && !rows.some((r) => r.sessionId === sid)) {
      const { found } = await scanWaitingPages(() => false, { findFirst: sid });
      if (found) {
        return {
          items: [found, ...rows.filter((r) => r.sessionId !== found.sessionId)],
          total: data.total ?? 0
        };
      }
    }
    return { items: rows, total: data.total ?? 0 };
  },
  sort: {
    prop: 'sessionId',
    mode: 'local',
    // 滞留行置顶（组内保持主键序）；「仅滞留」下服务端已过滤、全部命中置顶等价于不分组，与原逻辑一致
    pinned: (row) => !stuckOnly.value && isStuck(row)
  }
});

// 原逻辑在 load() 尾部滚动到聚焦行；迁移后改为监听数据变化统一触发
watch(
  () => crud.items,
  () => {
    void maybeScrollToFocus();
  }
);

const pageStuckCount = computed(() => crud.displayItems.filter((r) => isStuck(r)).length);

const emptyHint = computed(() =>
  stuckOnly.value
    ? `当前无超过 ${SLA_MINUTES} 分钟的滞留上传，可关闭「仅滞留」查看全部队列`
    : '暂无待上传录像（队列为空表示当前没有滞留上传任务）'
);

const csvOptions: CrudCsvOptions = {
  filePrefix: '录像上传队列',
  exportPerm: 'ops:upload:export',
  headers: [
    '会话编号',
    '用户',
    '设备',
    '上传状态',
    '等待原因',
    '滞留分钟',
    '是否滞留',
    '关门时间',
    '更新时间'
  ],
  toRows: (rows) =>
    rows.map((row) => [
      row.sessionId,
      row.userId ?? '',
      row.deviceId ?? '',
      displayLabel('upload_status', row.uploadStatus, '未知'),
      waitReason(row),
      String(Math.floor(ageMs(row) / 60000)),
      isStuck(row) ? '是' : '否',
      formatDateTime(row.closeTime),
      formatDateTime(row.updatedAt)
    ])
};

function formatAge(ms: number) {
  const abs = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(abs / 3600);
  const m = Math.floor((abs % 3600) / 60);
  if (h >= 48) return `${Math.floor(h / 24)} 天`;
  if (h > 0) return `${h} 小时 ${m} 分`;
  if (m > 0) return `${m} 分钟`;
  return '不到 1 分钟';
}

function waitReason(row: SessionRow) {
  const status = String(row.uploadStatus || '').toUpperCase();
  const stuck = isStuck(row);
  switch (status) {
    case 'LOCAL_QUEUED':
      return stuck ? '本地排队超时，可能弱网或设备离线' : '设备本地排队，等待推送对象存储';
    case 'UPLOADING':
      return stuck ? '上传中断或极慢，请查设备网络/存储' : '正在上传至对象存储';
    case 'FAILED':
      return '上传失败，需设备侧重试或排查存储凭证';
    case 'UPLOADED':
      return '已上传，等待会话状态收口';
    case 'NONE':
      return stuck ? '关门后长期无上传状态上报' : '待设备上报上传状态';
    default:
      return stuck ? '上传状态未推进，已超过处理时限' : '关门后等待录像上传';
  }
}

function rowClassName({ row }: { row: SessionRow }) {
  const classes: string[] = [];
  if (isStuck(row)) classes.push('is-overdue');
  else if (isDueSoon(row)) classes.push('is-due-soon');
  if (focusSessionId.value && row.sessionId === focusSessionId.value) classes.push('is-focus');
  return classes.join(' ');
}

function syncRouteQuery() {
  const query: Record<string, string> = {};
  if (keyword.value.trim()) query.keyword = keyword.value.trim();
  if (uploadStatus.value) query.uploadStatus = uploadStatus.value;
  if (stuckOnly.value) query.stuck = '1';
  if (focusSessionId.value) query.sessionId = focusSessionId.value;
  router.replace({ query });
}

const UPLOAD_ROUTE_KEYWORD_KEYS = ['keyword', 'q', 'deviceId', 'qSessionId', 'sessionId'] as const;

function resolveUploadRouteKeyword(): string {
  for (const key of UPLOAD_ROUTE_KEYWORD_KEYS) {
    const value = route.query[key];
    if (typeof value === 'string') return value;
  }
  return '';
}

function syncUploadKeywordFromRoute(changed: boolean): boolean {
  const routeKeyword = resolveUploadRouteKeyword();
  if (routeKeyword === keyword.value) return changed;
  keyword.value = routeKeyword;
  return true;
}

function syncUploadStuckFromRoute(changed: boolean): boolean {
  const qStuck = route.query.stuck === '1' || route.query.stuck === 'true';
  if (qStuck === stuckOnly.value) return changed;
  stuckOnly.value = qStuck;
  return true;
}

function syncUploadStatusFromRoute(changed: boolean): boolean {
  if (
    typeof route.query.uploadStatus === 'string' &&
    route.query.uploadStatus !== uploadStatus.value
  ) {
    uploadStatus.value = route.query.uploadStatus;
    return true;
  }
  if (!route.query.uploadStatus && uploadStatus.value) {
    uploadStatus.value = '';
    return true;
  }
  return changed;
}

function syncUploadFocusFromRoute(changed: boolean): boolean {
  if (typeof route.query.sessionId === 'string') {
    if (route.query.sessionId === focusSessionId.value) return changed;
    focusSessionId.value = route.query.sessionId;
    if (!keyword.value) keyword.value = route.query.sessionId;
    return true;
  }
  if (!focusSessionId.value) return changed;
  focusSessionId.value = '';
  return true;
}

function applyRouteQuery() {
  let changed = false;
  changed = syncUploadKeywordFromRoute(changed);
  changed = syncUploadStuckFromRoute(changed);
  changed = syncUploadStatusFromRoute(changed);
  changed = syncUploadFocusFromRoute(changed);
  return changed;
}

async function scanWaitingPages(
  predicate: (row: SessionRow) => boolean,
  opts?: { findFirst?: string }
): Promise<{ matched: SessionRow[]; found?: SessionRow }> {
  const pageSize = 100;
  const maxScan = 500;
  const matched: SessionRow[] = [];
  let found: SessionRow | undefined;
  let apiPage = 0;
  let scanned = 0;
  let serverTotal = Number.POSITIVE_INFINITY;
  while (scanned < maxScan && scanned < serverTotal) {
    const q = new URLSearchParams({
      page: String(apiPage),
      size: String(pageSize),
      state: 'WAITING_UPLOAD'
    });
    if (keyword.value.trim()) q.set('q', keyword.value.trim());
    const data = await api.request<PageResult<SessionRow>>(AdminEndpoints.sessionsList(q), 'GET');
    const batch = data.items || [];
    serverTotal = data.total ?? batch.length;
    for (const row of batch) {
      if (opts?.findFirst && row.sessionId === opts.findFirst) found = row;
      if (predicate(row)) matched.push(row);
    }
    scanned += batch.length;
    if (shouldStopWaitingScan(opts, found, batch, pageSize)) break;
    apiPage += 1;
  }
  return { matched, found };
}

function shouldStopWaitingScan(
  opts: { findFirst?: string } | undefined,
  found: SessionRow | undefined,
  batch: SessionRow[],
  pageSize: number
): boolean {
  if (opts?.findFirst && found) return true;
  if (!batch.length || batch.length < pageSize) return true;
  return false;
}

async function maybeScrollToFocus() {
  if (!focusSessionId.value) return;
  await nextTick();
  const reduceMotion = globalThis.matchMedia('(prefers-reduced-motion: reduce)').matches;
  document
    .querySelector('.report-table .is-focus')
    ?.scrollIntoView({ block: 'nearest', behavior: reduceMotion ? 'auto' : 'smooth' });
}

function search() {
  syncRouteQuery();
  crud.search();
}

function onStuckToggle() {
  search();
}

function reset() {
  keyword.value = '';
  uploadStatus.value = '';
  stuckOnly.value = false;
  focusSessionId.value = '';
  syncRouteQuery();
  crud.search();
}

function goSession(sessionId: string) {
  goPath('/sessions', { sessionId });
}

function objectKey(videoUri?: string) {
  if (!videoUri) return '';
  const match = videoUri.match(/^(?:minio|oss|s3):\/\/[^/]+\/(.+)$/);
  return match?.[1] ?? '';
}

async function playVideo(sessionId: string) {
  await playSessionVideo(sessionId);
}

async function reloadFromRouteQuery() {
  if (!applyRouteQuery()) return;
  await crud.search();
}

watch(
  () =>
    [
      route.query.keyword,
      route.query.q,
      route.query.deviceId,
      route.query.stuck,
      route.query.sessionId
    ] as const,
  () => {
    void reloadFromRouteQuery();
  }
);

onMounted(() => {
  // 首查依赖路由参数初始化（useCrudTable 配置 autoLoad: false），同步完筛选项后显式首查
  applyRouteQuery();
  void crud.load();
});
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
  font-size: var(--admin-font-size-title);
  font-weight: 600;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: var(--admin-font-size-sm);
  line-height: 1.4;
}
.page-card-head__actions {
  display: flex;
  gap: 8px;
}
.list-lead {
  margin: 0 0 8px;
}
.help-toggle {
  padding-left: 0;
}
.upload-hint {
  margin: 8px 0 0;
}
.sla-banner {
  margin-bottom: 10px;
}
.sla-cell {
  display: grid;
  gap: 2px;
  line-height: 1.35;
}
.sla-meta {
  color: var(--el-text-color-secondary);
  font-size: var(--admin-font-size-xs);
}
.sla-meta.danger {
  color: var(--el-color-danger);
}
.link-cell {
  appearance: none;
  border: 0;
  padding: 0;
  margin: 0;
  background: transparent;
  color: var(--el-color-primary);
  cursor: pointer;
  font: inherit;
  text-align: center;
}
.link-cell:hover {
  text-decoration: underline;
}
.muted {
  color: var(--el-text-color-placeholder);
}
:deep(.el-table .is-overdue > td.el-table__cell) {
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
:deep(.el-table .is-focus > td.el-table__cell) {
  outline: 1px solid color-mix(in srgb, var(--el-color-primary) 45%, transparent);
  outline-offset: -1px;
}
</style>
