<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
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
  industrialControlModel: string | null;
  classesVersion: string | null;
  status: 'UNBOUND' | 'BOUND' | 'RETIRED';
  tokenVersion: number;
  tokenIssuedAt: string | null;
  lastWsOnlineAt: string | null;
  createdAt: string;
  updatedAt: string;
  authEnabled?: boolean;
}

const props = defineProps<{
  deviceId: string;
  canEdit: boolean;
}>();

const router = useRouter();
const loading = ref(false);
const binding = ref<JiangyiDeviceBinding | null>(null);

const registerForm = reactive({ deviceSn: '', modelName: '' });
const registerSaving = ref(false);
const bindForm = reactive({ domain: '', socketUrl: '' });
const bindSaving = ref(false);
const activateForm = reactive({
  identifier: '',
  deviceName: '',
  deviceAddress: '',
  hasScreen: false
});
const activateSaving = ref(false);
const retireSaving = ref(false);
const industrialModelDraft = ref('');
const industrialModelSaving = ref(false);

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
    }>(AdminEndpoints.deviceJiangyi(props.deviceId), 'GET');
    binding.value = view.binding;
    industrialModelDraft.value = view.binding?.industrialControlModel || '';
    if (binding.value && !activateForm.identifier) {
      activateForm.identifier = binding.value.identifier || '';
    }
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

/** 修改 SN（仅 UNBOUND）：登记抄错的纠正入口，弹出输入框校验后调 update-sn 端点。 */
async function doUpdateSn() {
  if (!binding.value) return;
  try {
    const { value } = await ElMessageBox.prompt(
      '仅未绑定状态可修改；已绑定请先退役再重新登记接入。',
      '修改设备序列号 SN',
      {
        confirmButtonText: '保存',
        cancelButtonText: '取消',
        inputValue: binding.value.deviceSn,
        inputPattern: /\S+/,
        inputErrorMessage: 'SN 不能为空'
      }
    );
    const sn = (value || '').trim();
    if (!sn || sn === binding.value.deviceSn) return;
    await api.request(AdminEndpoints.deviceJiangyiUpdateSn(props.deviceId), 'POST', {
      deviceSn: sn
    });
    ElMessage.success('SN 已修改');
    await load();
  } catch (e) {
    if (e === 'cancel' || (e as { message?: string })?.message === 'cancel') return;
    ElMessage.error(errorMessage(e, '修改 SN 失败'));
  }
}

/** 登记工控机机型：须与将邑模型文件的 industrialControlModel 完全一致，否则模型下发被拒。 */
async function doSaveIndustrialModel(value: string) {
  if (!binding.value) return;
  const model = String(value || '').trim();
  if (!model || model === (binding.value.industrialControlModel || '')) return;
  industrialModelSaving.value = true;
  try {
    await api.request(AdminEndpoints.deviceJiangyiIndustrialControlModel(props.deviceId), 'PUT', {
      industrialControlModel: model
    });
    ElMessage.success('工控机机型已登记');
    await load();
  } catch (e) {
    ElMessage.error(errorMessage(e, '登记工控机机型失败'));
    industrialModelDraft.value = binding.value.industrialControlModel || '';
  } finally {
    industrialModelSaving.value = false;
  }
}

