<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { errorMessage } from '@/utils/error-message';
import { formatDateTime } from '@aicabinet/shared-uni/format';

/**
 * 将邑接入卡片（CB-022 收尾）：把此前只能手工 SQL 的登记/绑定/映射升级为后台可操作。
 * 流程＝SN 登记（UNBOUND）→ setDomain 绑定（BOUND）→ class_id→SKU 映射（识别结算的唯一换算依据）。
 * 库存不在此处：device_sku_inventory 走既有「货道管理」，将邑柜直接复用。
 */
interface JiangyiDeviceBinding {
  deviceId: string;
  deviceSn: string;
  identifier: string | null;
  modelName: string | null;
  classesVersion: string | null;
  status: 'UNBOUND' | 'BOUND' | 'RETIRED';
  tokenVersion: number;
  tokenIssuedAt: string | null;
  lastWsOnlineAt: string | null;
  createdAt: string;
  updatedAt: string;
}

interface JiangyiClassMappingRow {
  id: number;
  deviceId: string;
  classId: number;
  modelName: string | null;
  textName: string | null;
  skuId: string | null;
  status: 'ACTIVE' | 'DISABLED';
  source: 'MANUAL' | 'MODEL_SYNC';
  createdAt: string;
  updatedAt: string;
}

const props = defineProps<{
  deviceId: string;
  canEdit: boolean;
}>();

const loading = ref(false);
const binding = ref<JiangyiDeviceBinding | null>(null);
const mappings = ref<JiangyiClassMappingRow[]>([]);

const registerForm = reactive({ deviceSn: '', modelName: '' });
const registerSaving = ref(false);
const bindForm = reactive({ domain: '', socketUrl: '' });
const bindSaving = ref(false);
const retireSaving = ref(false);
const mappingForm = reactive({
  classId: undefined as number | undefined,
  skuId: '',
  textName: '',
  modelName: '',
  active: true
});
const mappingSaving = ref(false);

const statusMeta = computed(() => {
  if (!binding.value) return null;
  if (binding.value.status === 'BOUND') return { label: '已绑定', type: 'success' as const };
  if (binding.value.status === 'UNBOUND') return { label: '未绑定', type: 'warning' as const };
  return { label: '已退役', type: 'danger' as const };
});

async function load() {
  loading.value = true;
  try {
    const view = await api.request<{
      binding: JiangyiDeviceBinding | null;
      mappings: JiangyiClassMappingRow[];
    }>(AdminEndpoints.deviceJiangyi(props.deviceId), 'GET');
    binding.value = view.binding;
    mappings.value = view.mappings || [];
  } catch (e) {
    ElMessage.error(errorMessage(e, '加载将邑接入信息失败'));
  } finally {
    loading.value = false;
  }
}

async function doRegister() {
  const sn = registerForm.deviceSn.trim();
  if (!sn) {
    ElMessage.warning('请填写工控机 SN');
    return;
  }
  registerSaving.value = true;
  try {
    await api.request(AdminEndpoints.deviceJiangyiRegister(props.deviceId), 'POST', {
      deviceSn: sn,
      modelName: registerForm.modelName.trim() || undefined
    });
    ElMessage.success('已登记，可进行 setDomain 绑定');
    registerForm.deviceSn = '';
    registerForm.modelName = '';
    await load();
  } catch (e) {
    ElMessage.error(errorMessage(e, '登记失败'));
  } finally {
    registerSaving.value = false;
  }
}

