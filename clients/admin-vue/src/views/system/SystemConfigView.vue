<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">参数配置</span>
            <span class="hint">系统键值配置；支持导入导出</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button @click="onExport">{{ exportButtonLabel }}</el-button>
          <el-button v-if="canImport" @click="onDownloadTemplate(['demo.config.key', 'value', '说明', ''])">导入模板</el-button>
          <el-button v-if="canImport" :loading="importing" @click="triggerImport">导入</el-button>
          <input ref="importInput" type="file" accept=".csv,text/csv" class="hidden-input" @change="onImportFile" />
          <el-button type="primary" @click="openCreate">新增</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="关键词">
        <el-input
          v-model="keyword"
          clearable
          placeholder="配置键 / 值 / 说明"
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
      <div class="table-scroll-inner" style="min-width: 900px">
        <el-table
          v-loading="loading"
          :data="paged"
          stripe
          border
          class="report-table"
          row-key="configKey"
          @selection-change="onSelectionChange"
        >
          <template #empty><el-empty description="暂无参数" /></template>
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column label="配置" min-width="220" class-name="col-text">
            <template #default="{ row }">
              <div class="name-cell">
                <strong class="cell-id">{{ row.configKey }}</strong>
                <small>{{ row.description || '无说明' }}</small>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="配置值" min-width="200" class-name="col-text" show-overflow-tooltip>
            <template #default="{ row }">{{ row.configValue || '-' }}</template>
          </el-table-column>
          <el-table-column label="更新时间" width="168" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.updatedAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="88" class-name="col-action" align="center" fixed="right">
            <template #default="{ row }">
              <TableActions
                :actions="[{ key: 'edit', label: '编辑', icon: EditPen, type: 'primary' }]"
                @action="() => openEdit(row)"
              />
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <div class="page-pager">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="filtered.length"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        background
      />
    </div>

    <el-dialog v-model="dialogVisible" :title="creating ? '新增参数' : '编辑参数'" width="480px" destroy-on-close>
      <el-form label-width="88px">
        <el-form-item label="配置键" required>
          <el-input v-model="form.configKey" :disabled="!creating" placeholder="例如 consumer.service_phone" />
        </el-form-item>
        <el-form-item label="配置值" required>
          <el-input v-model="form.configValue" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { EditPen, Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import TableActions from '@/components/TableActions.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useTableSelection } from '@/composables/useTableSelection';
import { formatDateTime } from '@aicabinet/shared-uni/format';

interface SystemConfigRow {
  configKey: string;
  configValue: string;
  description?: string;
  updatedAt?: string;
}

const route = useRoute();
const router = useRouter();
const loading = ref(false);
const saving = ref(false);
const keyword = ref('');
const page = ref(1);
const size = ref(20);
const items = ref<SystemConfigRow[]>([]);
const dialogVisible = ref(false);
const creating = ref(false);
const form = reactive({ configKey: '', configValue: '', description: '' });

const filtered = computed(() => {
  const q = keyword.value.trim().toLowerCase();
  if (!q) return items.value;
  return items.value.filter((row) =>
    [row.configKey, row.configValue, row.description]
      .some((x) => String(x || '').toLowerCase().includes(q))
  );
});

const paged = computed(() => {
  const start = (page.value - 1) * size.value;
  return filtered.value.slice(start, start + size.value);
});

watch(keyword, () => {
  page.value = 1;
});

const { onSelectionChange, pickSelected, exportButtonLabel, clearSelection } =
  useTableSelection<SystemConfigRow>((r) => r.configKey);

const {
  canImport,
  importing,
  importInput,
  onExport,
  onDownloadTemplate,
  triggerImport,
  onImportFile
} = useListCsv({
  filePrefix: '参数配置',
  headers: ['配置键', '配置值', '说明', '更新时间'],
  toRows: () =>
    pickSelected(items.value).map((row) => [
      row.configKey,
      row.configValue,
      row.description || '',
      formatDateTime(row.updatedAt)
    ]),
  onImportRows: async (rows) => {
    let ok = 0;
    for (const row of rows) {
      const configKey = (row['配置键'] || row.configKey || '').trim();
      const configValue = (row['配置值'] || row.configValue || '').trim();
      if (!configKey || !configValue) continue;
      await api.request('/api/v2/ops/admin/system-configs', 'PUT', {
        configKey,
        configValue,
        description: (row['说明'] || row.description || '').trim()
      });
      ok++;
    }
    await load();
    return ok;
  }
});

function syncRouteQuery() {
  const query: Record<string, string> = {};
  if (keyword.value.trim()) query.keyword = keyword.value.trim();
  router.replace({ query });
}

function applyRouteQuery() {
  if (typeof route.query.keyword === 'string' && route.query.keyword !== keyword.value) {
    keyword.value = route.query.keyword;
    return true;
  }
  return false;
}

async function load() {
  loading.value = true;
  try {
    items.value = await api.request<SystemConfigRow[]>('/api/v2/ops/admin/system-configs', 'GET');
    clearSelection();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

function search() {
  page.value = 1;
  syncRouteQuery();
}

function reset() {
  keyword.value = '';
  page.value = 1;
  syncRouteQuery();
}

function openCreate() {
  creating.value = true;
  form.configKey = '';
  form.configValue = '';
  form.description = '';
  dialogVisible.value = true;
}

function openEdit(row: SystemConfigRow) {
  creating.value = false;
  form.configKey = row.configKey;
  form.configValue = row.configValue;
  form.description = row.description || '';
  dialogVisible.value = true;
}

async function save() {
  if (!form.configKey.trim() || !form.configValue.trim()) {
    ElMessage.warning('请填写配置键与配置值');
    return;
  }
  saving.value = true;
  try {
    await api.request('/api/v2/ops/admin/system-configs', 'PUT', {
      configKey: form.configKey.trim(),
      configValue: form.configValue.trim(),
      description: form.description.trim()
    });
    ElMessage.success('已保存');
    dialogVisible.value = false;
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

onMounted(() => {
  applyRouteQuery();
  load();
});
onActivated(() => {
  applyRouteQuery();
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
.title { font-weight: 600; font-size: 15px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.4; }
.page-card-head__actions { display: flex; gap: 8px; flex-wrap: wrap; }
.name-cell { display: grid; gap: 2px; line-height: 1.35; }
.name-cell strong { font-weight: 650; }
.name-cell small {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.hidden-input { display: none; }
</style>
