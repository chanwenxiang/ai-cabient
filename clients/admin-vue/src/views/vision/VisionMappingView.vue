<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">识别映射</span>
            <span class="hint">
              将邑端侧识别 classId → SKU 的翻译表；激活的映射才参与结算推荐。识别能力在将邑侧，
              云端不做识别；商品入驻流程见「识别入驻」
            </span>
          </div>
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="load()">
      <el-form-item label="柜机">
        <el-select
          v-model="deviceId"
          filterable
          clearable
          placeholder="选择将邑柜机"
          style="width: 280px"
          @change="load()"
        >
          <el-option
            v-for="d in deviceOptions"
            :key="d.deviceId"
            :label="`${d.deviceName || d.deviceId}（${d.deviceId}）`"
            :value="d.deviceId"
          />
        </el-select>
      </el-form-item>
    </el-form>

    <el-alert
      v-if="deviceId && loaded && !binding"
      type="warning"
      :closable="false"
      show-icon
      title="该柜机未接入将邑"
      description="请先到设备详情的「将邑接入」卡片完成登记与绑定。"
    />

    <template v-if="binding">
      <div class="jy-device-bar">
        <div class="jy-device-item">
          <span class="jy-device-label">设备状态</span>
          <el-tag size="small" :type="binding.status === 'BOUND' ? 'success' : 'info'">
            {{ binding.status }}
          </el-tag>
        </div>
        <div class="jy-device-item">
          <span class="jy-device-label">当前生效模型</span>
          <span class="jy-mono">{{ binding.modelName || '未下发' }}</span>
        </div>
        <div class="jy-device-item">
          <span class="jy-device-label">classes 版本</span>
          <span class="jy-mono">{{ binding.classesVersion || '—' }}</span>
        </div>
        <div class="jy-device-item">
          <span class="jy-device-label">最近 WS 在线</span>
          <span>{{ formatInstant(binding.lastWsOnlineAt) }}</span>
        </div>
      </div>

      <!-- 模型同步：预览 → 预生成 → 激活 → 下发（CB-023 链路） -->
      <el-card class="jy-model-card" shadow="never">
        <template #header>
          <div class="page-card-head">
            <div class="page-card-head__meta">
              <div class="page-card-head__title">
                <span class="title">模型同步</span>
                <span class="hint">
                  预生成后映射为停用态，人工核对 classId ↔ SKU 再激活；industrialControlModel
                  按字符串相等校验，与设备登记值不一致将拒绝下发
                </span>
              </div>
            </div>
          </div>
        </template>

        <el-form inline class="filter-bar filter-bar--compact" @submit.prevent>
          <el-form-item label="将邑模型">
            <el-select
              v-model="selectedModel"
              placeholder="拉取模型列表"
              style="width: 320px"
              :loading="modelsLoading"
            >
              <el-option
                v-for="m in modelPreviews"
                :key="m.modelName"
                :label="`${m.modelName}（${m.quantity} 类 · ${m.trainedMatchPercent}% 已挂接 · 机型 ${m.industrialControlModel}）`"
                :value="m.modelName"
                :disabled="Boolean(m.rejectReason)"
              >
                <template v-if="m.rejectReason">
                  <span>{{ m.modelName }}（不可用：{{ m.rejectReason }}）</span>
                </template>
              </el-option>
            </el-select>
          </el-form-item>
          <el-form-item label="classId 基数">
            <el-select v-model="classIdBase" style="width: 130px">
              <el-option label="0 起（第 1 行=0）" :value="0" />
              <el-option label="1 起（第 1 行=1）" :value="1" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button :loading="modelsLoading" @click="loadModels()">刷新模型列表</el-button>
          </el-form-item>
        </el-form>

        <template v-if="currentPreview">
          <div class="jy-preview-rows">
            <div v-for="row in currentPreview.rows" :key="row.classId" class="jy-preview-row">
              <span class="jy-mono">classId={{ row.classId }}</span>
              <span>{{ row.textName }}</span>
            </div>
          </div>
          <div class="jy-model-actions">
            <el-button
              v-hasPermi="['ops:device:edit']"
              :loading="acting === 'sync'"
              @click="doModelSync()"
            >
              预生成映射（停用态）
            </el-button>
            <el-button
              v-hasPermi="['ops:device:edit']"
              type="warning"
              :loading="acting === 'activate'"
              @click="doActivate()"
            >
              激活该模型预生成行
            </el-button>
            <el-button
              v-hasPermi="['ops:device:edit']"
              type="primary"
              :loading="acting === 'push'"
              @click="doPush()"
            >
              下发模型到柜机
            </el-button>
          </div>
        </template>
      </el-card>

      <!-- classId 映射表 -->
      <div class="table-scroll">
        <div class="table-scroll-inner">
          <CrudTable
            :table="crud"
            :actions="rowActions"
            :action-width="150"
            empty-text="暂无映射；先在「模型同步」预生成，或到商品管理逐条挂接"
            sort-field-label="classId"
            :csv="csvOptions"
            @action="onAction"
          >
            <el-table-column prop="classId" label="classId" width="100" class-name="col-text">
              <template #default="{ row }">
                <span class="jy-mono">{{ row.classId }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="textName" label="textName（classes 键）" min-width="150">
              <template #default="{ row }">{{ row.textName || '—' }}</template>
            </el-table-column>
            <el-table-column prop="skuId" label="SKU" min-width="120" class-name="col-text">
              <template #default="{ row }">{{ row.skuId || '未挂接' }}</template>
            </el-table-column>
            <el-table-column prop="modelName" label="模型" min-width="130">
              <template #default="{ row }">{{ row.modelName || '—' }}</template>
            </el-table-column>
            <el-table-column
              prop="status"
              label="状态"
              width="100"
              align="center"
              class-name="col-status"
              label-class-name="col-status"
            >
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">
                  {{ row.status === 'ACTIVE' ? '已启用' : '已停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column
              prop="source"
              label="来源"
              width="110"
              align="center"
              class-name="col-status"
              label-class-name="col-status"
            >
              <template #default="{ row }">
                <el-tag size="small" type="warning" effect="plain">
                  {{ row.source === 'MODEL_SYNC' ? '模型预生成' : '手工' }}
                </el-tag>
              </template>
            </el-table-column>
          </CrudTable>
        </div>
      </div>

      <!-- 下发审计 -->
      <el-card class="jy-deploy-card" shadow="never">
        <template #header>
          <div class="page-card-head">
            <div class="page-card-head__meta">
              <div class="page-card-head__title">
                <span class="title">下发记录</span>
                <span class="hint"
                  >SENT=已下发等待回执（600s 超时置 FAILED）；CONFIRMED=柜机已应用</span
                >
              </div>
            </div>
            <div class="page-card-head__actions">
              <el-button size="small" @click="loadDeployments()">刷新</el-button>
            </div>
          </div>
        </template>
        <el-table :data="deployments" stripe border size="small">
          <el-table-column prop="modelName" label="模型" min-width="140" />
          <el-table-column prop="industrialControlModel" label="机型" width="90">
            <template #default="{ row }">
              <span class="jy-mono">{{ row.industrialControlModel || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column
            prop="status"
            label="状态"
            width="120"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag
                size="small"
                :type="
                  row.status === 'CONFIRMED'
                    ? 'success'
                    : row.status === 'FAILED'
                      ? 'danger'
                      : 'warning'
                "
              >
                {{ row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="下发时间" width="170">
            <template #default="{ row }">{{ formatInstant(row.sentAt) }}</template>
          </el-table-column>
          <el-table-column label="确认时间" width="170">
            <template #default="{ row }">{{ formatInstant(row.confirmedAt) || '—' }}</template>
          </el-table-column>
          <el-table-column prop="failReason" label="失败原因" min-width="160">
            <template #default="{ row }">{{ row.failReason || '—' }}</template>
          </el-table-column>
          <template #empty><el-empty description="尚未下发过模型" /></template>
        </el-table>
      </el-card>
    </template>

    <el-empty v-if="!deviceId" description="选择一台将邑柜机查看其识别映射与模型同步状态" />

    <el-dialog v-model="editVisible" title="编辑映射" destroy-on-close>
      <el-form label-width="auto">
        <el-form-item label="classId">
          <el-input :model-value="editForm.classId" disabled />
        </el-form-item>
        <el-form-item label="textName">
          <el-input v-model="editForm.textName" placeholder="classes 键（识别商品名）" />
        </el-form-item>
        <el-form-item label="SKU" required>
          <el-select v-model="editForm.skuId" filterable style="width: 100%">
            <el-option
              v-for="s in skuOptions"
              :key="s.skuId"
              :label="`${s.skuName || s.skuId}（${s.skuId}）`"
              :value="s.skuId"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { CircleCheck, CircleClose, EditPen } from '@element-plus/icons-vue';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import CrudTable, { type CrudCsvOptions, type CrudRowAction } from '@/components/CrudTable.vue';
import { useCrudTable } from '@/composables/useCrudTable';
import { errorMessage } from '@/utils/error-message';

/**
 * 识别映射（CB-023 改造）：云端识别（YOLO/阿里云）方案已废弃，本页收编将邑
 * classId 映射管理与模型同步链路（预览→预生成→激活→下发→审计）。
 * 设备维度快捷操作仍在设备详情「将邑接入」卡片，两处共用同一组端点。
 */

interface JiangyiClassMappingRow {
  id: number;
  deviceId: string;
  classId: number;
  modelName: string | null;
  textName: string | null;
  skuId: string | null;
  status: string;
  source: string;
  createdAt: string | null;
  updatedAt: string | null;
}

interface JiangyiBindingInfo {
  deviceId: string;
  deviceSn: string | null;
  identifier: string | null;
  modelName: string | null;
  classesVersion: string | null;
  status: string;
  lastWsOnlineAt: string | null;
}

interface ParsedClassRow {
  classId: number;
  textName: string;
  lineNumber: number;
}

interface ModelPreview {
  modelName: string;
  industrialControlModel: string | null;
  quantity: number;
  modelTextUrl: string | null;
  classesVersion: string | null;
  fallbackVersion: string | null;
  rows: ParsedClassRow[];
  rejectReason: string | null;
  trainedMatchPercent: number;
}

interface DeploymentRow {
  id: number;
  deviceId: string;
  modelName: string;
  industrialControlModel: string | null;
  classesVersion: string | null;
  status: string;
  sentAt: string | null;
  confirmedAt: string | null;
  failReason: string | null;
}

interface DeviceOption {
  deviceId: string;
  deviceName?: string;
}

interface SkuOption {
  skuId: string;
  skuName?: string;
}

const deviceId = ref('');
const deviceOptions = ref<DeviceOption[]>([]);
const binding = ref<JiangyiBindingInfo | null>(null);
const loaded = ref(false);

const modelPreviews = ref<ModelPreview[]>([]);
const modelsLoading = ref(false);
const selectedModel = ref('');
const classIdBase = ref(0);
const acting = ref<'sync' | 'activate' | 'push' | ''>('');
const deployments = ref<DeploymentRow[]>([]);
const skuOptions = ref<SkuOption[]>([]);
const saving = ref(false);
const editVisible = ref(false);
const editForm = ref({ classId: 0, textName: '', skuId: '' });

const currentPreview = computed(
  () => modelPreviews.value.find((m) => m.modelName === selectedModel.value) || null
);

const crud = useCrudTable<JiangyiClassMappingRow>({
  rowKey: (r) => r.id,
  errorMessage: '加载映射失败',
  autoLoad: false,
  sort: { prop: 'classId', mode: 'local' },
  fetchPage: async () => {
    if (!deviceId.value) return { items: [], total: 0 };
    const data = await api.request<{
      binding: JiangyiBindingInfo | null;
      mappings: JiangyiClassMappingRow[];
    }>(AdminEndpoints.deviceJiangyi(deviceId.value), 'GET');
    binding.value = data.binding;
    return { items: data.mappings || [], total: (data.mappings || []).length };
  }
});

const csvOptions: CrudCsvOptions = {
  filePrefix: '将邑识别映射',
  exportPerm: 'ops:device:export',
  headers: ['classId', 'textName', 'SKU', '模型', '状态', '来源'],
  toRows: (rows) =>
    rows.map((r) => [
      r.classId,
      r.textName ?? '',
      r.skuId ?? '',
      r.modelName ?? '',
      r.status,
      r.source
    ])
};

function rowActions(row: JiangyiClassMappingRow): CrudRowAction[] {
  const acts: CrudRowAction[] = [];
  if (row.status === 'ACTIVE') {
    acts.push({
      key: 'disable',
      label: '停用',
      icon: CircleClose,
      type: 'warning',
      perm: 'ops:device:edit'
    });
  } else {
    acts.push({
      key: 'enable',
      label: '启用',
      icon: CircleCheck,
      type: 'primary',
      perm: 'ops:device:edit'
    });
  }
  acts.push({
    key: 'edit',
    label: '挂接 SKU',
    icon: EditPen,
    type: 'primary',
    perm: 'ops:device:edit'
  });
  return acts;
}

function onAction({ key, row }: { key: string; row: JiangyiClassMappingRow }) {
  if (key === 'enable' || key === 'disable') void toggleStatus(row);
  else if (key === 'edit') openEdit(row);
}

async function loadDevices() {
  try {
    const list = await api.request<{ items?: DeviceOption[] }>(
      AdminEndpoints.devicesOptions,
      'GET'
    );
    deviceOptions.value = list.items || [];
  } catch {
    deviceOptions.value = [];
  }
}

async function load() {
  loaded.value = false;
  binding.value = null;
  if (!deviceId.value) return;
  await crud.load({ resetPage: true });
  loaded.value = true;
  void loadDeployments();
}

async function loadModels() {
  modelsLoading.value = true;
  try {
    const q = new URLSearchParams({ classIdBase: String(classIdBase.value) });
    modelPreviews.value = await api.request<ModelPreview[]>(
      `${AdminEndpoints.jiangyiModels}?${q.toString()}`,
      'GET'
    );
    if (selectedModel.value && !currentPreview.value) selectedModel.value = '';
  } catch (e) {
    ElMessage.error(errorMessage(e, '拉取将邑模型列表失败'));
  } finally {
    modelsLoading.value = false;
  }
}

async function doModelSync() {
  if (!ensureSelected()) return;
  acting.value = 'sync';
  try {
    const n = await api.request<number>(
      AdminEndpoints.deviceJiangyiModelSync(deviceId.value),
      'POST',
      {
        modelName: selectedModel.value,
        classIdBase: classIdBase.value
      }
    );
    ElMessage.success(`已预生成 ${n} 行映射（停用态），请核对后激活`);
    await crud.load();
  } catch (e) {
    ElMessage.error(errorMessage(e, '预生成失败'));
  } finally {
    acting.value = '';
  }
}

async function doActivate() {
  if (!ensureSelected()) return;
  try {
    await ElMessageBox.confirm(
      '激活后该模型全部预生成行立即参与结算推荐。已确认 classId ↔ SKU 对照无误？',
      '激活确认',
      { type: 'warning', confirmButtonText: '激活', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  acting.value = 'activate';
  try {
    const n = await api.request<number>(
      AdminEndpoints.deviceJiangyiMappingActivate(deviceId.value),
      'POST',
      { modelName: selectedModel.value }
    );
    ElMessage.success(`已激活 ${n} 行映射`);
    await crud.load();
  } catch (e) {
    ElMessage.error(errorMessage(e, '激活失败'));
  } finally {
    acting.value = '';
  }
}

async function doPush() {
  if (!ensureSelected()) return;
  try {
    await ElMessageBox.confirm(
      '下发前确认：① 映射已激活；② 设备登记机型与模型机型一致。柜机在线才会收到 WS 指令。确认下发？',
      '下发确认',
      { type: 'warning', confirmButtonText: '下发', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  acting.value = 'push';
  try {
    const id = await api.request<number>(
      AdminEndpoints.deviceJiangyiModelPush(deviceId.value),
      'POST',
      {
        modelName: selectedModel.value
      }
    );
    ElMessage.success(`已下发（部署 #${id}），等待柜机回执`);
    loadDeployments();
  } catch (e) {
    ElMessage.error(errorMessage(e, '下发失败'));
  } finally {
    acting.value = '';
  }
}

async function loadDeployments() {
  if (!deviceId.value) return;
  try {
    deployments.value = await api.request<DeploymentRow[]>(
      AdminEndpoints.deviceJiangyiModelDeployments(deviceId.value),
      'GET'
    );
  } catch {
    deployments.value = [];
  }
}

async function toggleStatus(row: JiangyiClassMappingRow) {
  const next = row.status !== 'ACTIVE';
  try {
    await api.request(
      AdminEndpoints.deviceJiangyiMappingStatus(deviceId.value, row.classId),
      'POST',
      { active: next }
    );
    ElMessage.success(next ? '已启用，下次识别即生效' : '已停用');
    await crud.load();
  } catch (e) {
    ElMessage.error(errorMessage(e, '变更映射状态失败'));
  }
}

async function loadSkus() {
  try {
    const data = await api.request<SkuOption[] | { items?: SkuOption[] }>(
      AdminEndpoints.skusCatalogPage,
      'GET'
    );
    skuOptions.value = Array.isArray(data) ? data : data.items || [];
  } catch {
    skuOptions.value = [];
  }
}

function openEdit(row: JiangyiClassMappingRow) {
  editForm.value = { classId: row.classId, textName: row.textName || '', skuId: row.skuId || '' };
  editVisible.value = true;
}

async function saveEdit() {
  if (!editForm.value.skuId) {
    ElMessage.warning('请选择 SKU');
    return;
  }
  saving.value = true;
  try {
    await api.request(
      AdminEndpoints.deviceJiangyiMapping(deviceId.value, editForm.value.classId),
      'PUT',
      {
        skuId: editForm.value.skuId,
        textName: editForm.value.textName || undefined
      }
    );
    ElMessage.success('已保存');
    editVisible.value = false;
    await crud.load();
  } catch (e) {
    ElMessage.error(errorMessage(e, '保存失败'));
  } finally {
    saving.value = false;
  }
}

function ensureSelected() {
  if (selectedModel.value) return true;
  ElMessage.warning('请先选择将邑模型');
  return false;
}

function formatInstant(v?: string | null) {
  if (!v) return '';
  return String(v).replace('T', ' ').slice(0, 19);
}

onMounted(() => {
  void loadDevices();
  void loadSkus();
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
  font-size: var(--admin-font-size-title);
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: var(--admin-font-size-sm);
  line-height: 1.4;
}
.page-card-head__actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.jy-device-bar {
  display: flex;
  gap: 24px;
  flex-wrap: wrap;
  padding: 10px 12px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  margin-bottom: 16px;
}
.jy-device-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 120px;
}
.jy-device-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.jy-mono {
  font-family: var(--font-mono);
  font-size: 12px;
}
.jy-model-card {
  margin-bottom: 16px;
}
.jy-preview-rows {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 6px 16px;
  margin-bottom: 12px;
}
.jy-preview-row {
  display: flex;
  gap: 10px;
  align-items: baseline;
  font-size: 13px;
}
.jy-model-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.jy-deploy-card {
  margin-top: 16px;
}
</style>
