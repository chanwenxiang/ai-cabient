<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">告警规则</span>
            <span class="hint"
              >与「参数配置」同源；仅白名单键会被调度读取，不可再新建任意自定义键</span
            >
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button
            v-if="hasSelection && canDelete"
            type="danger"
            :loading="batchLoading"
            @click="batchDelete"
          >
            批量删除
          </el-button>
          <el-button @click="onExport">{{ exportButtonLabel }}</el-button>
          <el-button v-if="canEdit" type="primary" @click="openCreate">新增</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="关键词">
        <el-input
          v-model="keyword"
          clearable
          placeholder="分组 / 配置键 / 说明"
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

    <div v-loading="loading" class="table-scroll">
      <div class="table-scroll-inner">
        <el-table
          ref="tableRef"
          :data="displayRows"
          stripe
          border
          class="report-table"
          row-key="configKey"
          empty-text=" "
          @selection-change="onSelectionChange"
        >
          <template #empty>
            <el-empty description="暂无告警规则" />
          </template>
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column label="分组" width="140" align="center">
            <template #default="{ row }">{{ row.group }}</template>
          </el-table-column>
          <el-table-column label="规则说明" min-width="220" align="center" class-name="col-text">
            <template #default="{ row }">{{ row.description || '暂无' }}</template>
          </el-table-column>
          <el-table-column label="配置键" min-width="200" align="center" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-id">{{ row.configKey }}</span>
            </template>
          </el-table-column>
          <el-table-column label="单位/提示" width="110" align="center">
            <template #default="{ row }">{{ ruleUnitHint(row.configKey) }}</template>
          </el-table-column>
          <el-table-column label="当前值" min-width="160" align="center" class-name="col-text">
            <template #default="{ row }">
              <template v-if="row.configKey.endsWith('_enabled')">
                <el-tag :type="row.configValue === 'true' ? 'success' : 'info'" size="small">
                  {{ row.configValue === 'true' ? '开' : '关' }}
                </el-tag>
              </template>
              <span v-else>{{ displayValue(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="更新时间" width="150" align="center">
            <template #default="{ row }">{{
              row.updatedAt ? formatDateTime(row.updatedAt) : '暂无'
            }}</template>
          </el-table-column>
          <el-table-column
            v-if="showActionColumn"
            label="操作"
            width="140"
            align="center"
            class-name="col-action"
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

    <el-dialog
      v-model="dialogVisible"
      :title="creating ? '新增告警规则' : '编辑告警规则'"
      width="520px"
      destroy-on-close
    >
      <el-form label-width="88px">
        <el-form-item label="分组" required>
          <el-select v-model="form.group" filterable style="width: 100%" :disabled="creating">
            <el-option v-for="g in Object.keys(BUILTIN_GROUPS)" :key="g" :label="g" :value="g" />
          </el-select>
        </el-form-item>
        <el-form-item label="配置键" required>
          <el-select
            v-if="creating"
            v-model="form.configKey"
            filterable
            style="width: 100%"
            placeholder="仅可选白名单键"
            @change="onCreateKeyChange"
          >
            <el-option
              v-for="k in creatableBuiltinKeys"
              :key="k"
              :label="k"
              :value="k"
            />
          </el-select>
          <el-input v-else v-model="form.configKey" disabled />
        </el-form-item>
        <el-form-item label="当前值" required>
          <el-switch
            v-if="form.configKey.endsWith('_enabled')"
            v-model="formEnabled"
            active-text="开"
            inactive-text="关"
          />
          <el-input
            v-else
            v-model="form.configValue"
            type="textarea"
            :rows="3"
            placeholder="请输入"
          />
        </el-form-item>
        <el-form-item label="规则说明">
          <el-input v-model="form.description" placeholder="展示在列表中的说明" />
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
import { computed, onMounted, reactive, ref } from 'vue';
import { Delete, EditPen, Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import { useAdminListTable } from '@/composables/useAdminListTable';
import { useListCsv } from '@/composables/useListCsv';
import { useAuthStore } from '@/stores/auth';
import { formatDateTime } from '@aicabinet/shared-uni/format';

interface SystemConfigRow {
  configKey: string;
  configValue: string;
  description?: string;
  updatedAt?: string;
}

interface RuleRow extends SystemConfigRow {
  group: string;
}

const BUILTIN_GROUPS: Record<string, string[]> = {
  设备离线与解锁: [
    'device.offline.auto_sales_lock_minutes',
    'device.offline.manual_unlock_grace_minutes',
    'device.offline.auto_unlock_enabled',
    'device.offline.auto_unlock_stable_minutes'
  ],
  '争议 SLA': ['dispute.sla.hours', 'dispute.sla.reminder_hours', 'dispute.sla.webhook'],
  告警渠道: ['ops.alert.dingtalk_webhook', 'ops.alert.wecom_webhook', 'ops.alert.webhook'],
  卡点扫描: [
    'ops.scan.door_open_minutes',
    'ops.scan.upload_stuck_minutes',
    'ops.scan.recognition_stuck_minutes',
    'ops.scan.settlement_stuck_minutes'
  ],
  自动处置: ['order.unpaid.auto_cancel_hours', 'recharge.pending.auto_cancel_minutes']
};

const CUSTOM_GROUP_PREFIX = '自定义';
const GROUP_META_KEY = 'ops.alert.rule_groups_json';

const auth = useAuthStore();
const loading = ref(false);
const saving = ref(false);
const batchLoading = ref(false);
const rows = ref<RuleRow[]>([]);
const {
  tableRef,
  keyword,
  hasSelection,
  onSelectionChange,
  pickSelected,
  exportButtonLabel,
  clearSelection,
  filterByKeyword,
  resetKeyword
} = useAdminListTable<RuleRow>((r) => r.configKey);

const displayRows = computed(() =>
  filterByKeyword(rows.value, (row, kw) => {
    return (
      String(row.group || '')
        .toLowerCase()
        .includes(kw) ||
      String(row.configKey || '')
        .toLowerCase()
        .includes(kw) ||
      String(row.description || '')
        .toLowerCase()
        .includes(kw)
    );
  })
);

const { onExport } = useListCsv({
  filePrefix: '告警规则',
  headers: ['分组', '配置键', '规则说明', '当前值', '更新时间'],
  toRows: () =>
    pickSelected(displayRows.value).map((r) => [
      r.group,
      r.configKey,
      r.description || '',
      displayValue(r),
      r.updatedAt ? formatDateTime(r.updatedAt) : ''
    ])
});
const customGroupMap = ref<Record<string, string>>({});
const dialogVisible = ref(false);
const creating = ref(false);
const form = reactive({
  group: '告警渠道',
  configKey: '',
  configValue: '',
  description: ''
});

const canEdit = computed(() => auth.hasPerm('ops:config:edit'));
const canDelete = computed(() => auth.hasPerm('ops:config:delete'));
const showActionColumn = computed(() => canEdit.value || canDelete.value);

const formEnabled = computed({
  get: () => form.configValue === 'true',
  set: (v: boolean) => {
    form.configValue = String(v);
  }
});

const groupOptions = computed(() => Object.keys(BUILTIN_GROUPS));

const allBuiltinKeys = computed(() => Object.values(BUILTIN_GROUPS).flat());

const creatableBuiltinKeys = computed(() => {
  const existing = new Set(rows.value.map((r) => r.configKey));
  return allBuiltinKeys.value.filter((k) => !existing.has(k));
});

function onCreateKeyChange(key: string) {
  const g = builtinGroupOf(key);
  if (g) form.group = g;
}

function builtinGroupOf(key: string): string | null {
  for (const [group, keys] of Object.entries(BUILTIN_GROUPS)) {
    if (keys.includes(key)) return group;
  }
  return null;
}

function resolveGroup(key: string): string {
  return builtinGroupOf(key) || customGroupMap.value[key] || CUSTOM_GROUP_PREFIX;
}

function isAlertRelated(key: string): boolean {
  if (key === GROUP_META_KEY) return false;
  if (builtinGroupOf(key)) return true;
  if (customGroupMap.value[key]) return true;
  return (
    key.startsWith('ops.alert.') ||
    key.startsWith('ops.scan.') ||
    key.startsWith('device.offline.') ||
    key.startsWith('dispute.sla.') ||
    key.startsWith('order.unpaid.') ||
    key.startsWith('recharge.pending.')
  );
}

function displayValue(row: RuleRow) {
  const v = String(row.configValue ?? '').trim();
  return v || '暂无';
}

function ruleUnitHint(key: string) {
  if (key.endsWith('_enabled')) return '开关';
  if (key.includes('webhook')) return 'URL';
  if (key.includes('minutes')) return '分钟';
  if (key.includes('hours')) return '小时';
  return '暂无';
}

function rowActions(row: RuleRow): TableAction[] {
  const acts: TableAction[] = [];
  if (canEdit.value) {
    acts.push({ key: 'edit', label: '编辑', icon: EditPen, type: 'primary' });
  }
  if (canDelete.value && isCustomKey(row.configKey)) {
    acts.push({ key: 'delete', label: '删除', icon: Delete, type: 'danger' });
  }
  return acts;
}

function isCustomKey(key: string) {
  return !builtinGroupOf(key);
}

function search() {
  /* client-side filter only */
}

function reset() {
  resetKeyword();
}

async function batchDelete() {
  const targets = pickSelected(displayRows.value).filter((r) => isCustomKey(r.configKey));
  if (!targets.length) {
    ElMessage.warning('请先勾选自定义告警规则（内置键不可批量删除）');
    return;
  }
  try {
    await ElMessageBox.confirm(`确认删除选中的 ${targets.length} 条自定义规则？`, '批量删除', {
      type: 'warning'
    });
  } catch {
    return;
  }
  batchLoading.value = true;
  const results = await Promise.allSettled(
    targets.map(async (row) => {
      await api.request(
        `/api/v2/ops/admin/system-configs/${encodeURIComponent(row.configKey)}`,
        'DELETE'
      );
      if (customGroupMap.value[row.configKey]) {
        const next = { ...customGroupMap.value };
        delete next[row.configKey];
        await persistCustomGroups(next);
      }
    })
  );
  batchLoading.value = false;
  const ok = results.filter((r) => r.status === 'fulfilled').length;
  ElMessage.success(`批量删除完成：成功 ${ok}，失败 ${targets.length - ok}`);
  await load();
}

async function onRowAction(key: string, row: RuleRow) {
  if (key === 'edit') openEdit(row);
  else if (key === 'delete') await onDelete(row);
}

async function persistCustomGroups(next: Record<string, string>) {
  customGroupMap.value = next;
  await api.request('/api/v2/ops/admin/system-configs', 'PUT', {
    configKey: GROUP_META_KEY,
    configValue: JSON.stringify(next),
    description: '告警规则页自定义分组映射（内部）'
  });
}

async function load() {
  loading.value = true;
  try {
    const all = await api.request<SystemConfigRow[]>('/api/v2/ops/admin/system-configs', 'GET');
    const byKey = new Map(all.map((r) => [r.configKey, r]));
    const meta = byKey.get(GROUP_META_KEY);
    let map: Record<string, string> = {};
    if (meta?.configValue) {
      try {
        map = JSON.parse(meta.configValue) as Record<string, string>;
      } catch {
        map = {};
      }
    }
    customGroupMap.value = map;

    const out: RuleRow[] = [];
    const seen = new Set<string>();
    for (const [group, keys] of Object.entries(BUILTIN_GROUPS)) {
      for (const key of keys) {
        const row = byKey.get(key);
        if (row) {
          out.push({ ...row, group });
          seen.add(key);
        }
      }
    }
    for (const row of all) {
      if (seen.has(row.configKey) || !isAlertRelated(row.configKey)) continue;
      out.push({ ...row, group: resolveGroup(row.configKey) });
    }
    rows.value = out;
    clearSelection();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  if (!creatableBuiltinKeys.value.length) {
    ElMessage.info('白名单键均已存在，请直接编辑列表项');
    return;
  }
  creating.value = true;
  const first = creatableBuiltinKeys.value[0];
  form.configKey = first;
  form.group = builtinGroupOf(first) || '告警渠道';
  form.configValue = '';
  form.description = '';
  dialogVisible.value = true;
}

function openEdit(row: RuleRow) {
  creating.value = false;
  form.group = row.group || resolveGroup(row.configKey);
  form.configKey = row.configKey;
  form.configValue = row.configValue ?? '';
  form.description = row.description || '';
  dialogVisible.value = true;
}

async function save() {
  const configKey = form.configKey.trim();
  const configValue = String(form.configValue ?? '').trim();
  if (!configKey) {
    ElMessage.warning('请选择配置键');
    return;
  }
  if (creating && !builtinGroupOf(configKey)) {
    ElMessage.warning('仅允许白名单键；自定义键不会被调度读取');
    return;
  }
  if (!configValue && !configKey.endsWith('_enabled')) {
    ElMessage.warning('请填写当前值');
    return;
  }
  let value: string;
  if (configKey.endsWith('_enabled')) {
    value = configValue === 'true' ? 'true' : 'false';
  } else {
    value = configValue;
  }
  saving.value = true;
  try {
    await api.request('/api/v2/ops/admin/system-configs', 'PUT', {
      configKey,
      configValue: value || (configKey.endsWith('_enabled') ? 'false' : ''),
      description: form.description.trim()
    });
    ElMessage.success('已保存并生效');
    dialogVisible.value = false;
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

async function onDelete(row: RuleRow) {
  try {
    await ElMessageBox.confirm(
      `确认删除告警规则「${row.configKey}」？系统默认项删除后可能被重新初始化。`,
      '删除告警规则',
      { type: 'warning' }
    );
    await api.request(
      `/api/v2/ops/admin/system-configs/${encodeURIComponent(row.configKey)}`,
      'DELETE'
    );
    if (customGroupMap.value[row.configKey]) {
      const next = { ...customGroupMap.value };
      delete next[row.configKey];
      await persistCustomGroups(next);
    }
    ElMessage.success('已删除');
    await load();
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '删除失败');
    }
  }
}

onMounted(load);
</script>
