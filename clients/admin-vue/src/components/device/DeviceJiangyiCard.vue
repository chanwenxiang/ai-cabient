<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { errorMessage } from '@/utils/error-message';
import { formatDateTime } from '@aicabinet/shared-uni/format';
import VisionFlowSteps from '@/components/vision/VisionFlowSteps.vue';
import DeviceJiangyiRestockPanel from '@/components/device/DeviceJiangyiRestockPanel.vue';
import DeviceJiangyiCapturePanel from '@/components/device/DeviceJiangyiCapturePanel.vue';

/**
 * 将邑接入卡片（CB-022 收尾）：把此前只能手工 SQL 的登记/绑定/映射升级为后台可操作。
 * 流程＝SN 登记（UNBOUND）→ setDomain 绑定（BOUND）→ class_id→SKU 映射（识别结算的唯一换算依据）。
 * 库存不在此处：device_sku_inventory 由补货/理货流程维护，将邑柜直接复用。
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

// doRetire / toggleAuth（token 签发与退役）随设备控制一起移入「设备概览」的 DeviceJiangyiControlCard。

/** 商品对照统一在「识别映射」独立页维护，本卡片只保留入口（两处共用同一组端点）。 */
function goMappings() {
  void router.push({ path: '/vision-mappings', query: { deviceId: props.deviceId } });
}

const modelSection = ref(false);
const classIdBase = ref(0);

function toggleModelSection() {
  modelSection.value = !modelSection.value;
}

/**
 * 诊断专用输入（只读解码用）：classes 地址 + 行数，配合识别编号方向把当前模型的
 * classes 行翻成人话。模型同步三连的录入表单已随双入口一起删除（统一在识别映射页）。
 */
const decodeForm = reactive({ textUrl: '', quantity: 0 });

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
  const textUrl = decodeForm.textUrl.trim();
  if (!textUrl) {
    ElMessage.warning('先填 classes 对照文件地址');
    return;
  }
  decoding.value = true;
  try {
    const query = new URLSearchParams({
      textUrl,
      quantity: String(decodeForm.quantity || 0),
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

defineExpose({ reload: load });
// 设备控制（信号/音量/柜门/token/退役）已整体移入「设备概览」的 DeviceJiangyiControlCard。

onMounted(load);
</script>

<template>
  <el-card v-loading="loading" class="page-card" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">识别开门柜套件</span>
            <span class="hint">
              挂接 → 采集（手机/柜内）→ 映射下发 → 整单上架；库存由补货/理货流程维护
            </span>
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
      <VisionFlowSteps :active="0" />
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

      <!-- 设备控制（信号/音量/柜门）与 token/退役 已移入「设备概览」（DeviceJiangyiControlCard） -->

      <div v-if="binding.status === 'RETIRED'" class="jy-bind-block">
        <el-alert
          type="error"
          :closable="false"
          show-icon
          title="设备已退役"
          description="退役后该柜机停止识别开门与上报；如需恢复请联系平台重新登记接入。"
        />
      </div>

      <!-- token 签发 / 退役 设备控制卡也移入「设备概览」了 -->

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

      <!-- 模型同步三连（预生成→启用→下发）统一在「识别映射」页维护；本卡不再复刻入口 -->
      <div v-if="binding.status === 'BOUND'" class="jy-mapping-block">
        <div class="jy-section-label">识别模型同步（预生成对照 → 核对启用 → 下发柜机）</div>
        <div class="jy-mapping-entry">
          <span class="jy-mapping-hint">
            统一在「识别映射」页按柜机操作；新商品采集走手机拍照（商户小程序）或下方「柜内采集」面板。
          </span>
          <el-button type="primary" plain size="small" @click="goMappings">去识别映射</el-button>
        </div>
      </div>

      <!-- 诊断工具（只读）：把当前模型 classes 行翻成人话，方便逐条挂接；不改对照表 -->
      <div v-if="binding.status === 'BOUND'" class="jy-mapping-block">
        <div class="jy-section-label jy-collapse-head" @click="toggleModelSection">
          <span>诊断工具（查厂商商品对照，只读）</span>
          <el-icon class="jy-collapse-arrow" :class="{ open: modelSection }">
            <component :is="modelSection ? 'el-icon-arrow-up' : 'el-icon-arrow-down'" />
          </el-icon>
        </div>
        <template v-if="modelSection">
          <div class="jy-form-row">
            <el-input
              v-model="decodeForm.textUrl"
              size="small"
              placeholder="classes 对照文件地址（.txt）"
            />
            <el-input-number
              v-model="decodeForm.quantity"
              size="small"
              :min="0"
              controls-position="right"
              class="jy-input-class"
              placeholder="商品数量"
            />
            <el-select v-model="classIdBase" class="jy-input-class" size="small">
              <el-option label="识别编号从 0 起" :value="0" />
              <el-option label="识别编号从 1 起" :value="1" />
            </el-select>
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
        </template>
      </div>
    </template>

    <DeviceJiangyiRestockPanel v-if="binding" :device-id="deviceId" :can-edit="canEdit" />
    <DeviceJiangyiCapturePanel v-if="binding" :device-id="deviceId" :can-edit="canEdit" />
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
  margin-top: 14px;
  padding: 14px 16px 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-extra-light);
}
.jy-section-label {
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  margin-bottom: 10px;
}
.jy-mapping-block {
  margin-top: 14px;
  padding: 14px 16px 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-extra-light);
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
