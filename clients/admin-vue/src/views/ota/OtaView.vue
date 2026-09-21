<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">固件版本</span>
            <span class="hint">支持发布与灰度；柜机需自行安装，后台不下发自动安装</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button
            v-hasPermi="['ops:ota:publish']"
            type="danger"
            plain
            :disabled="!hasUnpublishableSelection"
            :loading="batchUnpublishing"
            @click="batchUnpublish"
          >
            批量下架
          </el-button>
          <el-button v-hasPermi="['ops:ota:publish']" type="primary" @click="openPublish"
            >发布版本</el-button
          >
        </div>
      </div>
    </template>

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <CrudTable
          :table="crud"
          selectable
          :actions="rowActions"
          :action-width="100"
          actions-testid="ota"
          empty-text="暂无固件版本"
          :csv="csvOptions"
          @action="onAction"
        >
          <el-table-column prop="appVersion" label="版本" min-width="120" class-name="col-text" />
          <el-table-column prop="channel" label="渠道" width="100" class-name="col-text">
            <template #default="{ row }">{{ channelLabel(row.channel) }}</template>
          </el-table-column>
          <el-table-column
            prop="status"
            label="状态"
            width="100"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">{{ statusLabel(row.status) }}</template>
          </el-table-column>
          <el-table-column
            label="强制"
            width="80"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">{{ row.mandatory ? '是' : '否' }}</template>
          </el-table-column>
          <el-table-column
            label="灰度%"
            width="80"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">{{ row.grayPercent ?? 100 }}</template>
          </el-table-column>
          <el-table-column
            label="定向设备"
            width="110"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag v-if="row.deviceAllowlist?.length" size="small" type="warning" effect="plain">
                {{ row.deviceAllowlist.length }} 台
              </el-tag>
              <span v-else>全量</span>
            </template>
          </el-table-column>
          <el-table-column prop="minVersion" label="最低版本" width="110" class-name="col-text" />
          <el-table-column
            align="center"
            label="发布时间"
            width="168"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.publishedAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column
            prop="releaseNotes"
            label="说明"
            min-width="180"
            class-name="col-text"
            label-class-name="col-text"
          />
        </CrudTable>
      </div>
    </div>
  </el-card>

  <!-- O2：设备侧上报的升级进度（下载/安装）。数据源同受 ota.progress.enabled 控制：
       开关关闭时设备不上报，这里就是空表 —— 这是「没上报」而不是「页面坏了」，故给出明确空态。 -->
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">升级进度</span>
            <span class="hint">设备上报的下载/安装进度；未开启上报开关时设备不上报</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-select v-model="progressStatus" style="width: 150px" @change="loadProgress">
            <el-option label="全部状态" value="" />
            <el-option
              v-for="s in PROGRESS_STATUSES"
              :key="s"
              :label="progressStatusLabel(s)"
              :value="s"
            />
          </el-select>
          <el-button :icon="Refresh" :loading="progressLoading" @click="loadProgress"
            >刷新</el-button
          >
        </div>
      </div>
    </template>

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <el-table
          v-loading="progressLoading"
          :data="progressItems"
          stripe
          border
          class="report-table"
          row-key="deviceId"
          empty-text=" "
        >
          <template #empty
            ><el-empty v-if="progressHydrated && !progressLoading" description="暂无升级进度上报"
          /></template>
          <el-table-column
            prop="deviceId"
            label="设备"
            min-width="140"
            class-name="col-text"
            label-class-name="col-text"
          />
          <el-table-column
            label="当前版本"
            width="120"
            class-name="col-text"
            label-class-name="col-text"
          >
            <template #default="{ row }">{{ row.appVersion || '—' }}</template>
          </el-table-column>
          <el-table-column
            label="目标版本"
            width="120"
            class-name="col-text"
            label-class-name="col-text"
          >
            <template #default="{ row }">{{ row.targetVersion || '—' }}</template>
          </el-table-column>
          <el-table-column
            label="状态"
            width="110"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag :type="progressStatusTagType(row.upgradeStatus)" size="small" effect="plain">
                {{ progressStatusLabel(row.upgradeStatus) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column
            label="进度"
            width="170"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-progress :percentage="clampPercent(row.progressPercent)" :stroke-width="12" />
            </template>
          </el-table-column>
          <el-table-column
            label="失败原因"
            min-width="160"
            class-name="col-text"
            label-class-name="col-text"
          >
            <template #default="{ row }">{{ row.errorMessage || '—' }}</template>
          </el-table-column>
          <el-table-column
            label="最近上报"
            width="168"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.updatedAt) }}</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>
  </el-card>

  <el-dialog v-model="dialog" title="发布固件版本" destroy-on-close>
    <el-form label-width="auto">
      <el-form-item label="版本号" required>
        <el-input v-model="form.appVersion" placeholder="例如 1.2.0…" />
      </el-form-item>
      <el-form-item label="渠道">
        <el-input v-model="form.channel" placeholder="稳定版 / 测试版（stable / beta）…" />
      </el-form-item>
      <el-form-item label="下载地址" required>
        <el-input v-model="form.downloadUrl" placeholder="https://cdn.example.com/app.apk…" />
      </el-form-item>
      <el-form-item label="校验和" required>
        <el-input v-model="form.checksumSha256" placeholder="sha256（必填，64 位十六进制）…" />
      </el-form-item>
      <el-form-item label="最低版本">
        <el-input v-model="form.minVersion" placeholder="可选…" />
      </el-form-item>
      <el-form-item label="灰度 %">
        <el-input-number v-model="form.grayPercent" :min="1" :max="100" />
      </el-form-item>
      <el-form-item label="定向设备">
        <el-select
          v-model="form.deviceAllowlist"
          multiple
          filterable
          clearable
          placeholder="不选则全量 / 按灰度%"
          style="width: 100%"
        >
          <el-option
            v-for="d in deviceOptions"
            :key="d.deviceId"
            :label="`${d.deviceName || d.deviceId}（${d.deviceId}）`"
            :value="d.deviceId"
          />
        </el-select>
        <div class="field-hint">指定后仅这些柜机收到该版本（定向优先于灰度）</div>
      </el-form-item>
      <el-form-item label="强制升级">
        <el-switch v-model="form.mandatory" />
      </el-form-item>
      <el-form-item label="说明">
        <el-input v-model="form.releaseNotes" type="textarea" :rows="3" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialog = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="publish">发布</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { CircleClose, Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import CrudTable, { type CrudCsvOptions, type CrudRowAction } from '@/components/CrudTable.vue';
import { useCrudTable } from '@/composables/useCrudTable';
import { useDeviceOptions } from '@/composables/useDeviceOptions';
import { formatDateTime } from '@aicabinet/shared-uni/format';

function statusLabel(s?: string) {
  const m: Record<string, string> = {
    DRAFT: '草稿',
    PUBLISHED: '已发布',
    UNPUBLISHED: '已下架',
    ARCHIVED: '已归档'
  };
  return (s && m[s]) || (s ? '未知' : '暂无');
}

function channelLabel(c?: string) {
  const m: Record<string, string> = {
    stable: '稳定版',
    beta: '测试版',
    canary: '灰度',
    internal: '内部'
  };
  return (c && m[c]) || (c ? '未知' : '暂无');
}

/** 与后端 OtaService.ALLOWED_PROGRESS_STATUS 对齐（未知状态后端直接 400，不会落库）。 */
const PROGRESS_STATUSES = ['IDLE', 'DOWNLOADING', 'INSTALLING', 'SUCCESS', 'FAILED'] as const;

function progressStatusLabel(s?: string) {
  const m: Record<string, string> = {
    IDLE: '空闲',
    DOWNLOADING: '下载中',
    INSTALLING: '安装中',
    SUCCESS: '已成功',
    FAILED: '失败'
  };
  return (s && m[s]) || (s ? '未知' : '暂无');
}

function progressStatusTagType(s?: string): 'success' | 'info' | 'warning' | 'danger' {
  if (s === 'SUCCESS') return 'success';
  if (s === 'FAILED') return 'danger';
  if (s === 'DOWNLOADING' || s === 'INSTALLING') return 'warning';
  return 'info';
}

/** 进度条只接受 0-100；后端已 clamp，这里再兜一层，避免脏数据把进度条画爆。 */
function clampPercent(v?: number) {
  if (typeof v !== 'number' || Number.isNaN(v)) return 0;
  return Math.min(100, Math.max(0, Math.round(v)));
}

interface OtaRelease {
  releaseId?: number;
  appVersion?: string;
  channel?: string;
  downloadUrl?: string;
  checksumSha256?: string;
  releaseNotes?: string;
  mandatory?: boolean;
  minVersion?: string;
  status?: string;
  publishedAt?: string;
  grayPercent?: number;
  deviceAllowlist?: string[];
}

/** 对应后端 OtaUpgradeProgressDto（GET /ops/ota/reports）。 */
interface OtaProgress {
  deviceId?: string;
  appVersion?: string | null;
  targetVersion?: string | null;
  upgradeStatus?: string;
  progressPercent?: number;
  errorMessage?: string | null;
  reportedAt?: string;
  updatedAt?: string;
}

const { deviceOptions, loadDeviceOptions } = useDeviceOptions();
const saving = ref(false);
const batchUnpublishing = ref(false);

// 列表状态机统一交给 CrudTable：分页 / 多选 / 竞态防护 / 空态 全部内建（挂载后自动首查）
const crud = useCrudTable<OtaRelease>({
  rowKey: (r) => r.releaseId ?? `${r.appVersion}-${r.channel}`,
  fetchPage: (params) => {
    const q = new URLSearchParams({
      page: String(params.page),
      size: String(params.size)
    });
    return api.request<OtaRelease[] | { items: OtaRelease[]; total: number }>(
      AdminEndpoints.otaReleasesList(q),
      'GET'
    );
  }
});

const csvOptions: CrudCsvOptions = {
  filePrefix: '固件版本',
  headers: [
    '发布ID',
    '版本',
    '渠道',
    '状态',
    '强制',
    '灰度%',
    '定向设备数',
    '最低版本',
    '发布时间',
    '说明'
  ],
  toRows: (rows) =>
    rows.map((r) => [
      r.releaseId ?? '',
      r.appVersion || '',
      channelLabel(r.channel),
      statusLabel(r.status),
      r.mandatory ? '是' : '否',
      r.grayPercent ?? 100,
      r.deviceAllowlist?.length ?? 0,
      r.minVersion || '',
      formatDateTime(r.publishedAt) || '',
      r.releaseNotes || ''
    ])
};

// ── O2 升级进度 ────────────────────────────────────────────────────────────
const progressStatus = ref('');
const progressLoading = ref(false);
const progressHydrated = ref(false);
const progressItems = ref<OtaProgress[]>([]);

function rowActions(row: OtaRelease): CrudRowAction[] {
  return row.status === 'PUBLISHED'
    ? [
        {
          key: 'unpublish',
          label: '下架',
          icon: CircleClose,
          type: 'danger',
          perm: 'ops:ota:publish'
        }
      ]
    : [];
}

function onAction({ key, row }: { key: string; row: OtaRelease }) {
  if (key === 'unpublish') void unpublish(row);
}

/** 勾选中可下架的已发布版本；未勾选时为空。 */
const unpublishableSelected = computed(() => {
  if (!crud.hasSelection) return [];
  return crud.pickSelected(crud.items).filter(
    (r): r is OtaRelease & { releaseId: number } => r.status === 'PUBLISHED' && r.releaseId != null
  );
});
const hasUnpublishableSelection = computed(() => unpublishableSelected.value.length > 0);

const dialog = ref(false);
const form = reactive({
  appVersion: '',
  channel: 'stable',
  downloadUrl: '',
  checksumSha256: '',
  releaseNotes: '',
  mandatory: false,
  minVersion: '',
  grayPercent: 100,
  deviceAllowlist: [] as string[]
});

/** 拉取设备升级进度（不分页：后端固定返回最近 N 条，按最近上报倒序）。 */
async function loadProgress() {
  progressLoading.value = true;
  try {
    const q = new URLSearchParams();
    if (progressStatus.value) q.set('status', progressStatus.value);
    const data = await api.request<OtaProgress[]>(
      AdminEndpoints.otaReports(q.size ? q : undefined),
      'GET'
    );
    progressItems.value = Array.isArray(data) ? data : [];
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载升级进度失败');
  } finally {
    progressHydrated.value = true;
    progressLoading.value = false;
  }
}

function openPublish() {
  form.appVersion = '';
  form.channel = 'stable';
  form.downloadUrl = '';
  form.checksumSha256 = '';
  form.releaseNotes = '';
  form.mandatory = false;
  form.minVersion = '';
  form.grayPercent = 100;
  form.deviceAllowlist = [];
  dialog.value = true;
  void loadDeviceOptions();
}

async function publish() {
  if (!form.appVersion.trim() || !form.downloadUrl.trim()) {
    ElMessage.warning('请填写版本号与下载地址');
    return;
  }
  // H09：发布即时对设备端生效（status:'PUBLISHED'），与下架同级危险操作，需二次确认
  const scopeText = form.deviceAllowlist.length
    ? `指定 ${form.deviceAllowlist.length} 台设备`
    : form.grayPercent < 100
      ? `灰度 ${form.grayPercent}%`
      : '全量设备';
  try {
    await ElMessageBox.confirm(
      `确认发布版本 ${form.appVersion.trim()}（${form.channel.trim() || 'stable'} · ${scopeText}· 强制${
        form.mandatory ? '开' : '关'
      }）？发布后设备端将立即收到更新。`,
      '发布版本',
      {
        type: 'warning',
        confirmButtonText: '确认发布',
        cancelButtonText: '取消'
      }
    );
  } catch {
    return;
  }
  saving.value = true;
  try {
    await api.request(AdminEndpoints.otaReleases, 'POST', {
      appVersion: form.appVersion.trim(),
      channel: form.channel.trim() || 'stable',
      downloadUrl: form.downloadUrl.trim(),
      checksumSha256: form.checksumSha256.trim() || undefined,
      releaseNotes: form.releaseNotes.trim() || undefined,
      mandatory: form.mandatory,
      minVersion: form.minVersion.trim() || undefined,
      grayPercent: form.grayPercent,
      deviceAllowlist: form.deviceAllowlist.length ? form.deviceAllowlist : undefined,
      status: 'PUBLISHED'
    });
    ElMessage.success('已发布');
    dialog.value = false;
    await crud.load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '发布失败');
  } finally {
    saving.value = false;
  }
}

