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
    ElMessage.warning('请填写设备序列号 SN');
    return;
  }
  registerSaving.value = true;
  try {
    await api.request(AdminEndpoints.deviceJiangyiRegister(props.deviceId), 'POST', {
      deviceSn: sn,
      modelName: registerForm.modelName.trim() || undefined
    });
    ElMessage.success('已登记，请完成下方接入绑定');
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
    ElMessage.warning('请填写平台接口地址（如 https://api.example.com）');
    return;
  }
  try {
    await ElMessageBox.confirm(
      '将通知将邑平台，把该设备的服务地址切换到我方平台，确认继续？',
      '接入确认',
      { type: 'warning', confirmButtonText: '确认接入', cancelButtonText: '取消' }
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
    ElMessage.success('接入完成，等待柜机上电后自动连回平台');
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
    ElMessage.warning('请填写识别编号（数字）');
    return;
  }
  if (!skuId) {
    ElMessage.warning('请填写对应商品编号');
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
    ElMessage.success('对照已保存');
    mappingForm.classId = undefined;
    mappingForm.skuId = '';
    mappingForm.textName = '';
    mappingForm.modelName = '';
    mappingForm.active = true;
    await load();
  } catch (e) {
    ElMessage.error(errorMessage(e, '保存对照失败'));
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
    ElMessage.error(errorMessage(e, '变更对照状态失败'));
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
      `按「${row.modelName}」的识别类目生成该柜的商品对照表（生成后为停用状态，不会自动生效）。已自动生成的行会被覆盖，已手工挂接的商品保留。`,
      '生成对照表确认',
      { type: 'warning', confirmButtonText: '生成', cancelButtonText: '取消' }
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
    ElMessage.success(`已生成 ${written} 行停用状态对照，请核对后启用`);
    await load();
  } catch (e) {
    ElMessage.error(errorMessage(e, '生成对照表失败'));
  } finally {
    modelSyncing.value = false;
  }
}