/** 将邑入库激活（V16 §5.4.10 confirmActivate）：identifier 缺省取已存 identifier。 */
async function doConfirmActivate() {
  if (!binding.value) return;
  const identifier = (activateForm.identifier.trim() || binding.value.identifier || '').trim();
  const deviceName = activateForm.deviceName.trim();
  const deviceAddress = activateForm.deviceAddress.trim();
  if (!identifier) {
    ElMessage.warning('请填写设备编码（铭牌「设备号」，如 CQYB10720）');
    return;
  }
  if (!deviceName || !deviceAddress) {
    ElMessage.warning('设备名称与投放地址为将邑必填项');
    return;
  }
  try {
    await ElMessageBox.confirm(
      `将把设备 ${identifier}（SN ${binding.value.deviceSn}）在将邑平台激活入库，确认继续？`,
      '将邑激活确认',
      { type: 'warning', confirmButtonText: '确认激活', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  activateSaving.value = true;
  try {
    await api.request(AdminEndpoints.deviceJiangyiConfirmActivate(props.deviceId), 'POST', {
      identifier,
      deviceName,
      deviceAddress,
      screen: activateForm.hasScreen
    });
    ElMessage.success('将邑激活完成，可继续「接入平台」');
    await load();
  } catch (e) {
    ElMessage.error(errorMessage(e, '将邑激活失败'));
  } finally {
    activateSaving.value = false;
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

// ---------- 审计 A1/P0-3 激活制：SN 可枚举，绑定≠在场；激活后方可领 token ----------
const authSaving = ref(false);

async function toggleAuth() {
  const activated = binding.value?.authEnabled === true;
  authSaving.value = true;
  try {
    const url = activated
      ? AdminEndpoints.deviceJiangyiDeactivate(props.deviceId)
      : AdminEndpoints.deviceJiangyiActivate(props.deviceId);
    await api.request(url, 'POST');
    ElMessage.success(activated ? '已停用，存量 token 已吊销' : '已激活，设备可凭 SN 领取 token');
    await load();
  } catch (e) {
    ElMessage.error(errorMessage(e, activated ? '停用失败' : '激活失败'));
  } finally {
    authSaving.value = false;
  }
}

/** 商品对照统一在「识别映射」独立页维护，本卡片只保留入口（两处共用同一组端点）。 */
function goMappings() {
  void router.push({ path: '/vision-mappings', query: { deviceId: props.deviceId } });
} // ---------- 模型同步（CB-023 二期）：classes 对照→预生成→人工激活→WS 下发→回执审计 ----------

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

/**
 * 台账 G-14：上方列表来自将邑 §4.4.4，租户没登记模型时它是空的——但 V16 §4.3.2.9 的下行
 * 载荷本来就是裸地址，所以厂商只要肯给 modelUrl + classes 地址，同一条通道照样能走。
 */
const explicitForm = reactive({ modelName: '', modelUrl: '', textUrl: '', quantity: 0 });
const explicitBusy = ref(false);

function explicitPayload() {
  return {
    modelName: explicitForm.modelName.trim(),
    modelUrl: explicitForm.modelUrl.trim(),
    textUrl: explicitForm.textUrl.trim(),
    quantity: explicitForm.quantity,
    classIdBase: classIdBase.value
  };
}

async function doExplicitSync() {
  explicitBusy.value = true;
  try {
    const written = await api.request<number>(
      AdminEndpoints.deviceJiangyiModelSyncExplicit(props.deviceId),
      'POST',
      explicitPayload()
    );
    ElMessage.success(`已按该 classes 文件生成 ${written} 行停用状态对照，请核对后启用`);
    await load();
  } catch (e) {
    ElMessage.error(errorMessage(e, '按地址生成对照表失败'));
  } finally {
    explicitBusy.value = false;
  }
}

async function doExplicitPush() {
  try {
    await ElMessageBox.confirm(
      `把「${explicitForm.modelName.trim()}」按地址发送到柜机（柜机加载模型后会重启）。确认发送？`,
      '发送模型确认',
      { type: 'warning', confirmButtonText: '发送', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  explicitBusy.value = true;
  try {
    await api.request<number>(
      AdminEndpoints.deviceJiangyiModelPushExplicit(props.deviceId),
      'POST',
      explicitPayload()
    );
    ElMessage.success('已发送，等待柜机确认回执');
    await loadDeployments();
  } catch (e) {
    ElMessage.error(errorMessage(e, '按地址发送模型失败'));
  } finally {
    explicitBusy.value = false;
  }
}

/**
 * 台账 G-9：V16 §5.5.3 能把 classes 行翻成厂商标准商品（品名 + 条码）。
 * 🔴 只读展示——厂商回的是「标准商品」，不是我方 SKU，按它自动挂接会把
 * 「厂商认为这是什么」当成「这个柜按哪个价结算」，挂错即错扣款。
 * 条码命中的本平台候选只给人看，多命中也不自动选。
 */
interface JiangyiSkuCandidate {
  skuId: string;
  skuName: string;
  barcode: string;
}

interface JiangyiDecodeRow {
  classId: number;
  textName: string;
  vendorClassId: number | null;
  stdSkuId: number | null;
  stdSkuName: string | null;
  vendorBarcode: string | null;
  localCandidates: JiangyiSkuCandidate[] | null;
  mappedSkuId: string | null;
  mappedSkuName: string | null;
  mappingStatus: string | null;
}

interface JiangyiDecodeView {
  classesVersion: string | null;
  classesLines: number;
  vendorRows: number;
  vendorMatchedRows: number;
  rows: JiangyiDecodeRow[] | null;
}

const decode = ref<JiangyiDecodeView | null>(null);
const decoding = ref(false);

function candidateText(row: JiangyiDecodeRow): string {
  const list = row.localCandidates || [];
  if (!list.length) return '';
  return list.map((c) => `${c.skuName}（${c.skuId}）`).join('、');
}

async function doClassDecode() {
  const textUrl = explicitForm.textUrl.trim();
  if (!textUrl) {
    ElMessage.warning('先填 classes 对照文件地址');
    return;
  }
  decoding.value = true;
  try {
    const query = new URLSearchParams({
      textUrl,
      quantity: String(explicitForm.quantity || 0),
      classIdBase: String(classIdBase.value)
    });
    decode.value = await api.request<JiangyiDecodeView>(
      `${AdminEndpoints.deviceJiangyiClassDecode(props.deviceId)}?${query.toString()}`,
      'GET'
    );
  } catch (e) {
    decode.value = null;
    ElMessage.error(errorMessage(e, '厂商对照解码失败'));
  } finally {
    decoding.value = false;
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

// ===== 设备控制（V363，图2 对照）：信号强度 + 音量设置 =====
interface SignalView {
  signalStrength?: number | null;
  reportedAt?: string | null;
}
const signal = ref<SignalView | null>(null);
const signalLoading = ref(false);
const pullLoading = ref(false);
const soundOn = ref(true);
const soundLevel = ref(1);
const soundSaving = ref(false);

async function loadSignal() {
  signalLoading.value = true;
  try {
    signal.value = await api.request<SignalView>(
      AdminEndpoints.deviceJiangyiSignal(props.deviceId)
    );
  } catch {
    signal.value = null;
  } finally {
    signalLoading.value = false;
  }
}

/** 下发 getDeviceInfo（§4.3.2.13）：设备回执后信号强度异步落库。当前固件未实现该回执。 */
async function pullDeviceInfo() {
  pullLoading.value = true;
  try {
    await api.request(AdminEndpoints.deviceJiangyiDeviceInfo(props.deviceId), 'POST');
    ElMessage.info('已下发实时拉取；该接口为厂商可选，固件未实现时不会回报信号');
  } catch (e) {
    ElMessage.error(errorMessage(e, '拉取失败（设备可能离线）'));
  } finally {
    pullLoading.value = false;
  }
}

async function saveSound() {
  soundSaving.value = true;
  try {
    await api.request(AdminEndpoints.deviceJiangyiSound(props.deviceId), 'PUT', {
      switchOn: soundOn.value,
      volume: soundLevel.value
    });
    ElMessage.success('音量设置已下发');
  } catch (e) {
    ElMessage.error(errorMessage(e, '音量下发失败'));
  } finally {
    soundSaving.value = false;
  }
}

// ===== 柜门控制（2026-10-23）：关门/回锁 §4.3.2.6、门磁查询 §4.3.2.12、重启 §4.3.2.1 =====
interface DoorStateView {
  doorState?: 'open' | 'close' | 'unknown' | null;
  lockState?: 'open' | 'close' | 'unknown' | null;
  reportedAt?: string | null;
}
const doorState = ref<DoorStateView | null>(null);
const doorStateLoading = ref(false);
const doorQueryLoading = ref(false);
const closeDoorLoading = ref(false);
const rebootLoading = ref(false);

/** unknown = 设备回了但锁控 GPIO 读不出来（固件 uploadDoorState 初值），与「从未上报」区分。 */
const switchLabel = (v?: string | null) =>
  v === 'open' ? '开' : v === 'close' ? '关' : v === 'unknown' ? '未知' : '未上报';

async function loadDoorState() {
  doorStateLoading.value = true;
  try {
    doorState.value = await api.request<DoorStateView>(
      AdminEndpoints.deviceJiangyiDoorState(props.deviceId)
    );
  } catch {
    // 保留上次快照：一次网络抖动不该把已读到的门磁状态抹成「未上报」
  } finally {
    doorStateLoading.value = false;
  }
}

/** 下发 getDoorState：设备经 §4.2.16 回执异步落库，故延迟两次刷新（弱网下设备回执可能超过 3s）。 */
async function queryDoorState() {
  doorQueryLoading.value = true;
  try {
    await api.request(AdminEndpoints.deviceJiangyiDoorState(props.deviceId), 'POST');
    ElMessage.info('已下发门磁查询；设备回报后自动刷新（可选接口，固件未实现则无回执）');
    window.setTimeout(() => void loadDoorState(), 3000);
    window.setTimeout(() => void loadDoorState(), 10000);
  } catch (e) {
    ElMessage.error(errorMessage(e, '门磁查询下发失败（设备可能离线）'));
  } finally {
    doorQueryLoading.value = false;
  }
}

async function closeDoor() {
  try {
    await ElMessageBox.confirm(
      '将向柜机下发关门/回锁指令。该指令为厂商可选命令、无回执，下发后请到现场确认柜门已锁。',
      '关闭柜门',
      { type: 'warning', confirmButtonText: '下发', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  closeDoorLoading.value = true;
  try {
    await api.request(AdminEndpoints.deviceJiangyiCloseDoor(props.deviceId), 'POST');
    ElMessage.success('关门指令已下发（无回执，请现场确认已锁）');
  } catch (e) {
    ElMessage.error(errorMessage(e, '关门指令下发失败（设备可能离线）'));
  } finally {
    closeDoorLoading.value = false;
  }
}

async function rebootDevice() {
  try {
    await ElMessageBox.confirm(
      '将重启柜机主控。设备会短暂离线（WS 断开、下单/开门不可用），确认现场无人在购物。',
      '重启设备',
      { type: 'warning', confirmButtonText: '重启', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  rebootLoading.value = true;
  try {
    await api.request(AdminEndpoints.deviceJiangyiReboot(props.deviceId), 'POST');
    ElMessage.success('重启指令已下发，设备将短暂离线');
  } catch (e) {
    ElMessage.error(errorMessage(e, '重启指令下发失败（设备可能离线）'));
  } finally {
    rebootLoading.value = false;
  }
}

onMounted(load);
// 绑定成功（BOUND）后补充拉一次信号强度（未绑定设备该接口 400，静默忽略）
watch(
  () => props.deviceId,
  () => {
    if (binding.value?.status === 'BOUND') {
      void loadSignal();
      void loadDoorState();
    }
  }
);
watch(
  () => binding.value?.status,
  (status) => {
    if (status === 'BOUND') {
      void loadSignal();
      void loadDoorState();
    }
  }
);
</script>

<template>
  <el-card v-loading="loading" class="page-card" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">识别开门柜套件</span>
            <span class="hint">登记设备 → 接入平台 → 配置识别映射；库存走「货道陈列」</span>
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
          <el-tooltip
            v-if="canEdit && binding.status === 'UNBOUND'"
            content="登记时 SN 抄错可在此纠正（仅未绑定状态）"
          >
            <el-button link type="primary" size="small" @click="doUpdateSn">修改</el-button>
          </el-tooltip>
        </el-descriptions-item>
        <el-descriptions-item label="设备标识">
          <span class="jy-mono">{{ binding.identifier || '未取得' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="设备型号">{{
          binding.modelName || '无'
        }}</el-descriptions-item>
        <el-descriptions-item label="工控机机型">
          <el-select
            v-if="canEdit && binding.status !== 'RETIRED'"
            v-model="industrialModelDraft"
            class="jy-input-class"
            size="small"
            placeholder="未登记（下发会拒绝）"
            :disabled="industrialModelSaving"
            @change="doSaveIndustrialModel"
          >
            <el-option label="76" value="76" />
            <el-option label="88" value="88" />
          </el-select>
          <span v-else class="jy-mono">{{ binding.industrialControlModel || '未登记' }}</span>
        </el-descriptions-item>
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

      <!-- 将邑入库激活（V16 §5.4.10）：仅未绑定设备的首次入库；BOUND 后已激活，再点会重复写厂商云 -->
      <div v-if="canEdit && binding.status === 'UNBOUND'" class="jy-bind-block">
        <div class="jy-section-label">
          将邑入库激活（出厂新设备首次使用；对照铭牌「设备号」填写，运维小程序扫码激活的接口等价）
        </div>
        <div class="jy-form-row">
          <el-input
            v-model="activateForm.identifier"
            class="jy-input"
            placeholder="设备编码（铭牌设备号，如 CQYB10720）"
            clearable
          />
          <el-input
            v-model="activateForm.deviceName"
            class="jy-input"
            placeholder="设备名称（必填）"
            clearable
          />
          <el-input
            v-model="activateForm.deviceAddress"
            class="jy-input"
            placeholder="投放地址（必填）"
            clearable
          />
          <el-checkbox v-model="activateForm.hasScreen">有广告屏</el-checkbox>
          <el-button type="primary" :loading="activateSaving" @click="doConfirmActivate">
            确认激活
          </el-button>
        </div>
      </div>

      <!-- UNBOUND：补接入绑定表单 -->
      <div v-if="binding.status === 'UNBOUND' && canEdit" class="jy-bind-block">
        <div class="jy-section-label">接入平台（把该设备的服务地址指向我方平台）</div>
        <div class="jy-form-row">
          <el-input
            v-model="bindForm.domain"
            class="jy-input-sn"
            placeholder="平台接口地址，须含 /jiangyi/api 尾段，如 https://gate.example.com/jiangyi/api"
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

      <!-- 设备控制（V363，图2 对照）：信号强度 + 音量设置；网络延迟/拍照/断电协议无接口不做 -->
      <div v-if="binding.status === 'BOUND'" class="jy-bind-block">
        <div class="jy-section-label">设备控制（信号 / 音量 / 柜门）</div>
        <div class="jy-signal-row">
          <span>
            信号强度：
            <strong>{{
              signal?.signalStrength != null ? `${signal.signalStrength}/4` : '未上报（设备未回报）'
            }}</strong>
            <span v-if="signal?.reportedAt" class="jy-signal-at">
              （{{ formatDateTime(signal.reportedAt) }}）
            </span>
          </span>
          <el-button size="small" :loading="signalLoading" @click="loadSignal">刷新</el-button>
          <el-button size="small" :loading="pullLoading" @click="pullDeviceInfo"
            >实时拉取</el-button
          >
        </div>
        <div class="jy-signal-hint">
          信号强度走将邑可选接口（§4.2.15 上报 / §4.3.2.13
          拉取）；实测当前固件下发后不回报，数值为空属固件未实现，待厂商固件支持后才会出现。
        </div>
        <div v-if="canEdit" class="jy-sound-row">
          <span class="jy-sound-label">音量设置：</span>
          <el-switch v-model="soundOn" active-text="开" inactive-text="关" />
          <el-radio-group v-model="soundLevel" size="small">
            <el-radio-button :value="0">大</el-radio-button>
            <el-radio-button :value="1">中</el-radio-button>
            <el-radio-button :value="2">小</el-radio-button>
          </el-radio-group>
          <el-button type="primary" size="small" :loading="soundSaving" @click="saveSound"
            >下发</el-button
          >
        </div>

        <!-- 柜门控制（2026-10-23）：关门 §4.3.2.6 / 门磁查询 §4.3.2.12 / 重启 §4.3.2.1 -->
        <div class="jy-door-row">
          <span>
            门磁：
            <strong>门 {{ switchLabel(doorState?.doorState) }}</strong>
            ·
            <strong>锁 {{ switchLabel(doorState?.lockState) }}</strong>
            <span v-if="doorState?.reportedAt" class="jy-signal-at">
              （{{ formatDateTime(doorState.reportedAt) }}）
            </span>
          </span>
          <el-button size="small" :loading="doorStateLoading" @click="loadDoorState"
            >刷新</el-button
          >
          <template v-if="canEdit">
            <el-button size="small" :loading="doorQueryLoading" @click="queryDoorState"
              >查询门磁</el-button
            >
            <el-button type="warning" size="small" :loading="closeDoorLoading" @click="closeDoor"
              >关闭柜门</el-button
            >
            <el-button
              type="danger"
              plain
              size="small"
              :loading="rebootLoading"
              @click="rebootDevice"
              >重启设备</el-button
            >
          </template>
        </div>
        <div class="jy-signal-hint">
          门磁走将邑可选接口（§4.3.2.12 下发 / §4.2.16 回报）——设备自报口径，仅作现场参考；
          「未知」= 设备回报了但锁控读不出（固件初值），与「未上报」不同。
          关门（§4.3.2.6）与重启（§4.3.2.1）均无回执，下发后需现场确认。
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
          :title="
            binding.authEnabled
              ? '停用后设备无法领取 token、存量 token 即刻吊销，确认停用？'
              : '激活后该设备可凭 SN 领取 token（请确认设备已在现场），确认激活？'
          "
          confirm-button-text="确认"
          cancel-button-text="取消"
          @confirm="toggleAuth"
        >
          <template #reference>
            <el-button
              :type="binding.authEnabled ? 'warning' : 'success'"
              plain
              :loading="authSaving"
            >
              {{ binding.authEnabled ? '停用 token 签发' : '激活 token 签发' }}
            </el-button>
          </template>
        </el-popconfirm>
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

      <!-- 商品对照：柜机识别上报的是「识别编号」，结算前必须换算成商品；表格统一在识别映射页维护 -->
      <div v-if="binding.status !== 'RETIRED'" class="jy-mapping-block">
        <div class="jy-section-label">识别映射（识别编号 ↔ 平台商品）</div>
        <div class="jy-mapping-entry">
          <span class="jy-mapping-hint">
            对照表、模型预生成与启用都在「识别映射」页维护；未挂接的识别会转争议处理。
          </span>
          <el-button type="primary" plain size="small" @click="goMappings">去识别映射</el-button>
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

          <div class="jy-section-label" style="margin-top: 12px">
            上方列表为空时：按厂商给的地址直接处理
          </div>
          <el-alert
            type="warning"
            :closable="false"
            show-icon
            class="jy-tip"
            title="这条不查将邑模型列表，所以校验不了工控机机型——地址与该柜型号是否对应由操作人确认；classes 文件行数必须等于商品数量，否则拒绝生成对照"
          />
          <div class="jy-form-row">
            <el-input
              v-model="explicitForm.modelName"
              size="small"
              placeholder="模型名称（柜机回执按它比对，例：hysxr…3576.rknn）"
            />
            <el-input-number
              v-model="explicitForm.quantity"
              size="small"
              :min="1"
              controls-position="right"
              class="jy-input-class"
              placeholder="商品数量"
            />
          </div>
          <div class="jy-form-row">
            <el-input
              v-model="explicitForm.modelUrl"
              size="small"
              placeholder="模型下载地址（.rknn）"
            />
          </div>
          <div class="jy-form-row">
            <el-input
              v-model="explicitForm.textUrl"
              size="small"
              placeholder="classes 对照文件地址（.txt）"
            />
          </div>
          <div v-if="canEdit" class="jy-form-row">
            <el-button size="small" :loading="explicitBusy" @click="doExplicitSync">
              按地址生成对照
            </el-button>
            <el-button size="small" type="warning" :loading="explicitBusy" @click="doExplicitPush">
              按地址发到柜机
            </el-button>
          </div>

          <div class="jy-form-row">
            <el-button size="small" :loading="decoding" @click="doClassDecode">
              查厂商商品对照（只读）
            </el-button>
            <span v-if="decode" class="jy-mapping-hint">
              classes 共 {{ decode.classesLines }} 行，厂商标准商品库认出
              {{ decode.vendorMatchedRows }} 行
            </span>
          </div>
          <el-alert
            v-if="decode"
            type="info"
            :closable="false"
            show-icon
            class="jy-tip"
            title="这里只把识别编号翻成人话，方便逐条挂接平台商品；不改动对照表，条码相同也不会自动挂"
          />
          <el-table
            v-if="decode"
            :data="decode.rows || []"
            size="small"
            max-height="260"
            empty-text="该 classes 文件没有可显示的行"
          >
            <el-table-column prop="classId" label="识别编号" width="86" />
            <el-table-column label="识别名（classes 原文）" min-width="160">
              <template #default="{ row }">
                <span class="jy-mono">{{ row.textName }}</span>
              </template>
            </el-table-column>
            <el-table-column label="厂商商品" min-width="150">
              <template #default="{ row }">
                <el-tag v-if="!row.stdSkuName" size="small" type="info">厂商库无对应</el-tag>
                <span v-else>{{ row.stdSkuName }}</span>
              </template>
            </el-table-column>
            <el-table-column label="厂商条码" width="130">
              <template #default="{ row }">
                <span class="jy-mono">{{ row.vendorBarcode || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="本平台同条码商品" min-width="160">
              <template #default="{ row }">
                <el-tag v-if="(row.localCandidates || []).length > 1" size="small" type="warning">
                  {{ (row.localCandidates || []).length }} 条，需人工分辨
                </el-tag>
                <span v-if="candidateText(row)" class="jy-mono">{{ candidateText(row) }}</span>
                <el-tag
                  v-else-if="row.vendorBarcode"
                  size="small"
                  type="info"
                  :class="{ 'jy-tag-gap': (row.localCandidates || []).length > 1 }"
                >
                  本平台无同条码商品
                </el-tag>
                <span v-else>—</span>
              </template>
            </el-table-column>
            <el-table-column label="当前挂接" min-width="130">
              <template #default="{ row }">
                <el-tag v-if="!row.mappedSkuId" size="small" type="info">未挂接</el-tag>
                <template v-else>
                  <el-button link type="primary" size="small" @click="goMappings">
                    {{ row.mappedSkuName || row.mappedSkuId }}
                  </el-button>
                  <el-tag
                    size="small"
                    :type="row.mappingStatus === 'ACTIVE' ? 'success' : 'warning'"
                    class="jy-tag-gap"
                  >
                    {{ row.mappingStatus === 'ACTIVE' ? '已启用' : '停用' }}
                  </el-tag>
                </template>
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
.jy-signal-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.jy-signal-at {
  color: var(--color-text-secondary);
  font-size: 12px;
}
.jy-signal-hint {
  margin: -2px 0 8px;
  font-size: 12px;
  color: var(--color-text-secondary);
}
.jy-sound-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.jy-door-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 8px;
}
.jy-sound-label {
  color: var(--color-text-secondary);
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
.jy-mapping-entry {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.jy-mapping-hint {
  flex: 1 1 320px;
  font-size: var(--admin-font-size-sm);
  color: var(--color-text-secondary);
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
.jy-tag-gap {
  margin-left: 6px;
}
</style>