async function unpublish(row: OtaRelease) {
  if (row.releaseId == null) {
    ElMessage.error('该版本缺少 ID，无法下架');
    return;
  }
  try {
    await ElMessageBox.confirm(
      `确认下架版本 ${row.appVersion}？设备端将停止收到该版本。`,
      '下架版本',
      {
        type: 'warning',
        confirmButtonText: '下架',
        cancelButtonText: '取消'
      }
    );
  } catch {
    return;
  }
  try {
    await api.request(AdminEndpoints.otaReleaseUnpublish(row.releaseId), 'POST', {});
    ElMessage.success('已下架');
    await crud.load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '下架失败');
  }
}

/** 批量下架勾选中的已发布版本。 */
async function batchUnpublish() {
  const targets = unpublishableSelected.value;
  if (!targets.length) {
    ElMessage.warning(
      crud.hasSelection ? '勾选行中没有「已发布」的版本' : '请先勾选需要下架的版本'
    );
    return;
  }
  try {
    await ElMessageBox.confirm(
      `确认下架 ${targets.length} 个已发布版本？设备端将停止收到这些版本。`,
      '批量下架',
      {
        type: 'warning',
        confirmButtonText: '下架',
        cancelButtonText: '取消'
      }
    );
  } catch {
    return;
  }
  batchUnpublishing.value = true;
  let ok = 0;
  let fail = 0;
  try {
    for (const row of targets) {
      try {
        await api.request(AdminEndpoints.otaReleaseUnpublish(row.releaseId), 'POST', {});
        ok += 1;
      } catch {
        fail += 1;
      }
    }
    if (fail === 0) {
      ElMessage.success(`已下架 ${ok} 个版本`);
    } else {
      ElMessage.warning(`成功 ${ok} 个，失败 ${fail} 个`);
    }
    await crud.load();
  } finally {
    batchUnpublishing.value = false;
  }
}

// 主列表由 useCrudTable 挂载后自动首查；这里只补拉设备升级进度
onMounted(() => {
  void loadProgress();
});
</script>

<style scoped>
.field-hint {
  font-size: var(--admin-font-size-sm);
  color: var(--el-text-color-secondary);
  line-height: 1.5;
  margin-top: 4px;
}
</style>
