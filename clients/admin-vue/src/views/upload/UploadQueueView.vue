<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">录像上传队列</span>
            <span class="hint">设备自动上传状态；滞留超过 {{ SLA_MINUTES }} 分钟高亮，便于日间跟进</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-hasPermi="['ops:upload:export']" @click="onExport">{{ exportButtonLabel }}</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
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
      v-if="stuckOnly ? total > 0 : pageStuckCount > 0"
      :type="stuckOnly || pageStuckCount > 0 ? 'warning' : 'info'"
      :closable="false"
      show-icon
      class="sla-banner"
      :title="stuckOnly
        ? `当前筛选共 ${total} 条滞留上传（超过 ${SLA_MINUTES} 分钟）`
        : `本页 ${pageStuckCount} 条已滞留，可勾选「仅滞留」优先处理`"
    />

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="设备">
        <el-input
          v-model="deviceId"
          clearable
          placeholder="设备编号"
          style="width: 180px"
          @keyup.enter="search"
          @clear="search"
        />
      </el-form-item>
      <el-form-item label="上传状态">
        <el-select v-model="uploadStatus" clearable placeholder="全部" style="width: 140px" @change="search">
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
        <el-table
          v-loading="loading"
          :data="displayItems"
          :default-sort="idDefaultSort"
          @sort-change="onIdSortChange"
          stripe
          border
          class="report-table"
          table-layout="auto"
          row-key="sessionId"
          :row-class-name="rowClassName"
          @selection-change="onSelectionChange"
        >
          <template #empty>
            <el-empty :description="emptyHint" :image-size="88" />
          </template>
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column prop="sessionId" label="会话" min-width="168" align="center" class-name="col-text" sortable="custom">
            <template #default="{ row }">
              <button type="button" class="link-cell" @click="goSession(row.sessionId)">
                <span class="cell-id">{{ row.sessionId }}</span>
              </button>
            </template>
          </el-table-column>
          <el-table-column label="用户" width="100" align="center" class-name="col-text">
            <template #default="{ row }">{{ row.userId || '无' }}</template>
          </el-table-column>
          <el-table-column label="设备" min-width="120" align="center" class-name="col-text">
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
          <el-table-column label="对象路径" min-width="180" align="center" class-name="col-text" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="objectKey(row.videoUri)" class="cell-id">{{ objectKey(row.videoUri) }}</span>
              <span v-else class="muted">无</span>
            </template>
          </el-table-column>
          <el-table-column label="上传状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="dictTagType(String(row.uploadStatus || ''))">
                {{ dictLabel('upload_status', row.uploadStatus) || row.uploadStatus || '未知状态' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="等待原因" min-width="200" align="center" class-name="col-text" show-overflow-tooltip>
            <template #default="{ row }">
              <span>{{ waitReason(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="滞留 / 时限" width="150" align="center" class-name="col-text">
            <template #default="{ row }">
              <div class="sla-cell">
                <template v-if="isStuck(row)">
                  <el-tag type="danger" size="small">已滞留</el-tag>
                  <small class="sla-meta danger">超 {{ formatAge(overdueMs(row)) }}</small>
                </template>
                <template v-else-if="isDueSoon(row)">
                  <el-tag type="warning" size="small">临近时限</el-tag>
                  <small class="sla-meta">已等 {{ formatAge(ageMs(row)) }} · 剩 {{ formatAge(remainMs(row)) }}</small>
                </template>
                <template v-else>
                  <span class="cell-datetime">已等 {{ formatAge(ageMs(row)) }}</span>
                  <small class="sla-meta">时限 {{ SLA_MINUTES }} 分</small>
                </template>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="预览" width="80" align="center">
            <template #default="{ row }">
              <el-link v-if="row.videoUri" type="primary" @click.prevent="playVideo(row.sessionId)">播放</el-link>
              <span v-else class="muted">无</span>
            </template>
          </el-table-column>
          <el-table-column label="关门时间" width="168" align="center" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.closeTime) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="更新时间" width="168" align="center" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.updatedAt) }}</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <div class="page-pager">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        background
        @current-change="load"
        @size-change="onSizeChange"
      />
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed, nextTick, onActivated, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { dictLabel, dictOptions, dictTagType } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import { useListCsv } from '@/composables/useListCsv';
import { useNavAccess } from '@/composables/useNavAccess';
import { useSessionVideo } from '@/composables/useSessionVideo';
import { useTableSelection } from '@/composables/useTableSelection';
import { useIdColumnSort } from '@/composables/useIdColumnSort';
import type { PageResult } from '@aicabinet/shared-types';
import { formatDateTime } from '@aicabinet/shared-uni/format';

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
const loading = ref(false);
const helpOpen = ref(false);
const deviceId = ref('');
const uploadStatus = ref('');
const stuckOnly = ref(false);
const page = ref(1);
const size = ref(20);
const total = ref(0);
const items = ref<SessionRow[]>([]);
const { defaultSort: idDefaultSort, onSortChange: onIdSortChange, sortById, idSortDir } =
  useIdColumnSort<SessionRow>('sessionId');

const focusSessionId = ref('');
const uploadStatusOptions = dictOptions('upload_status').filter((o) =>
  ['NONE', 'LOCAL_QUEUED', 'UPLOADING', 'UPLOADED', 'FAILED'].includes(o.value)
);

function matchUploadStatus(row: SessionRow) {
  if (!uploadStatus.value) return true;
  return String(row.uploadStatus || '').toUpperCase() === uploadStatus.value.toUpperCase();
}

const { onSelectionChange, pickSelected, exportButtonLabel, clearSelection } = useTableSelection<SessionRow>(
  (row) => row.sessionId
);

const displayItems = computed(() => {
  const list = [...items.value];
  if (stuckOnly.value) return sortById(list);
  const dir = idSortDir.value === 'desc' ? -1 : 1;
  return list.sort((a, b) => {
    const as = isStuck(a) ? 1 : 0;
    const bs = isStuck(b) ? 1 : 0;
    if (as !== bs) return bs - as;
    return String(a.sessionId).localeCompare(String(b.sessionId), undefined, { numeric: true }) * dir;
  });
});

const pageStuckCount = computed(() => items.value.filter((r) => isStuck(r)).length);

const emptyHint = computed(() =>
  stuckOnly.value
    ? `当前无超过 ${SLA_MINUTES} 分钟的滞留上传，可关闭「仅滞留」查看全部队列`
    : '暂无待上传录像（队列为空表示当前没有滞留上传任务）'
);

const { onExport } = useListCsv({
  filePrefix: '录像上传队列',
  headers: ['会话', '用户', '设备', '上传状态', '等待原因', '滞留分钟', '是否滞留', '关门时间', '更新时间'],
  toRows: () =>
    pickSelected(displayItems.value).map((row) => [
      row.sessionId,
      row.userId ?? '',
      row.deviceId ?? '',
      dictLabel('upload_status', row.uploadStatus) || row.uploadStatus || '',
      waitReason(row),
      String(Math.floor(ageMs(row) / 60000)),
      isStuck(row) ? '是' : '否',
      formatDateTime(row.closeTime),
      formatDateTime(row.updatedAt)
    ])
});

function parseTs(value?: string) {
  if (!value) return NaN;
  const t = Date.parse(value);
  return Number.isNaN(t) ? NaN : t;
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
  if (deviceId.value.trim()) query.deviceId = deviceId.value.trim();
  if (uploadStatus.value) query.uploadStatus = uploadStatus.value;
  if (stuckOnly.value) query.stuck = '1';
  if (focusSessionId.value) query.sessionId = focusSessionId.value;
  router.replace({ query });
}

function applyRouteQuery() {
  let changed = false;
  if (typeof route.query.deviceId === 'string' && route.query.deviceId !== deviceId.value) {
    deviceId.value = route.query.deviceId;
    changed = true;
  } else if (!route.query.deviceId && deviceId.value) {
    deviceId.value = '';
    changed = true;
  }
  const qStuck = route.query.stuck === '1' || route.query.stuck === 'true';
  if (qStuck !== stuckOnly.value) {
    stuckOnly.value = qStuck;
    changed = true;
  }
  if (typeof route.query.uploadStatus === 'string' && route.query.uploadStatus !== uploadStatus.value) {
    uploadStatus.value = route.query.uploadStatus;
    changed = true;
  } else if (!route.query.uploadStatus && uploadStatus.value) {
    uploadStatus.value = '';
    changed = true;
  }
  if (typeof route.query.sessionId === 'string') {
    if (route.query.sessionId !== focusSessionId.value) {
      focusSessionId.value = route.query.sessionId;
      changed = true;
    }
  } else if (focusSessionId.value) {
    focusSessionId.value = '';
    changed = true;
  }
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
    if (deviceId.value.trim()) q.set('deviceId', deviceId.value.trim());
    const data = await api.request<PageResult<SessionRow>>(`/api/v2/ops/admin/sessions?${q}`, 'GET');
    const batch = data.items || [];
    serverTotal = data.total ?? batch.length;
    for (const row of batch) {
      if (opts?.findFirst && row.sessionId === opts.findFirst) found = row;
      if (predicate(row)) matched.push(row);
    }
    scanned += batch.length;
    if (opts?.findFirst && found) break;
    if (!batch.length || batch.length < pageSize) break;
    apiPage += 1;
  }
  return { matched, found };
}

