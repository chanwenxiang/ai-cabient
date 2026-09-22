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
            v-if="crud.hasSelection && canDelete"
            type="danger"
            :loading="batchLoading"
            @click="batchDelete"
          >
            批量删除
          </el-button>
          <el-button v-if="canEdit" :loading="testingAlert" @click="onTestAlertChannels">
            测试发送
          </el-button>
          <el-button v-if="canEdit" type="primary" @click="openCreate">新增</el-button>
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

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <CrudTable
          :table="crud"
          row-key="configKey"
          selectable
          :actions="showActionColumn ? rowActions : undefined"
          :action-width="140"
          actions-testid="alert-rule"
          empty-text="暂无告警规则"
          :csv="csvOptions"
          @action="onRowAction"
        >
          <el-table-column
            label="分组"
            width="140"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">{{ row.group }}</template>
          </el-table-column>
          <el-table-column label="规则说明" min-width="220" class-name="col-text">
            <template #default="{ row }">{{ row.description || '暂无' }}</template>
          </el-table-column>
          <el-table-column label="配置键" min-width="200" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-id">{{ row.configKey }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="单位/提示"
            width="110"
            class-name="col-text"
            label-class-name="col-text"
          >
            <template #default="{ row }">{{ ruleUnitHint(row.configKey) }}</template>
          </el-table-column>
          <el-table-column
            align="center"
            label="当前值"
            min-width="160"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <template v-if="row.configKey.endsWith('_enabled')">
                <el-tag :type="row.configValue === 'true' ? 'success' : 'info'" size="small">
                  {{ row.configValue === 'true' ? '开' : '关' }}
                </el-tag>
              </template>
              <span v-else>{{ displayValue(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="更新时间"
            width="150"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">{{
              row.updatedAt ? formatDateTime(row.updatedAt) : '暂无'
            }}</template>
          </el-table-column>
        </CrudTable>
      </div>
    </div>

    <el-dialog
      v-model="dialogVisible"
      :title="creating ? '新增告警规则' : '编辑告警规则'"
      destroy-on-close
    >
      <el-form label-width="auto">
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
            <el-option v-for="k in creatableBuiltinKeys" :key="k" :label="k" :value="k" />
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
import { computed, reactive, ref } from 'vue';
import { Delete, EditPen } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import CrudTable, { type CrudCsvOptions, type CrudRowAction } from '@/components/CrudTable.vue';
import { useCrudTable, type CrudPageParams } from '@/composables/useCrudTable';
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

/** 试发结果：detail 为空表示已投递，否则是平台给出的拒绝原因（如飞书 code=19024）。 */
interface AlertChannelProbe {
  channel: string;
  delivered: boolean;
  detail?: string | null;
}

const BUILTIN_GROUPS: Record<string, string[]> = {
  设备离线与解锁: [
    'device.offline.auto_sales_lock_minutes',
    'device.offline.manual_unlock_grace_minutes',
    'device.offline.auto_unlock_enabled',
    'device.offline.auto_unlock_stable_minutes'
  ],
  温控告警: ['device.temp.alert_max_c'],
  '争议 SLA': ['dispute.sla.hours', 'dispute.sla.reminder_hours', 'dispute.sla.webhook'],
  告警渠道: [
    'ops.alert.feishu_webhook',
    'ops.alert.feishu_sign_secret',
    'ops.alert.dingtalk_webhook',
    'ops.alert.wecom_webhook',
    'ops.alert.webhook'
  ],
  告警升级链: [
    'ops.alert.escalation_enabled',
    'ops.alert.escalation_types',
    'ops.alert.oncall_roster',
    'ops.alert.escalation_sms_webhook',
    'ops.alert.escalation_phone_webhook'
  ],
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
const saving = ref(false);
const batchLoading = ref(false);
const testingAlert = ref(false);
const keyword = ref('');
/** 全量派生规则（未做关键词过滤），供「新增」白名单键排除已存在键使用 */
const rows = ref<RuleRow[]>([]);
const customGroupMap = ref<Record<string, string>>({});

// 列表状态机统一交给 CrudTable：分页 / 多选 / 竞态 / 空态 / CSV / 刷新全部内建。
// 本页无服务端分页：fetchPage 拉全量配置 → 客户端按关键词过滤（随查询生效）→ 前端切片成一页。
async function fetchPage(params: CrudPageParams) {
  const all = await api.request<SystemConfigRow[]>(AdminEndpoints.systemConfigs, 'GET');
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

  const kw = keyword.value.trim().toLowerCase();
  const filtered = kw
    ? out.filter(
        (row) =>
          String(row.group || '')
            .toLowerCase()
            .includes(kw) ||
          String(row.configKey || '')
            .toLowerCase()
            .includes(kw) ||
          String(row.description || '')
            .toLowerCase()
            .includes(kw)
      )
    : out;
  const start = params.page * params.size;
  return { items: filtered.slice(start, start + params.size), total: filtered.length };
}

const crud = useCrudTable<RuleRow>({
  rowKey: (r) => r.configKey,
  fetchPage,
  // 数据量为白名单键量级（24 个内置键），默认一页展示完，最接近原「整表一次渲染」
  pageSize: 50
});

const csvOptions: CrudCsvOptions = {
  filePrefix: '告警规则',
  exportPerm: 'ops:config:export',
  headers: ['分组', '配置键', '规则说明', '当前值', '更新时间'],
  toRows: (picked) =>
    picked.map((r) => [
      r.group,
      r.configKey,
      r.description || '',
      displayValue(r),
      r.updatedAt ? formatDateTime(r.updatedAt) : ''
    ])
};

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
    key.startsWith('device.temp.') ||
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
  if (key.endsWith('_c') || key.includes('temp')) return '℃';
  return '暂无';
}

function rowActions(row: RuleRow): CrudRowAction[] {
  const acts: CrudRowAction[] = [
    { key: 'edit', label: '编辑', icon: EditPen, type: 'primary', perm: 'ops:config:edit' }
  ];
  if (isCustomKey(row.configKey)) {
    acts.push({
      key: 'delete',
      label: '删除',
      icon: Delete,
      type: 'danger',
      perm: 'ops:config:delete'
    });
  }
  return acts;
}

function isCustomKey(key: string) {
  return !builtinGroupOf(key);
}

/** 关键词为客户端过滤（fetchPage 内生效），查询/回车/清空触发重查 */
function search() {
  void crud.search();
}

function reset() {
  keyword.value = '';
  void crud.search();
}

async function batchDelete() {
  const targets = crud.pickSelected(crud.displayItems).filter((r) => isCustomKey(r.configKey));
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
      await api.request(AdminEndpoints.systemConfig(row.configKey), 'DELETE');
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
  await crud.load();
}

async function onRowAction({ key, row }: { key: string; row: RuleRow }) {
  if (key === 'edit') openEdit(row);
  else if (key === 'delete') await onDelete(row);
}

async function persistCustomGroups(next: Record<string, string>) {
  customGroupMap.value = next;
  await api.request(AdminEndpoints.systemConfigs, 'PUT', {
    configKey: GROUP_META_KEY,
    configValue: JSON.stringify(next),
    description: '告警规则页自定义分组映射（内部）'
  });
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
  if (creating.value && !builtinGroupOf(configKey)) {
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
    await api.request(AdminEndpoints.systemConfigs, 'PUT', {
      configKey,
      configValue: value || (configKey.endsWith('_enabled') ? 'false' : ''),
      description: form.description.trim()
    });
    ElMessage.success('已保存并生效');
    dialogVisible.value = false;
    await crud.load();
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
    await api.request(AdminEndpoints.systemConfig(row.configKey), 'DELETE');
    if (customGroupMap.value[row.configKey]) {
      const next = { ...customGroupMap.value };
      delete next[row.configKey];
      await persistCustomGroups(next);
    }
    ElMessage.success('已删除');
    await crud.load();
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '删除失败');
    }
  }
}