async function doBind() {
  const domain = bindForm.domain.trim();
  if (!domain) {
    ElMessage.warning('请填写公网 domain（如 https://api.example.com）');
    return;
  }
  try {
    await ElMessageBox.confirm(
      '将调用将邑云 setDomain 把该设备 domain/socketUrl 指向我方，确认继续？',
      '绑定确认',
      { type: 'warning', confirmButtonText: '绑定', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  bindSaving.value = true;
  try {
    await api.request(AdminEndpoints.deviceJiangyiBind(props.deviceId), 'POST', {
      domain,
      socketUrl: bindForm.socketUrl.trim() || undefined
    });
    ElMessage.success('绑定完成，等待设备上电换取 token 并建立 WS 连接');
    bindForm.domain = '';
    bindForm.socketUrl = '';
    await load();
  } catch (e) {
    ElMessage.error(errorMessage(e, '绑定失败'));
  } finally {
    bindSaving.value = false;
  }
}

async function doRetire() {
  retireSaving.value = true;
  try {
    await api.request(AdminEndpoints.deviceJiangyiRetire(props.deviceId), 'POST');
    ElMessage.success('已退役');
    await load();
  } catch (e) {
    ElMessage.error(errorMessage(e, '退役失败'));
  } finally {
    retireSaving.value = false;
  }
}

async function saveMapping() {
  const classId = mappingForm.classId;
  const skuId = mappingForm.skuId.trim();
  if (classId == null || Number.isNaN(classId)) {
    ElMessage.warning('请填写识别 classId（数字）');
    return;
  }
  if (!skuId) {
    ElMessage.warning('请填写对应 SKU 编号');
    return;
  }
  mappingSaving.value = true;
  try {
    await api.request(AdminEndpoints.deviceJiangyiMapping(props.deviceId, classId), 'PUT', {
      skuId,
      textName: mappingForm.textName.trim() || undefined,
      modelName: mappingForm.modelName.trim() || undefined,
      active: mappingForm.active
    });
    ElMessage.success('映射已保存');
    mappingForm.classId = undefined;
    mappingForm.skuId = '';
    mappingForm.textName = '';
    mappingForm.modelName = '';
    mappingForm.active = true;
    await load();
  } catch (e) {
    ElMessage.error(errorMessage(e, '保存映射失败'));
  } finally {
    mappingSaving.value = false;
  }
}

async function toggleMapping(row: JiangyiClassMappingRow) {
  try {
    await api.request(
      AdminEndpoints.deviceJiangyiMappingStatus(props.deviceId, row.classId),
      'POST',
      { active: row.status !== 'ACTIVE' }
    );
    ElMessage.success(row.status === 'ACTIVE' ? '已停用，下次识别即生效' : '已启用');
    await load();
  } catch (e) {
    ElMessage.error(errorMessage(e, '变更映射状态失败'));
  }
}

// ---------- 模型同步（CB-023 二期）：classes 对照→预生成→人工激活→WS 下发→回执审计 ----------

interface JiangyiModelPreview {
  modelName: string;
  industrialControlModel: string | null;
  quantity: number;
  modelTextUrl: string | null;
  classesVersion: string | null;
  rows: { classId: number; textName: string; lineNumber: number }[] | null;
  rejectReason: string | null;
  trainedMatchPercent: number;
}

interface JiangyiDeployment {
  id: number;
  modelName: string;
  modelUrl: string | null;
  industrialControlModel: string | null;
  classesVersion: string | null;
  status: 'SENT' | 'CONFIRMED' | 'FAILED';
  sentAt: string | null;
  confirmedAt: string | null;
  failReason: string | null;
}

const modelSection = ref(false);
const models = ref<JiangyiModelPreview[]>([]);
const modelsLoading = ref(false);
const modelSyncing = ref(false);
const activating = ref(false);
const pushing = ref(false);
const deployments = ref<JiangyiDeployment[]>([]);
const deploymentsLoading = ref(false);
const selectedModel = ref<JiangyiModelPreview | null>(null);
const classIdBase = ref(0);

async function loadModels() {
  modelsLoading.value = true;
  try {
    models.value = await api.request<JiangyiModelPreview[]>(
      `${AdminEndpoints.jiangyiModels}?classIdBase=${classIdBase.value}`,
      'GET'
    );
  } catch (e) {
    ElMessage.error(errorMessage(e, '加载将邑模型列表失败'));
  } finally {
    modelsLoading.value = false;
  }
}

function toggleModelSection() {
  modelSection.value = !modelSection.value;
  if (modelSection.value && models.value.length === 0) {
    void loadModels();
  }
}

async function doModelSync(row: JiangyiModelPreview) {
  try {
    await ElMessageBox.confirm(
      `按「${row.modelName}」的 classes 生成该设备的预置映射（停用态，不会自动生效）。已有 MODEL_SYNC 行会被覆盖，但已挂 SKU 保留。`,
      '预生成映射确认',
      { type: 'warning', confirmButtonText: '预生成', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  modelSyncing.value = true;
  try {
    const written = await api.request<number>(
      AdminEndpoints.deviceJiangyiModelSync(props.deviceId),
      'POST',
      {
        modelName: row.modelName,
        classIdBase: classIdBase.value
      }
    );
    ElMessage.success(`已预生成 ${written} 行停用态映射，请核对后激活`);
    await load();
  } catch (e) {
    ElMessage.error(errorMessage(e, '预生成映射失败'));
  } finally {
    modelSyncing.value = false;
  }
}

async function doActivate(row: JiangyiModelPreview) {
  try {
    await ElMessageBox.confirm(
      `激活「${row.modelName}」的全部预置映射（启用后识别即按此对照结算）。确认对照表无误？`,
      '激活确认',
      { type: 'warning', confirmButtonText: '激活', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  activating.value = true;
  try {
    const n = await api.request<number>(
      AdminEndpoints.deviceJiangyiMappingActivate(props.deviceId),
      'POST',
      { modelName: row.modelName }
    );
    ElMessage.success(`已激活 ${n} 行映射`);
    await load();
  } catch (e) {
    ElMessage.error(errorMessage(e, '激活映射失败'));
  } finally {
    activating.value = false;
  }
}

async function doModelPush(row: JiangyiModelPreview) {
  try {
    await ElMessageBox.confirm(
      `下发「${row.modelName}」到设备（经 WS updateModel；设备回执后生效）。确认下发？`,
      '下发模型确认',
      { type: 'warning', confirmButtonText: '下发', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  pushing.value = true;
  try {
    await api.request<number>(AdminEndpoints.deviceJiangyiModelPush(props.deviceId), 'POST', {
      modelName: row.modelName
    });
    ElMessage.success('已下发，等待设备 downloadModelNotify 回执');
    await loadDeployments();
  } catch (e) {
    ElMessage.error(errorMessage(e, '下发模型失败'));
  } finally {
    pushing.value = false;
  }
}

async function loadDeployments() {
  deploymentsLoading.value = true;
  try {
    deployments.value = await api.request<JiangyiDeployment[]>(
      AdminEndpoints.deviceJiangyiModelDeployments(props.deviceId),
      'GET'
    );
  } catch (e) {
    ElMessage.error(errorMessage(e, '加载下发记录失败'));
  } finally {
    deploymentsLoading.value = false;
  }
}

// ---------- 采集编排（CB-023 二期）：进入/退出采集模式 + 学习 + 进度 ----------

interface GatherCheckItem {
  id: number;
  productId: number;
  status: 'pass' | 'reject' | 'wait' | string;
  rejectCause: string | null;
  name: string | null;
  picUrl: string | null;
}

interface GatherProgress {
  gatherLocked: boolean;
  doorStatus: unknown;
  trainingProducts: unknown;
  checks: GatherCheckItem[];
}

const gatherSection = ref(false);
const gatherProgress = ref<GatherProgress | null>(null);
const gatherLoading = ref(false);
const gatherStarting = ref(false);
const gatherDoorPosition = ref('');
const trainingForm = reactive({ skuId: '', modelName: '' });
const trainingSaving = ref(false);

const gatherCheckMeta: Record<string, { label: string; type: 'success' | 'danger' | 'info' }> = {
  pass: { label: '通过', type: 'success' },
  reject: { label: '驳回', type: 'danger' },
  wait: { label: '待审核', type: 'info' }
};

async function loadGatherProgress() {
  gatherLoading.value = true;
  try {
    gatherProgress.value = await api.request<GatherProgress>(
      AdminEndpoints.deviceJiangyiGatherProgress(props.deviceId),
      'GET'
    );
  } catch (e) {
    ElMessage.error(errorMessage(e, '加载采集进度失败'));
  } finally {
    gatherLoading.value = false;
  }
}

function toggleGatherSection() {
  gatherSection.value = !gatherSection.value;
  if (gatherSection.value && !gatherProgress.value) {
    void loadGatherProgress();
  }
}

async function doStartGather() {
  try {
    await ElMessageBox.confirm(
      '进入采集模式后营业开门将被拒绝（409），直到手动退出。将同时调将邑侧采集开门。确认？',
      '进入采集模式',
      { type: 'warning', confirmButtonText: '进入', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  gatherStarting.value = true;
  try {
    await api.request(AdminEndpoints.deviceJiangyiGatherStart(props.deviceId), 'POST', {
      doorPosition: gatherDoorPosition.value.trim() || undefined
    });
    ElMessage.success('已进入采集模式');
    await loadGatherProgress();
  } catch (e) {
    ElMessage.error(errorMessage(e, '进入采集模式失败'));
  } finally {
    gatherStarting.value = false;
  }
}

async function doExitGather() {
  gatherStarting.value = true;
  try {
    await api.request(AdminEndpoints.deviceJiangyiGatherExit(props.deviceId), 'POST');
    ElMessage.success('已退出采集模式，恢复营业');
    await loadGatherProgress();
  } catch (e) {
    ElMessage.error(errorMessage(e, '退出采集模式失败'));
  } finally {
    gatherStarting.value = false;
  }
}

async function doStartTraining() {
  const skuId = trainingForm.skuId.trim();
  const modelName = trainingForm.modelName.trim();
  if (!skuId || !modelName) {
    ElMessage.warning('请填写 SKU 编号与模型名');
    return;
  }
  trainingSaving.value = true;
  try {
    await api.request(AdminEndpoints.deviceJiangyiGatherTraining(props.deviceId), 'POST', {
      skuId,
      modelName
    });
    ElMessage.success('学习已提交，等待将邑完成回调');
    trainingForm.skuId = '';
    trainingForm.modelName = '';
    await loadGatherProgress();
  } catch (e) {
    ElMessage.error(errorMessage(e, '提交学习失败'));
  } finally {
    trainingSaving.value = false;
  }
}

defineExpose({ reload: load });

onMounted(load);
</script>

<template>
  <el-card v-loading="loading" class="page-card" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">将邑接入（开门柜套件）</span>
            <span class="hint">SN 登记 → setDomain 绑定 → class 映射；库存走「货道管理」</span>
          </div>
        </div>
        <el-tag v-if="statusMeta" :type="statusMeta.type" size="small">{{
          statusMeta.label
        }}</el-tag>
      </div>
    </template>

    <!-- 未登记：给登记表单（普通柜机不接入将邑可整卡忽略） -->
    <template v-if="!binding">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="该柜机尚未接入将邑"
        description="弹簧柜加装将邑工控机后变为开门柜：登记工控机 SN → 调将邑云 setDomain 指向我方 → 配置识别 classId 与 SKU 的映射。不接入的柜机可忽略本卡片。"
      />
      <div v-if="canEdit" class="jy-form-row">
        <el-input
          v-model="registerForm.deviceSn"
          class="jy-input-sn"
          placeholder="工控机 SN（必填）"
          clearable
        />
        <el-input
          v-model="registerForm.modelName"
          class="jy-input"
          placeholder="设备型号（可选）"
          clearable
        />
        <el-button type="primary" :loading="registerSaving" @click="doRegister">登记</el-button>
      </div>
    </template>

    <template v-else>
      <el-descriptions :column="3" border size="small">
        <el-descriptions-item label="工控机 SN">
          <span class="jy-mono">{{ binding.deviceSn }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="identifier">
          <span class="jy-mono">{{ binding.identifier || '未取得' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="设备型号">{{
          binding.modelName || '无'
        }}</el-descriptions-item>
        <el-descriptions-item label="token 版本">{{
          binding.tokenVersion ?? 0
        }}</el-descriptions-item>
        <el-descriptions-item label="最近 WS 在线">{{
          binding.lastWsOnlineAt ? formatDateTime(binding.lastWsOnlineAt) : '从未'
        }}</el-descriptions-item>
        <el-descriptions-item label="登记时间">{{
          formatDateTime(binding.createdAt)
        }}</el-descriptions-item>
      </el-descriptions>

      <!-- UNBOUND：补 setDomain 绑定表单 -->
      <div v-if="binding.status === 'UNBOUND' && canEdit" class="jy-bind-block">
        <div class="jy-section-label">setDomain 绑定（需部署侧已配置将邑租户凭据）</div>
        <div class="jy-form-row">
          <el-input
            v-model="bindForm.domain"
            class="jy-input-sn"
            placeholder="公网 domain，如 https://api.example.com"
            clearable
          />
          <el-input
            v-model="bindForm.socketUrl"
            class="jy-input-sn"
            placeholder="WSS 地址（可选，默认 domain 推导）"
            clearable
          />
          <el-button type="primary" :loading="bindSaving" @click="doBind">绑定</el-button>
        </div>
      </div>

      <div v-if="binding.status === 'RETIRED'" class="jy-bind-block">
        <el-alert
          type="error"
          :closable="false"
          show-icon
          title="设备已退役"
          description="退役后将邑柜拒绝一切开门路由与识别上报；如需恢复请联系平台重新登记绑定。"
        />
      </div>

      <div v-if="binding.status === 'BOUND' && canEdit" class="jy-actions">
        <el-popconfirm
          title="退役后该柜机拒绝一切开门路由与上报，确认退役？"
          confirm-button-text="退役"
          cancel-button-text="取消"
          @confirm="doRetire"
        >
          <template #reference>
            <el-button type="danger" plain :loading="retireSaving">退役设备</el-button>
          </template>
        </el-popconfirm>
      </div>

      <!-- class 映射：识别上报只有 classId，结算前必须换算成 SKU -->
      <div class="jy-mapping-block">
        <div class="jy-section-label">识别映射（classId → SKU）</div>
        <el-table :data="mappings" size="small" empty-text="暂无映射；识别到未映射商品会转争议处理">
          <el-table-column prop="classId" label="classId" width="90" />
          <el-table-column prop="textName" label="商品名" min-width="140">
            <template #default="{ row }">{{ row.textName || '—' }}</template>
          </el-table-column>
          <el-table-column prop="skuId" label="SKU" min-width="140">
            <template #default="{ row }">
              <span class="jy-mono">{{ row.skuId || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="modelName" label="识别型号" min-width="110">
            <template #default="{ row }">{{ row.modelName || '—' }}</template>
          </el-table-column>
          <el-table-column prop="source" label="来源" width="90">
            <template #default="{ row }">{{
              row.source === 'MODEL_SYNC' ? '模型同步' : '人工'
            }}</template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="80">
            <template #default="{ row }">
              <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
                {{ row.status === 'ACTIVE' ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column v-if="canEdit" label="操作" width="90">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="toggleMapping(row)">
                {{ row.status === 'ACTIVE' ? '停用' : '启用' }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <div v-if="canEdit" class="jy-form-row jy-mapping-form">
          <el-input-number
            v-model="mappingForm.classId"
            class="jy-input-class"
            :min="0"
            :controls="false"
            placeholder="classId"
          />
          <el-input
            v-model="mappingForm.skuId"
            class="jy-input"
            placeholder="SKU 编号（必填）"
            clearable
          />
          <el-input
            v-model="mappingForm.textName"
            class="jy-input"
            placeholder="商品名（可选）"
            clearable
          />
          <el-input
            v-model="mappingForm.modelName"
            class="jy-input"
            placeholder="识别型号（可选）"
            clearable
          />
          <el-checkbox v-model="mappingForm.active">保存即启用</el-checkbox>
          <el-button type="primary" :loading="mappingSaving" @click="saveMapping">
            添加映射
          </el-button>
        </div>
      </div>

      <!-- 模型同步（CB-023 二期）：采集→学习→模型 → classes 对照预生成 → 人工激活 → WS 下发 -->
      <div v-if="binding.status === 'BOUND'" class="jy-mapping-block">
        <div class="jy-section-label jy-collapse-head" @click="toggleModelSection">
          <span>模型同步（classes 对照 → 预生成 → 激活 → 下发）</span>
          <el-icon class="jy-collapse-arrow" :class="{ open: modelSection }">
            <component :is="modelSection ? 'el-icon-arrow-up' : 'el-icon-arrow-down'" />
          </el-icon>
        </div>
        <template v-if="modelSection">
          <el-alert
            type="info"
            :closable="false"
            show-icon
            class="jy-tip"
            title="预生成不自动生效：classes 行 ↔ SKU 挂接核对无误后手动激活，再经 WS 下发到设备"
          />
          <div class="jy-form-row">
            <el-select v-model="classIdBase" class="jy-input-class" size="small">
              <el-option label="classId 从 0 起" :value="0" />
              <el-option label="classId 从 1 起" :value="1" />
            </el-select>
            <el-button size="small" :loading="modelsLoading" @click="loadModels">
              刷新模型列表
            </el-button>
          </div>
          <el-table
            :data="models"
            size="small"
            empty-text="暂无模型；设备采集并学习成功后模型出现在将邑侧"
          >
            <el-table-column prop="modelName" label="模型" min-width="130" />
            <el-table-column prop="industrialControlModel" label="机型" width="70" />
            <el-table-column prop="quantity" label="商品数" width="80" />
            <el-table-column label="已学习匹配" width="100">
              <template #default="{ row }">
                <el-tag
                  size="small"
                  :type="
                    row.rejectReason
                      ? 'danger'
                      : row.trainedMatchPercent >= 100
                        ? 'success'
                        : 'warning'
                  "
                >
                  {{ row.rejectReason ? '解析被拒' : `${row.trainedMatchPercent}%` }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="classesVersion" label="classes 指纹" width="120">
              <template #default="{ row }">
                <span class="jy-mono">{{ row.classesVersion || row.rejectReason || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column v-if="canEdit" label="操作" width="200">
              <template #default="{ row }">
                <el-button
                  link
                  type="primary"
                  size="small"
                  :loading="modelSyncing"
                  @click="doModelSync(row)"
                >
                  预生成映射
                </el-button>
                <el-button
                  link
                  type="success"
                  size="small"
                  :loading="activating"
                  @click="doActivate(row)"
                >
                  激活
                </el-button>
                <el-button
                  link
                  type="warning"
                  size="small"
                  :loading="pushing"
                  @click="doModelPush(row)"
                >
                  下发
                </el-button>
              </template>
            </el-table-column>
          </el-table>

          <div class="jy-section-label" style="margin-top: 12px">
            下发记录（SENT 待回执 / CONFIRMED 已生效 / FAILED 失败）
          </div>
          <div class="jy-form-row" style="margin-top: 0">
            <el-button size="small" :loading="deploymentsLoading" @click="loadDeployments">
              刷新下发记录
            </el-button>
          </div>
          <el-table :data="deployments" size="small" empty-text="暂无下发记录">
            <el-table-column prop="id" label="ID" width="70" />
            <el-table-column prop="modelName" label="模型" min-width="120" />
            <el-table-column prop="classesVersion" label="classes 指纹" width="120">
              <template #default="{ row }">
                <span class="jy-mono">{{ row.classesVersion || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="100">
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
            <el-table-column prop="sentAt" label="下发时间" min-width="140">
              <template #default="{ row }">
                {{ row.sentAt ? formatDateTime(row.sentAt) : '—' }}
              </template>
            </el-table-column>
            <el-table-column prop="failReason" label="失败原因" min-width="140">
              <template #default="{ row }">{{ row.failReason || '—' }}</template>
            </el-table-column>
          </el-table>
        </template>
      </div>

      <!-- 采集编排（CB-023 二期）：进入采集模式（营业开门 409）→ 学习 → 审核进度 -->
      <div v-if="binding.status === 'BOUND'" class="jy-mapping-block">
        <div class="jy-section-label jy-collapse-head" @click="toggleGatherSection">
          <span>采集模式（新增商品采集 / 学习触发 / 审核进度）</span>
          <el-icon class="jy-collapse-arrow" :class="{ open: gatherSection }">
            <component :is="gatherSection ? 'el-icon-arrow-up' : 'el-icon-arrow-down'" />
          </el-icon>
        </div>
        <template v-if="gatherSection">
          <el-alert
            type="warning"
            :closable="false"
            show-icon
            class="jy-tip"
            title="采集模式下营业开门将被拒绝；采集开门由将邑 App 直接下发，批次操作在将邑商户 App 人工完成"
          />
          <div class="jy-form-row">
            <el-input
              v-model="gatherDoorPosition"
              class="jy-input"
              placeholder="门位（可选）"
              clearable
            />
            <el-button
              v-if="!gatherProgress?.gatherLocked"
              type="warning"
              :loading="gatherStarting"
              @click="doStartGather"
            >
              进入采集模式
            </el-button>
            <el-button v-else type="success" :loading="gatherStarting" @click="doExitGather">
              退出采集模式（恢复营业）
            </el-button>
            <el-button :loading="gatherLoading" @click="loadGatherProgress">刷新进度</el-button>
            <el-tag
              v-if="gatherProgress"
              :type="gatherProgress.gatherLocked ? 'warning' : 'info'"
              size="small"
            >
              {{ gatherProgress.gatherLocked ? '采集中' : '营业中' }}
            </el-tag>
          </div>

          <div class="jy-section-label" style="margin-top: 12px">
            触发学习（采集完成后提交训练）
          </div>
          <div v-if="canEdit" class="jy-form-row">
            <el-input
              v-model="trainingForm.skuId"
              class="jy-input"
              placeholder="SKU 编号"
              clearable
            />
            <el-input
              v-model="trainingForm.modelName"
              class="jy-input"
              placeholder="目标模型名"
              clearable
            />
            <el-button type="primary" :loading="trainingSaving" @click="doStartTraining">
              提交学习
            </el-button>
          </div>

          <div class="jy-section-label" style="margin-top: 12px">采集审核进度</div>
          <el-table :data="gatherProgress?.checks || []" size="small" empty-text="暂无采集审核记录">
            <el-table-column prop="name" label="商品" min-width="140">
              <template #default="{ row }">{{ row.name || '—' }}</template>
            </el-table-column>
            <el-table-column prop="status" label="审核状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="gatherCheckMeta[row.status]?.type || 'info'">
                  {{ gatherCheckMeta[row.status]?.label || row.status }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="rejectCause" label="驳回原因" min-width="140">
              <template #default="{ row }">{{ row.rejectCause || '—' }}</template>
            </el-table-column>
          </el-table>
        </template>
      </div>
    </template>
  </el-card>
</template>

<style scoped>
.jy-form-row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-top: 12px;
  flex-wrap: wrap;
}
.jy-input-sn {
  flex: 2 1 260px;
}
.jy-input {
  flex: 1 1 160px;
}
.jy-input-class {
  width: 110px;
}
.jy-bind-block {
  margin-top: 12px;
}
.jy-section-label {
  font-size: 13px;
  color: var(--color-text-secondary);
  margin-bottom: 4px;
}
.jy-actions {
  margin-top: 12px;
}
.jy-mapping-block {
  margin-top: 16px;
}
.jy-mapping-form {
  margin-top: 10px;
}
.jy-mono {
  font-family: var(--font-mono);
  font-size: 12px;
}
.jy-collapse-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  cursor: pointer;
  user-select: none;
}
.jy-collapse-arrow {
  transition: transform 0.2s;
  margin-left: 6px;
}
.jy-tip {
  margin: 8px 0;
}
</style>