async function maybeScrollToFocus() {
  if (!focusSessionId.value) return;
  await nextTick();
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  document
    .querySelector('.report-table .is-focus')
    ?.scrollIntoView({ block: 'nearest', behavior: reduceMotion ? 'auto' : 'smooth' });
}

async function load() {
  loading.value = true;
  try {
    // Soft stuck / uploadStatus filters are client-side; scan recent waiting pages.
    if (stuckOnly.value || uploadStatus.value) {
      const { matched } = await scanWaitingPages(
        (r) => (!stuckOnly.value || isStuck(r)) && matchUploadStatus(r)
      );
      matched.sort((a, b) => ageMs(b) - ageMs(a));
      total.value = matched.length;
      const start = (page.value - 1) * size.value;
      items.value = matched.slice(start, start + size.value);
    } else {
      const q = new URLSearchParams({
        page: String(page.value - 1),
        size: String(size.value),
        state: 'WAITING_UPLOAD'
      });
      if (deviceId.value.trim()) q.set('deviceId', deviceId.value.trim());
      const data = await api.request<PageResult<SessionRow>>(`/api/v2/ops/admin/sessions?${q}`, 'GET');
      items.value = data.items || [];
      total.value = data.total ?? 0;
    }
    const sid = focusSessionId.value.trim();
    if (sid && !items.value.some((r) => r.sessionId === sid)) {
      const { found } = await scanWaitingPages(() => false, { findFirst: sid });
      if (found) {
        items.value = [found, ...items.value.filter((r) => r.sessionId !== found.sessionId)];
      }
    }
    clearSelection();
    await maybeScrollToFocus();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

function search() {
  page.value = 1;
  syncRouteQuery();
  load();
}

function onStuckToggle() {
  page.value = 1;
  syncRouteQuery();
  load();
}

function reset() {
  deviceId.value = '';
  uploadStatus.value = '';
  stuckOnly.value = false;
  focusSessionId.value = '';
  page.value = 1;
  syncRouteQuery();
  load();
}

function onSizeChange() {
  page.value = 1;
  load();
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
  page.value = 1;
  await load();
}

watch(
  () => [route.query.deviceId, route.query.stuck, route.query.sessionId] as const,
  () => {
    void reloadFromRouteQuery();
  }
);

onMounted(() => {
  applyRouteQuery();
  load();
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
.page-card-head__meta { min-width: 0; }
.page-card-head__title { display: flex; flex-direction: column; gap: 4px; }
.title { font-size: 15px; font-weight: 600; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.4; }
.page-card-head__actions { display: flex; gap: 8px; }
.list-lead { margin: 0 0 8px; }
.help-toggle { padding-left: 0; }
.upload-hint { margin: 8px 0 0; }
.sla-banner { margin-bottom: 10px; }
.sla-cell { display: grid; gap: 2px; line-height: 1.35; }
.sla-meta { color: var(--el-text-color-secondary); font-size: 11px; }
.sla-meta.danger { color: var(--el-color-danger); }
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
.link-cell:hover { text-decoration: underline; }
.muted { color: var(--el-text-color-placeholder); }
:deep(.el-table .is-overdue > td.el-table__cell) {
  background: color-mix(in srgb, var(--el-color-danger) 6%, var(--el-table-bg-color, #fff)) !important;
}
:deep(.el-table .is-due-soon > td.el-table__cell) {
  background: color-mix(in srgb, var(--el-color-warning) 7%, var(--el-table-bg-color, #fff)) !important;
}
:deep(.el-table .is-focus > td.el-table__cell) {
  outline: 1px solid color-mix(in srgb, var(--el-color-primary) 45%, transparent);
  outline-offset: -1px;
}
</style>
