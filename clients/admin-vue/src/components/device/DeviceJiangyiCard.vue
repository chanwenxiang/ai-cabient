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
</style>