/**
 * 试发一条测试告警到所有已配置渠道。
 *
 * 告警渠道（尤其飞书）配错时 HTTP 仍是 200，只在响应体里带业务码，所以「保存成功」
 * 不等于「收得到」。这个按钮把每个渠道的真实投递结果（含平台业务码）直接摊开，
 * 不需要任何监控栈就能确认通不通。
 */
async function onTestAlertChannels() {
  testingAlert.value = true;
  try {
    const probes = await api.request<AlertChannelProbe[]>(
      AdminEndpoints.systemConfigAlertTest,
      'POST',
      {}
    );
    if (!probes.length) {
      ElMessage.warning('没有已配置的告警渠道：请先填写对应渠道的 Webhook URL 并保存');
      return;
    }
    const lines = probes.map(
      (p) => `${p.channel}：${p.delivered ? '已投递' : `投递失败 —— ${p.detail || '未返回原因'}`}`
    );
    const allOk = probes.every((p) => p.delivered);
    await ElMessageBox.alert(
      `<div style="line-height:1.9">${lines.map((l) => `<div>${escapeHtml(l)}</div>`).join('')}</div>`,
      allOk ? '测试发送：全部成功' : '测试发送：存在失败',
      { dangerouslyUseHTMLString: true, confirmButtonText: '知道了' }
    );
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '测试发送失败');
  } finally {
    testingAlert.value = false;
  }
}

/** 渠道返回的业务码文案来自外部平台，拼进 HTML 前一律转义。 */
function escapeHtml(s: string) {
  return s.replaceAll(
    /[&<>"']/g,
    (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c] || c
  );
}
</script>