async function doActivate(row: JiangyiModelPreview) {
  try {
    await ElMessageBox.confirm(
      `启用「${row.modelName}」的全部对照（启用后柜机识别的商品将按此对照结算）。确认对照表无误？`,
      '启用确认',
      { type: 'warning', confirmButtonText: '启用', cancelButtonText: '取消' }
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
    ElMessage.success(`已启用 ${n} 行对照`);
    await load();
  } catch (e) {
    ElMessage.error(errorMessage(e, '启用对照失败'));
  } finally {
    activating.value = false;
  }
}

async function doModelPush(row: JiangyiModelPreview) {
  try {
    await ElMessageBox.confirm(
      `将「${row.modelName}」发送到柜机（柜机确认后生效）。确认发送？`,
      '发送模型确认',
      { type: 'warning', confirmButtonText: '发送', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  pushing.value = true;
  try {
    await api.request<number>(AdminEndpoints.deviceJiangyiModelPush(props.deviceId), 'POST', {
      modelName: row.modelName
    });
    ElMessage.success('已发送，等待柜机确认回执');
    await loadDeployments();
  } catch (e) {
    ElMessage.error(errorMessage(e, '发送模型失败'));
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
    ElMessage.error(errorMessage(e, '加载发送记录失败'));
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
      '进入采集模式后，顾客将无法开门购物，直到手动退出。确认进入？',
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
    ElMessage.warning('请填写商品编号与模型名');
    return;
  }
  trainingSaving.value = true;
  try {
    await api.request(AdminEndpoints.deviceJiangyiGatherTraining(props.deviceId), 'POST', {
      skuId,
      modelName
    });
    ElMessage.success('学习已提交，等待将邑完成训练');
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
            <span class="title">识别开门柜套件</span>
            <span class="hint">登记设备 → 接入平台 → 配置商品对照；库存走「货道陈列」</span>
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
        title="该柜机未启用识别开门套件"
        description="加装识别套件后，顾客开门取货由摄像头自动识别结算。使用前需：登记设备序列号 → 接入我方平台 → 配置「识别编号 ↔ 商品」对照表。未启用的柜机可忽略本卡片。"
      />
      <div v-if="canEdit" class="jy-form-row">
        <el-input
          v-model="registerForm.deviceSn"
          class="jy-input-sn"
          placeholder="设备序列号 SN（必填）"
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
        <el-descriptions-item label="设备序列号">
          <span class="jy-mono">{{ binding.deviceSn }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="设备标识">
          <span class="jy-mono">{{ binding.identifier || '未取得' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="设备型号">{{
          binding.modelName || '无'
        }}</el-descriptions-item>
        <el-descriptions-item label="通信凭证版本">{{
          binding.tokenVersion ?? 0
        }}</el-descriptions-item>
        <el-descriptions-item label="最近在线">{{
          binding.lastWsOnlineAt ? formatDateTime(binding.lastWsOnlineAt) : '从未'
        }}</el-descriptions-item>
        <el-descriptions-item label="登记时间">{{
          formatDateTime(binding.createdAt)
        }}</el-descriptions-item>
      </el-descriptions>

      <!-- UNBOUND：补接入绑定表单 -->
      <div v-if="binding.status === 'UNBOUND' && canEdit" class="jy-bind-block">
        <div class="jy-section-label">接入平台（把该设备的服务地址指向我方平台）</div>
        <div class="jy-form-row">
          <el-input
            v-model="bindForm.domain"
            class="jy-input-sn"
            placeholder="平台接口地址，如 https://api.example.com"
            clearable
          />
          <el-input
            v-model="bindForm.socketUrl"
            class="jy-input-sn"
            placeholder="长连接地址（可选，默认自动生成）"
            clearable
          />
          <el-button type="primary" :loading="bindSaving" @click="doBind">确认接入</el-button>
        </div>
      </div>

      <div v-if="binding.status === 'RETIRED'" class="jy-bind-block">
        <el-alert
          type="error"
          :closable="false"
          show-icon
          title="设备已退役"
          description="退役后该柜机停止识别开门与上报；如需恢复请联系平台重新登记接入。"
        />
      </div>

      <div v-if="binding.status === 'BOUND' && canEdit" class="jy-actions">
        <el-popconfirm
          title="退役后该柜机停止识别开门与上报，确认退役？"
          confirm-button-text="退役"
          cancel-button-text="取消"
          @confirm="doRetire"
        >
          <template #reference>
            <el-button type="danger" plain :loading="retireSaving">退役设备</el-button>
          </template>
        </el-popconfirm>
      </div>

      <!-- 商品对照：柜机识别上报的是「识别编号」，结算前必须换算成商品 -->
      <div class="jy-mapping-block">
        <div class="jy-section-label">商品对照表（识别编号 ↔ 商品）</div>
        <el-table
          :data="mappings"
          size="small"
          empty-text="暂无对照；识别到未登记的商品会转争议处理"
        >
          <el-table-column prop="classId" label="识别编号" width="90" />
          <el-table-column prop="textName" label="商品名" min-width="140">
            <template #default="{ row }">{{ row.textName || '—' }}</template>
          </el-table-column>
          <el-table-column prop="skuId" label="商品编号" min-width="140">
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
            placeholder="识别编号"
          />
          <el-input
            v-model="mappingForm.skuId"
            class="jy-input"
            placeholder="商品编号（必填）"
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
            添加对照
          </el-button>
        </div>
      </div>

      <!-- 模型同步（CB-023 二期）：采集→学习→模型 → 对照预生成 → 人工启用 → 发到柜机 -->
      <div v-if="binding.status === 'BOUND'" class="jy-mapping-block">
        <div class="jy-section-label jy-collapse-head" @click="toggleModelSection">
          <span>识别模型同步（生成对照 → 核对启用 → 发到柜机）</span>
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
            title="生成对照不会自动生效：与商品核对无误后手动启用，再发送到柜机"
          />
          <div class="jy-form-row">
            <el-select v-model="classIdBase" class="jy-input-class" size="small">
              <el-option label="识别编号从 0 起" :value="0" />
              <el-option label="识别编号从 1 起" :value="1" />
            </el-select>
            <el-button size="small" :loading="modelsLoading" @click="loadModels">
              刷新模型列表
            </el-button>
          </div>
          <el-table
            :data="models"
            size="small"
            empty-text="暂无模型；柜机采集并学习成功后模型会出现在这里"
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
            <el-table-column prop="classesVersion" label="模型版本号" width="120">
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
                  生成对照
                </el-button>
                <el-button
                  link
                  type="success"
                  size="small"
                  :loading="activating"
                  @click="doActivate(row)"
                >
                  启用
                </el-button>
                <el-button
                  link
                  type="warning"
                  size="small"
                  :loading="pushing"
                  @click="doModelPush(row)"
                >
                  发到柜机
                </el-button>
              </template>
            </el-table-column>
          </el-table>

          <div class="jy-section-label" style="margin-top: 12px">发送记录</div>
          <div class="jy-form-row" style="margin-top: 0">
            <el-button size="small" :loading="deploymentsLoading" @click="loadDeployments">
              刷新发送记录
            </el-button>
          </div>
          <el-table :data="deployments" size="small" empty-text="暂无发送记录">
            <el-table-column prop="id" label="编号" width="70" />
            <el-table-column prop="modelName" label="模型" min-width="120" />
            <el-table-column prop="classesVersion" label="模型版本号" width="120">
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
                  {{
                    row.status === 'CONFIRMED'
                      ? '已生效'
                      : row.status === 'FAILED'
                        ? '失败'
                        : '等待回执'
                  }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="sentAt" label="发送时间" min-width="140">
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

      <!-- 采集编排（CB-023 二期）：进入采集模式（暂停营业）→ 学习 → 审核进度 -->
      <div v-if="binding.status === 'BOUND'" class="jy-mapping-block">
        <div class="jy-section-label jy-collapse-head" @click="toggleGatherSection">
          <span>商品采集（拍照采集 / 触发学习 / 审核进度）</span>
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
            title="采集期间顾客无法开门购物；拍照采集等具体操作在将邑商户 App 完成"
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
              placeholder="商品编号"
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
