<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">定时任务</span>
            <span class="hint"
              >启停即时生效；点「立即执行」后看本表「最近执行 / 最近结果说明」两列（不是另开页面）</span
            >
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="关键词">
        <el-input
          v-model="keyword"
          clearable
          placeholder="任务名 / 标识 / 分组 / 调度"
          style="width: 220px"
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
          :data="paged"
          stripe
          border
          class="report-table"
          row-key="taskKey"
          empty-text=" "
        >
          <template #empty>
            <el-empty v-if="listHydrated && !loading" description="暂无定时任务" />
          </template>
          <el-table-column label="任务名称" min-width="170" align="center" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-id">{{ row.taskName }}</span>
            </template>
          </el-table-column>
          <el-table-column label="任务标识" min-width="200" align="center" class-name="col-text">
            <template #default="{ row }">{{ row.taskKey }}</template>
          </el-table-column>
          <el-table-column label="分组" width="110" align="center">
            <template #default="{ row }">{{
              dictLabel('scheduled_task_group', row.taskGroup)
            }}</template>
          </el-table-column>
          <el-table-column label="调度说明" width="130" align="center">
            <template #default="{ row }">{{ row.scheduleDesc || '—' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="110" align="center">
            <template #default="{ row }">
              <el-switch
                v-if="canEdit"
                :model-value="row.enabled"
                :loading="togglingKey === row.taskKey"
                @change="(v: boolean) => onToggle(row, v)"
              />
              <el-tag v-else :type="row.enabled ? 'success' : 'info'">
                {{ row.enabled ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="最近执行" min-width="200" align="center">
            <template #default="{ row }">
              <template v-if="row.lastRunAt">
                <div>{{ formatDateTime(row.lastRunAt) }}</div>
                <el-tag size="small" :type="resultType(row.lastResult)">
                  {{ resultLabel(row.lastResult) }}
                </el-tag>
                <div v-if="row.lastDurationMs != null" class="cell-hint">
                  耗时 {{ formatDuration(row.lastDurationMs) }}
                </div>
              </template>
              <span v-else class="cell-hint">尚未执行</span>
            </template>
          </el-table-column>
          <el-table-column
            label="最近结果说明"
            min-width="200"
            align="center"
            show-overflow-tooltip
          >
            <template #default="{ row }">{{ row.lastMessage || '—' }}</template>
          </el-table-column>
          <el-table-column label="备注" min-width="220" align="center" show-overflow-tooltip>
            <template #default="{ row }">{{ row.remark || '—' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="190" align="center" class-name="col-action">
            <template #default="{ row }">
              <el-button
                v-if="canRun"
                size="small"
                type="primary"
                plain
                :loading="runningKey === row.taskKey"
                @click="onRun(row)"
              >
                立即执行
              </el-button>
              <el-button v-if="canEdit" size="small" plain @click="openRemark(row)">备注</el-button>
              <span v-if="!canRun && !canEdit" class="cell-hint">—</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <PagePager
      :hydrated="listHydrated"
      v-model:current-page="page"
      v-model:page-size="size"
      :total="filtered.length"
      :page-sizes="[10, 20, 50, 100]"
      layout="total, sizes, prev, pager, next"
      background
    />

    <el-dialog v-model="remarkVisible" title="任务备注" width="480px" destroy-on-close>
      <div class="cell-hint" style="margin-bottom: 8px">
        写清楚这个任务干什么用的，方便运维理解与交接
      </div>
      <el-select
        v-model="remarkTemplate"
        placeholder="选择用途模板（可选）"
        clearable
        style="width: 100%; margin-bottom: 12px"
        @change="applyTemplate"
      >
        <el-option v-for="t in TEMPLATES" :key="t.value" :label="t.label" :value="t.value" />
      </el-select>
      <el-input
        v-model="remarkForm.remark"
        type="textarea"
        :rows="4"
        maxlength="500"
        show-word-limit
        placeholder="例如：离线超时自动锁机停售，恢复稳定在线后可自动解锁"
      />
      <template #footer>
        <el-button @click="remarkVisible = false">取消</el-button>
        <el-button type="primary" :loading="remarkSaving" @click="saveRemark">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import PagePager from '@/components/PagePager.vue';
import { useAuthStore } from '@/stores/auth';
import { dictLabel } from '@aicabinet/shared-dict';
import { formatDateTime } from '@aicabinet/shared-uni/format';

interface ScheduledTaskRow {
  taskKey: string;
  taskName: string;
  taskGroup: string;
  scheduleDesc?: string;
  enabled: boolean;
  lastRunAt?: string;
  lastResult?: string;
  lastMessage?: string;
  lastDurationMs?: number;
  remark?: string;
}

const auth = useAuthStore();
const loading = ref(false);
const listHydrated = ref(false);
const keyword = ref('');
const page = ref(1);
const size = ref(20);
const items = ref<ScheduledTaskRow[]>([]);
const togglingKey = ref('');
const runningKey = ref('');
const remarkVisible = ref(false);
const remarkSaving = ref(false);
const remarkForm = reactive({ taskKey: '', taskName: '', remark: '' });
const remarkTemplate = ref('');

const TEMPLATES = [
  { value: 'device', label: '设备巡检', text: '设备离线巡检、自动锁机/解锁，保障设备在线率' },
  { value: 'finance', label: '资金结算', text: '资金类处理（分账/佣金/保证金等），涉及资金变动' },
  { value: 'recon', label: '对账', text: '渠道对账与差异核对，确保账实一致' },
  { value: 'warehouse', label: '库存仓储', text: '库存、临期、补货相关处理' },
  { value: 'trade', label: '交易订单', text: '订单/会话/支付相关自动处理' },
  { value: 'marketing', label: '营销优惠', text: '优惠券/营销活动状态维护' },
  { value: 'ops', label: '运维监控', text: '异常扫描、SLA、数据一致性巡检' },
  { value: 'other', label: '其他', text: '' }
] as const;

const canEdit = computed(() => auth.hasPerm('ops:task:edit'));
const canRun = computed(() => auth.hasPerm('ops:task:run'));

const filtered = computed(() => {
  const q = keyword.value.trim().toLowerCase();
  if (!q) return items.value;
  return items.value.filter((row) =>
    [row.taskName, row.taskKey, row.taskGroup, row.scheduleDesc].some((x) =>
      String(x || '')
        .toLowerCase()
        .includes(q)
    )
  );
});

const paged = computed(() => {
  const start = (page.value - 1) * size.value;
  return filtered.value.slice(start, start + size.value);
});

function resultType(result?: string) {
  if (result === 'SUCCESS') return 'success';
  if (result === 'FAILED') return 'danger';
  if (result === 'SKIPPED') return 'warning';
  return 'info';
}

function resultLabel(result?: string) {
  return { SUCCESS: '成功', FAILED: '失败', SKIPPED: '跳过' }[result || ''] || result || '—';
}

function formatDuration(ms: number) {
  if (ms < 1000) return `${ms} ms`;
  return `${(ms / 1000).toFixed(1)} s`;
}

function search() {
  page.value = 1;
}

function reset() {
  keyword.value = '';
  page.value = 1;
}

async function load() {
  loading.value = true;
  try {
    items.value = await api.request<ScheduledTaskRow[]>('/api/v2/ops/admin/scheduled-tasks', 'GET');
    listHydrated.value = true;
  } catch (e: any) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

async function onToggle(row: ScheduledTaskRow, enabled: boolean) {
  togglingKey.value = row.taskKey;
  try {
    await api.request(
      `/api/v2/ops/admin/scheduled-tasks/${encodeURIComponent(row.taskKey)}/enabled`,
      'PUT',
      { enabled }
    );
    row.enabled = enabled;
    ElMessage.success(enabled ? `已启用「${row.taskName}」` : `已停用「${row.taskName}」`);
  } catch (e: any) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败');
  } finally {
    togglingKey.value = '';
  }
}

async function onRun(row: ScheduledTaskRow) {
  try {
    await ElMessageBox.confirm(`确认立即执行「${row.taskName}」？`, '立即执行', {
      type: 'warning'
    });
  } catch {
    return;
  }
  runningKey.value = row.taskKey;
  try {
    const res = await api.request<{
      result: string;
      message: string;
      lastMessage?: string;
      lastDurationMs?: number;
    }>(`/api/v2/ops/admin/scheduled-tasks/${encodeURIComponent(row.taskKey)}/run`, 'POST');
    if (res?.result === 'SKIPPED') {
      ElMessage.warning(res.message || '任务已跳过');
    } else {
      ElMessage.success(res?.message || '已执行，请看「最近执行 / 最近结果说明」列');
    }
    await load();
  } catch (e: any) {
    ElMessage.error(e instanceof Error ? e.message : '执行失败');
  } finally {
    runningKey.value = '';
  }
}

function openRemark(row: ScheduledTaskRow) {
  remarkForm.taskKey = row.taskKey;
  remarkForm.taskName = row.taskName;
  remarkForm.remark = row.remark || '';
  remarkTemplate.value = '';
  remarkVisible.value = true;
}

function applyTemplate(value: string) {
  const t = TEMPLATES.find((x) => x.value === value);
  if (t) {
    remarkForm.remark = t.text;
  }
}

async function saveRemark() {
  remarkSaving.value = true;
  try {
    await api.request(
      `/api/v2/ops/admin/scheduled-tasks/${encodeURIComponent(remarkForm.taskKey)}/remark`,
      'PUT',
      { remark: remarkForm.remark.trim() }
    );
    ElMessage.success('备注已保存');
    remarkVisible.value = false;
    await load();
  } catch (e: any) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    remarkSaving.value = false;
  }
}

onMounted(load);
</script>
