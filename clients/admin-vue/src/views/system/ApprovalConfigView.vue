<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">审批流配置</span>
            <span class="hint">用节点图配置多级审批；指派支持部门 / 权限 / 角色 / 指定用户</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-if="canEdit" type="primary" @click="openCreate">新增</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <el-table
          v-loading="loading"
          :data="rows"
          stripe
          border
          class="report-table"
          row-key="defId"
          empty-text=" "
        >
          <template #empty>
            <el-empty v-if="hydrated && !loading" description="暂无审批定义" />
          </template>
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="flow-preview">
                <div class="flow-preview__rail">
                  <div class="flow-chip flow-chip--start">提交</div>
                  <template v-for="(n, i) in sortedNodes(row.nodes)" :key="n.nodeId || i">
                    <div class="flow-arrow" aria-hidden="true" />
                    <div class="flow-chip">
                      <span class="flow-chip__seq">{{ i + 1 }}</span>
                      <span class="flow-chip__name">{{ n.nodeName }}</span>
                      <span class="flow-chip__meta">{{ assigneeBrief(n) }}</span>
                    </div>
                  </template>
                  <div class="flow-arrow" aria-hidden="true" />
                  <div class="flow-chip flow-chip--end">通过 / 驳回</div>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="业务" min-width="160" align="center">
            <template #default="{ row }">
              <div>{{ bizLabel(row.bizType) }}</div>
              <div class="cell-hint">{{ row.bizType }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="defName" label="名称" min-width="140" align="center" />
          <el-table-column label="启用" width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
                {{ row.enabled ? '是' : '否' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="流程预览" min-width="280" align="center">
            <template #default="{ row }">
              <span class="flow-inline">
                {{ ['提交', ...sortedNodes(row.nodes).map((n) => n.nodeName), '结束'].join(' → ') }}
              </span>
            </template>
          </el-table-column>
          <el-table-column
            prop="remark"
            label="备注"
            min-width="160"
            show-overflow-tooltip
            align="center"
          />
          <el-table-column
            v-if="canEdit"
            label="操作"
            width="160"
            align="center"
            class-name="col-action"
            fixed="right"
          >
            <template #default="{ row }">
              <TableActions :actions="rowActions()" @action="(k) => onRowAction(String(k), row)" />
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>
  </el-card>

  <el-dialog
    v-model="metaDlg"
    :title="creating ? '新增审批流' : '编辑审批流'"
    width="520px"
    destroy-on-close
  >
    <el-form label-width="88px">
      <el-form-item label="业务类型" required>
        <el-select
          v-if="creating"
          v-model="metaForm.bizType"
          filterable
          allow-create
          default-first-option
          placeholder="选择或输入业务码"
          style="width: 100%"
        >
          <el-option
            v-for="opt in bizTypeOptions"
            :key="opt.value"
            :label="`${opt.label} (${opt.value})`"
            :value="opt.value"
          />
        </el-select>
        <el-input
          v-else
          :model-value="`${bizLabel(metaForm.bizType)} · ${metaForm.bizType}`"
          disabled
        />
      </el-form-item>
      <el-form-item label="名称" required>
        <el-input v-model="metaForm.defName" placeholder="审批流名称" />
      </el-form-item>
      <el-form-item label="启用">
        <el-switch v-model="metaForm.enabled" />
      </el-form-item>
      <el-form-item label="备注">
        <el-input
          v-model="metaForm.remark"
          type="textarea"
          :rows="2"
          maxlength="256"
          show-word-limit
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="metaDlg = false">取消</el-button>
      <el-button type="primary" :loading="metaSaving" @click="saveMeta">保存</el-button>
    </template>
  </el-dialog>

  <el-dialog
    v-model="dlg"
    :title="`流程图 · ${bizLabel(editForm.bizType)}`"
    width="720px"
    top="4vh"
    destroy-on-close
    class="flow-dialog"
  >
    <div class="flow-meta">
      <el-form inline label-width="56px" class="flow-meta__form">
        <el-form-item label="名称">
          <el-input v-model="editForm.defName" style="width: 200px" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="editForm.enabled" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="editForm.remark" style="width: 260px" placeholder="可选" />
        </el-form-item>
      </el-form>
    </div>

    <div class="flow-canvas">
      <div class="flow-node flow-node--terminal">
        <div class="flow-node__badge">开始</div>
        <div class="flow-node__title">业务提交</div>
        <div class="flow-node__sub">{{ bizLabel(editForm.bizType) }}</div>
      </div>

      <button type="button" class="flow-insert" title="在此处插入节点" @click="insertNode(0)">
        <span class="flow-insert__line" />
        <span class="flow-insert__plus">+</span>
        <span class="flow-insert__line" />
      </button>

      <template v-for="(n, idx) in editForm.nodes" :key="n._key">
        <div class="flow-node" :class="{ 'is-active': activeIdx === idx }" @click="activeIdx = idx">
          <div class="flow-node__head">
            <span class="flow-node__badge">节点 {{ idx + 1 }}</span>
            <div class="flow-node__actions" @click.stop>
              <el-button
                link
                :disabled="idx === 0"
                :icon="ArrowUp"
                title="上移"
                @click="moveNode(idx, -1)"
              />
              <el-button
                link
                :disabled="idx === editForm.nodes.length - 1"
                :icon="ArrowDown"
                title="下移"
                @click="moveNode(idx, 1)"
              />
              <el-button
                link
                type="danger"
                :icon="Delete"
                title="删除"
                :disabled="editForm.nodes.length <= 1"
                @click="removeNode(idx)"
              />
            </div>
          </div>
          <el-input
            v-model="n.nodeName"
            class="flow-node__name"
            placeholder="节点名称"
            @click.stop
          />
          <div class="flow-node__fields" @click.stop>
            <el-select v-model="n.assigneeType" placeholder="指派类型" style="width: 100%">
              <el-option
                v-for="opt in assigneeTypeOptions"
                :key="opt.value"
                :label="opt.label"
                :value="opt.value"
              />
            </el-select>
            <el-select
              v-if="n.assigneeType === 'DEPT'"
              v-model="n.assigneeValue"
              placeholder="选择部门"
              filterable
              style="width: 100%"
            >
              <el-option
                v-for="d in departments"
                :key="d.deptKey"
                :label="`${d.deptName} (${d.deptKey})`"
                :value="d.deptKey"
              />
            </el-select>
            <el-input
              v-else
              v-model="n.assigneeValue"
              :placeholder="assigneeValuePlaceholder(n.assigneeType)"
            />
            <el-select v-model="n.passRule" style="width: 100%">
              <el-option value="ANY" label="通过规则：任一处理人同意" />
              <el-option value="ALL" label="通过规则：全部处理人同意" />
            </el-select>
          </div>
          <div class="flow-node__footer">{{ assigneeBrief(n) }}</div>
        </div>

        <button
          type="button"
          class="flow-insert"
          title="在此处插入节点"
          @click="insertNode(idx + 1)"
        >
          <span class="flow-insert__line" />
          <span class="flow-insert__plus">+</span>
          <span class="flow-insert__line" />
        </button>
      </template>

      <div class="flow-ends">
        <div class="flow-node flow-node--terminal flow-node--ok">
          <div class="flow-node__badge">结束</div>
          <div class="flow-node__title">审批通过</div>
        </div>
        <div class="flow-node flow-node--terminal flow-node--reject">
          <div class="flow-node__badge">结束</div>
          <div class="flow-node__title">审批驳回</div>
        </div>
      </div>
    </div>

    <template #footer>
      <el-button @click="dlg = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存流程图</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { ArrowDown, ArrowUp, Delete, EditPen, Refresh, Share } from '@element-plus/icons-vue';
import { api } from '@/api/client';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import { useAuthStore } from '@/stores/auth';
import { dictLabel, dictOptions } from '@aicabinet/shared-dict';

interface ApprovalNode {
  nodeId?: number;
  seq: number;
  nodeName: string;
  assigneeType: string;
  assigneeValue: string;
  passRule: string;
  _key?: string;
}

interface ApprovalDef {
  defId: number;
  bizType: string;
  defName: string;
  enabled: boolean;
  remark?: string;
  nodes: ApprovalNode[];
}

interface DeptRow {
  deptKey: string;
  deptName: string;
}

let keySeq = 0;
function nextKey() {
  keySeq += 1;
  return `n-${Date.now()}-${keySeq}`;
}

const auth = useAuthStore();
const canEdit = computed(() => auth.hasPerm('ops:approval:config'));
const loading = ref(false);
const hydrated = ref(false);
const saving = ref(false);
const metaSaving = ref(false);
const creating = ref(false);
const rows = ref<ApprovalDef[]>([]);
const departments = ref<DeptRow[]>([]);
const dlg = ref(false);
const metaDlg = ref(false);
const activeIdx = ref(0);
const editForm = reactive({
  defId: 0,
  bizType: '',
  defName: '',
  enabled: true,
  remark: '',
  nodes: [] as ApprovalNode[]
});
const metaForm = reactive({
  defId: 0,
  bizType: '',
  defName: '',
  enabled: true,
  remark: ''
});

const assigneeTypeOptions = computed(() => dictOptions('approval_assignee_type'));
const bizTypeOptions = computed(() => dictOptions('approval_biz_type'));

function rowActions(): TableAction[] {
  return [
    { key: 'edit', label: '编辑', icon: EditPen, type: 'primary' },
    { key: 'flow', label: '编辑流程图', icon: Share, type: 'primary' },
    { key: 'delete', label: '删除', icon: Delete, type: 'danger', overflow: true }
  ];
}

function onRowAction(key: string, row: ApprovalDef) {
  if (key === 'edit') openMetaEdit(row);
  else if (key === 'flow') openFlowEdit(row);
  else if (key === 'delete') void onDelete(row);
}

function bizLabel(bizType: string) {
  return dictLabel('approval_biz_type', bizType) || bizType;
}

function sortedNodes(nodes?: ApprovalNode[]) {
  return [...(nodes || [])].sort((a, b) => (a.seq || 0) - (b.seq || 0));
}

function deptName(key?: string) {
  if (!key) return '';
  return departments.value.find((d) => d.deptKey === key)?.deptName || key;
}

function assigneeBrief(n: ApprovalNode) {
  const typeLabel = dictLabel('approval_assignee_type', n.assigneeType || '') || n.assigneeType;
  if (n.assigneeType === 'DEPT') {
    return `${typeLabel} · ${deptName(n.assigneeValue)}`;
  }
  return `${typeLabel} · ${n.assigneeValue || '未指定'}`;
}

function assigneeValuePlaceholder(type: string) {
  switch (type) {
    case 'PERM':
      return '权限码，如 ops:finance:review';
    case 'ROLE':
      return '角色 key，如 finance';
    case 'USER':
      return '用户 ID，如 100000001';
    default:
      return '指派值';
  }
}

function reseq() {
  editForm.nodes.forEach((n, i) => {
    n.seq = i + 1;
  });
}

async function load() {
  loading.value = true;
  try {
    const [defs, depts] = await Promise.all([
      api.request<ApprovalDef[]>('/api/v2/ops/admin/approvals/definitions', 'GET'),
      api.request<DeptRow[]>('/api/v2/ops/admin/departments', 'GET').catch(() => [])
    ]);
    rows.value = defs || [];
    departments.value = depts || [];
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
    hydrated.value = true;
  }
}

function openCreate() {
  creating.value = true;
  metaForm.defId = 0;
  metaForm.bizType = '';
  metaForm.defName = '';
  metaForm.enabled = true;
  metaForm.remark = '';
  metaDlg.value = true;
}

function openMetaEdit(row: ApprovalDef) {
  creating.value = false;
  metaForm.defId = row.defId;
  metaForm.bizType = row.bizType;
  metaForm.defName = row.defName;
  metaForm.enabled = row.enabled;
  metaForm.remark = row.remark || '';
  metaDlg.value = true;
}

function openFlowEdit(row: ApprovalDef) {
  editForm.defId = row.defId;
  editForm.bizType = row.bizType;
  editForm.defName = row.defName;
  editForm.enabled = row.enabled;
  editForm.remark = row.remark || '';
  editForm.nodes = sortedNodes(row.nodes).map((n) => ({ ...n, _key: nextKey() }));
  if (!editForm.nodes.length) insertNode(0);
  activeIdx.value = 0;
  dlg.value = true;
}

async function saveMeta() {
  if (!metaForm.defName.trim()) {
    ElMessage.warning('请填写名称');
    return;
  }
  if (creating.value && !metaForm.bizType.trim()) {
    ElMessage.warning('请填写业务类型');
    return;
  }
  metaSaving.value = true;
  try {
    if (creating.value) {
      await api.request('/api/v2/ops/admin/approvals/definitions', 'POST', {
        bizType: metaForm.bizType.trim().toUpperCase(),
        defName: metaForm.defName.trim(),
        enabled: metaForm.enabled,
        remark: metaForm.remark.trim() || null
      });
      ElMessage.success('已新增，可继续编辑流程图');
    } else {
      await api.request(`/api/v2/ops/admin/approvals/definitions/${metaForm.defId}`, 'PUT', {
        defName: metaForm.defName.trim(),
        enabled: metaForm.enabled,
        remark: metaForm.remark.trim() || null
      });
      ElMessage.success('已保存');
    }
    metaDlg.value = false;
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    metaSaving.value = false;
  }
}

async function onDelete(row: ApprovalDef) {
  try {
    await ElMessageBox.confirm(
      `确认删除审批流「${row.defName}」？已有审批实例时将无法删除。`,
      '删除',
      { type: 'warning' }
    );
  } catch {
    return;
  }
  try {
    await api.request(`/api/v2/ops/admin/approvals/definitions/${row.defId}`, 'DELETE');
    ElMessage.success('已删除');
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败');
  }
}

function blankNode(seq: number): ApprovalNode {
  return {
    seq,
    nodeName: `节点${seq}`,
    assigneeType: 'DEPT',
    assigneeValue: departments.value[0]?.deptKey || 'HQ',
    passRule: 'ANY',
    _key: nextKey()
  };
}

function insertNode(at: number) {
  editForm.nodes.splice(at, 0, blankNode(at + 1));
  reseq();
  activeIdx.value = at;
}

function removeNode(idx: number) {
  if (editForm.nodes.length <= 1) {
    ElMessage.warning('至少保留一个审批节点');
    return;
  }
  editForm.nodes.splice(idx, 1);
  reseq();
  activeIdx.value = Math.min(idx, editForm.nodes.length - 1);
}

function moveNode(idx: number, delta: number) {
  const to = idx + delta;
  if (to < 0 || to >= editForm.nodes.length) return;
  const list = editForm.nodes;
  const tmp = list[idx];
  list[idx] = list[to];
  list[to] = tmp;
  reseq();
  activeIdx.value = to;
}

async function save() {
  if (!editForm.nodes.length) {
    ElMessage.warning('至少保留一个审批节点');
    return;
  }
  for (const n of editForm.nodes) {
    if (!n.nodeName?.trim() || !n.assigneeType || !String(n.assigneeValue || '').trim()) {
      ElMessage.warning('请完整填写每个节点的名称与处理人');
      return;
    }
  }
  saving.value = true;
  try {
    reseq();
    await api.request(`/api/v2/ops/admin/approvals/definitions/${editForm.defId}`, 'PUT', {
      defName: editForm.defName,
      enabled: editForm.enabled,
      remark: editForm.remark,
      nodes: editForm.nodes.map((n) => ({
        seq: n.seq,
        nodeName: n.nodeName.trim(),
        assigneeType: n.assigneeType,
        assigneeValue: String(n.assigneeValue).trim(),
        passRule: n.passRule || 'ANY'
      }))
    });
    ElMessage.success('流程图已保存');
    dlg.value = false;
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.cell-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.flow-inline {
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.flow-preview {
  padding: 12px 16px 16px;
  overflow-x: auto;
}
.flow-preview__rail {
  display: flex;
  align-items: center;
  gap: 0;
  min-width: max-content;
}
.flow-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  border-radius: 10px;
  border: 1px solid var(--el-border-color);
  background: var(--el-bg-color);
  font-size: 13px;
  white-space: nowrap;
}
.flow-chip--start,
.flow-chip--end {
  background: var(--el-fill-color-light);
  color: var(--el-text-color-secondary);
}
.flow-chip__seq {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: var(--el-color-primary);
  color: #fff;
  font-size: 12px;
}
.flow-chip__meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.flow-arrow {
  width: 28px;
  height: 2px;
  margin: 0 4px;
  background: var(--el-border-color-darker);
  position: relative;
  flex-shrink: 0;
}
.flow-arrow::after {
  content: '';
  position: absolute;
  right: -1px;
  top: -3px;
  border: 4px solid transparent;
  border-left-color: var(--el-border-color-darker);
}

.flow-meta {
  margin-bottom: 8px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.flow-meta__form {
  margin-bottom: 0;
}
.flow-canvas {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0;
  max-height: min(68vh, 720px);
  overflow-y: auto;
  padding: 8px 4px 16px;
  background: radial-gradient(circle at 1px 1px, var(--el-border-color-lighter) 1px, transparent 0)
    0 0 / 16px 16px;
  border-radius: 8px;
}
.flow-node {
  width: min(420px, 100%);
  padding: 12px 14px;
  border-radius: 12px;
  border: 1px solid var(--el-border-color);
  background: var(--el-bg-color);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
  cursor: pointer;
  transition:
    border-color 0.15s ease,
    box-shadow 0.15s ease;
}
.flow-node.is-active {
  border-color: var(--el-color-primary);
  box-shadow: 0 0 0 2px var(--el-color-primary-light-7);
}
.flow-node--terminal {
  cursor: default;
  text-align: center;
  background: var(--el-fill-color-blank);
}
.flow-node--ok {
  border-color: var(--el-color-success-light-5);
}
.flow-node--reject {
  border-color: var(--el-color-danger-light-5);
}
.flow-node__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.flow-node__badge {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.flow-node__actions {
  display: inline-flex;
  gap: 0;
}
.flow-node__title {
  font-weight: 600;
  font-size: 15px;
}
.flow-node__sub {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.flow-node__name {
  margin-bottom: 8px;
}
.flow-node__fields {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.flow-node__footer {
  margin-top: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.flow-insert {
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 40px;
  padding: 4px 0;
  border: 0;
  background: transparent;
  cursor: pointer;
  color: var(--el-color-primary);
}
.flow-insert__line {
  width: 2px;
  height: 14px;
  background: var(--el-border-color-darker);
}
.flow-insert__plus {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  border: 1px dashed var(--el-color-primary);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  line-height: 1;
  background: var(--el-bg-color);
}
.flow-insert:hover .flow-insert__plus {
  background: var(--el-color-primary-light-9);
}
.flow-ends {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  width: min(420px, 100%);
}
</style>
