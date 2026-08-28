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
          <el-button v-hasPermi="['ops:config:export']" @click="onExport">{{
            exportButtonLabel
          }}</el-button>
          <el-button
            v-hasPermi="['ops:config:import']"
            @click="onDownloadTemplate(['demo.config.key', 'value', '说明', ''])"
            >导入模板</el-button
          >
          <el-button v-hasPermi="['ops:config:import']" :loading="importing" @click="triggerImport"
            >导入</el-button
          >
          <input
            ref="importInput"
            type="file"
            accept=".csv,text/csv"
            class="hidden-input"
            @change="onImportFile"
          />
          <el-button v-hasPermi="['ops:config:edit']" type="primary" @click="openCreate"
            >新增</el-button
          >
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
      <div class="table-scroll-inner">
        <el-table
          v-loading="loading"
          :data="paged"
          stripe
          border
          class="report-table"
          row-key="configKey"
          @selection-change="onSelectionChange"
          empty-text=" "
        >
          <template #empty>
            <el-empty v-if="listHydrated && !loading" description="暂无参数" />
          </template>
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column label="配置键" min-width="180" align="center" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-id">{{ row.configKey }}</span>
            </template>
          </el-table-column>
          <el-table-column label="说明" min-width="160" align="center" class-name="col-text">
            <template #default="{ row }">{{ row.description || '无说明' }}</template>
          </el-table-column>
          <el-table-column
            label="配置值"
            min-width="200"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">{{ row.configValue || '无' }}</template>
          </el-table-column>
          <el-table-column label="更新时间" width="168" align="center" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.updatedAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column
            v-if="showActionColumn"
            label="操作"
            width="120"
            class-name="col-action"
            align="center"
            fixed="right"
          >
            <template #default="{ row }">
              <TableActions
                :actions="rowActions(row)"
                @action="(k) => onRowAction(String(k), row)"
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
      :total="filtered.length"
      :page-sizes="[10, 20, 50, 100]"
      layout="total, sizes, prev, pager, next"
      background
    />

    <el-dialog
      v-model="dialogVisible"
      :title="creating ? '新增参数' : '编辑参数'"
      width="480px"
      destroy-on-close
    >
      <el-form label-width="88px">
        <el-form-item label="配置键" required>
          <el-input
            v-model="form.configKey"
            :disabled="!creating"
            placeholder="例如 consumer.service_phone"
          />
        </el-form-item>
        <el-form-item label="配置值" required>
          <el-select
            v-if="valueOptions.length"
            v-model="form.configValue"
            filterable
            allow-create
            default-first-option
            style="width: 100%"
            placeholder="选择或输入"
          >
            <el-option
              v-for="opt in valueOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
          <el-input v-else v-model="form.configValue" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button v-hasPermi="['ops:config:edit']" type="primary" :loading="saving" @click="save"
          >保存</el-button
        >
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Delete, EditPen, Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import PagePager from '@/components/PagePager.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useTableSelection } from '@/composables/useTableSelection';
import { useAuthStore } from '@/stores/auth';
import { formatDateTime } from '@aicabinet/shared-uni/format';
import { sortByPrimaryKey } from '@/utils/sort-by-pk';

interface SystemConfigRow {
  configKey: string;
  configValue: string;
  description?: string;
  updatedAt?: string;
}

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const loading = ref(false);
const listHydrated = ref(false);
const saving = ref(false);
const keyword = ref('');
const page = ref(1);
const size = ref(20);
const items = ref<SystemConfigRow[]>([]);
const dialogVisible = ref(false);
const creating = ref(false);
const form = reactive({ configKey: '', configValue: '', description: '' });

const ENUM_VALUE_OPTIONS: Record<string, { value: string; label: string }[]> = {
  'settlement.recognition_mode': [
    { value: 'VISION', label: 'VISION — 纯视觉（忽略重力）' },
    { value: 'VISION_GRAVITY', label: 'VISION_GRAVITY — 视觉+重力融合' }
  ],
  'refund.default_policy': [
    { value: 'AUTO_REFUND', label: 'AUTO_REFUND — 自助退款' },
    { value: 'DISPUTE_ONLY', label: 'DISPUTE_ONLY — 仅申诉' }
  ]
};

const valueOptions = computed(() => ENUM_VALUE_OPTIONS[form.configKey.trim()] || []);

const filtered = computed(() => {
  const q = keyword.value.trim().toLowerCase();
  const rows = q
    ? items.value.filter((row) =>
        [row.configKey, row.configValue, row.description].some((x) =>
          String(x || '')
            .toLowerCase()
            .includes(q)
        )
      )
    : items.value;
  return sortByPrimaryKey(rows, 'configKey', 'asc');
});

const paged = computed(() => {
  const start = (page.value - 1) * size.value;
  return filtered.value.slice(start, start + size.value);
});

function rowActions(_row: SystemConfigRow): TableAction[] {
  const acts: TableAction[] = [];
  if (auth.hasPerm('ops:config:edit')) {
    acts.push({ key: 'edit', label: '编辑', icon: EditPen, type: 'primary' });
  }
  if (auth.hasPerm('ops:config:delete')) {
    acts.push({ key: 'delete', label: '删除', icon: Delete, type: 'danger' });
  }
  return acts;
}

const showActionColumn = computed(
  () => auth.hasPerm('ops:config:edit') || auth.hasPerm('ops:config:delete')
);

async function onRowAction(key: string, row: SystemConfigRow) {
  if (key === 'edit') openEdit(row);
  else if (key === 'delete') await onDelete(row);
}

async function onDelete(row: SystemConfigRow) {
  try {
    await ElMessageBox.confirm(
      `确认删除参数「${row.configKey}」？系统默认项删除后可能被重新初始化。`,
      '删除参数',
      { type: 'warning' }
    );
    await api.request(
      `/api/v2/ops/admin/system-configs/${encodeURIComponent(row.configKey)}`,
      'DELETE'
    );
    ElMessage.success('已删除');
    await load();
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '删除失败');
    }
  }
}

watch(keyword, () => {
  page.value = 1;
});

const { onSelectionChange, pickSelected, exportButtonLabel, clearSelection } =
  useTableSelection<SystemConfigRow>((r) => r.configKey);

const { importing, importInput, onExport, onDownloadTemplate, triggerImport, onImportFile } =
  useListCsv({
    filePrefix: '参数配置',
    headers: ['配置键', '配置值', '说明', '更新时间'],
    toRows: () =>
      pickSelected(filtered.value).map((row) => [
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
  const qKeyword = typeof route.query.keyword === 'string' ? route.query.keyword : '';
  if (qKeyword !== keyword.value) {
    keyword.value = qKeyword;
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
    listHydrated.value = true;
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

async function reloadFromRouteQuery() {
  if (!applyRouteQuery()) return;
  page.value = 1;
}

watch(
  () => route.query.keyword,
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
  flex-wrap: wrap;
}
.hidden-input {
  display: none;
}
</style>
