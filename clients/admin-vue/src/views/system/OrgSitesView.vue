<template>
  <el-card class="page-card" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">组织与点位</span>
            <span class="hint">组织树 · 设备归属 · 点位场地合同</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button :icon="Refresh" :loading="loading" @click="loadAll">刷新</el-button>
        </div>
      </div>
    </template>

    <el-tabs v-model="tab">
      <el-tab-pane label="组织树" name="org">
        <div class="org-toolbar">
          <el-button v-hasPermi="['ops:org:edit']" size="small" type="primary" @click="openNode(null)">
            新增顶级组织
          </el-button>
        </div>
        <el-tree
          :data="orgTree"
          node-key="nodeId"
          :props="{ label: 'name', children: 'children' }"
          default-expand-all
        >
          <template #default="{ data }">
            <div class="tree-node">
              <span class="tree-name">{{ data.name }}</span>
              <el-tag size="small" :type="data.enabled ? 'success' : 'info'" effect="plain">
                {{ data.enabled ? '启用' : '停用' }} · {{ data.deviceIds.length }} 台
              </el-tag>
              <div class="tree-actions">
                <el-button v-hasPermi="['ops:org:edit']" size="small" link @click.stop="openNode(data)">
                  新增子级
                </el-button>
                <el-button v-hasPermi="['ops:org:edit']" size="small" link @click.stop="openAssign(data)">
                  分配设备
                </el-button>
                <el-button v-hasPermi="['ops:org:edit']" size="small" link @click.stop="toggleNode(data)">
                  {{ data.enabled ? '停用' : '启用' }}
                </el-button>
              </div>
            </div>
          </template>
        </el-tree>
      </el-tab-pane>

      <el-tab-pane label="场地合同" name="contracts">
        <div class="org-toolbar">
          <el-button v-hasPermi="['ops:org:edit']" size="small" type="primary" @click="openContract(null)">
            新增合同
          </el-button>
        </div>
        <el-table v-loading="loading" :data="contracts" stripe border>
          <el-table-column prop="deviceName" label="柜机" min-width="150" show-overflow-tooltip />
          <el-table-column prop="siteName" label="场地" min-width="150" show-overflow-tooltip />
          <el-table-column prop="address" label="地址" min-width="160" show-overflow-tooltip />
          <el-table-column prop="landlordName" label="场地主" width="100" />
          <el-table-column label="月费" width="100" align="center">
            <template #default="{ row }">¥{{ (row.monthlyFeeCents / 100).toFixed(0) }}</template>
          </el-table-column>
          <el-table-column label="到期" width="110" align="center">
            <template #default="{ row }">{{ row.endDate || '不限' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="contractStatusType(row.status)">
                {{ contractStatusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90" align="center">
            <template #default="{ row }">
              <el-button v-hasPermi="['ops:org:edit']" size="small" @click="openContract(row)">
                编辑
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="nodeVisible" :title="nodeForm.nodeId ? '编辑组织' : '新增组织'" width="420px">
      <el-form label-position="top">
        <el-form-item label="组织名称">
          <el-input v-model="nodeForm.name" placeholder="如：华南区 / 深圳分公司" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="nodeForm.nodeType" style="width: 100%">
            <el-option label="总部" value="HQ" />
            <el-option label="区域" value="REGION" />
            <el-option label="分公司" value="BRANCH" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="nodeVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveNode">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="assignVisible" :title="`分配设备到 ${assignNode?.name || ''}`" width="480px">
      <el-select v-model="assignDeviceIds" multiple filterable placeholder="选择柜机" style="width: 100%">
        <el-option
          v-for="d in deviceOptions"
          :key="d.deviceId"
          :label="d.deviceName || d.deviceId"
          :value="d.deviceId"
        />
      </el-select>
      <template #footer>
        <el-button @click="assignVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveAssign">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="contractVisible" :title="contractForm.contractId ? '编辑合同' : '新增合同'" width="520px">
      <el-form label-position="top">
        <el-form-item label="柜机">
          <el-select
            v-model="contractForm.deviceId"
            filterable
            placeholder="选择柜机"
            style="width: 100%"
            :disabled="!!contractForm.contractId"
          >
            <el-option
              v-for="d in deviceOptions"
              :key="d.deviceId"
              :label="d.deviceName || d.deviceId"
              :value="d.deviceId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="场地名称">
          <el-input v-model="contractForm.siteName" />
        </el-form-item>
        <el-form-item label="地址">
          <el-input v-model="contractForm.address" />
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="场地主">
            <el-input v-model="contractForm.landlordName" />
          </el-form-item>
          <el-form-item label="联系电话">
            <el-input v-model="contractForm.landlordPhone" />
          </el-form-item>
        </div>
        <div class="form-grid">
          <el-form-item label="合同开始">
            <el-date-picker v-model="contractForm.startDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
          </el-form-item>
          <el-form-item label="合同到期">
            <el-date-picker v-model="contractForm.endDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
          </el-form-item>
        </div>
        <el-form-item label="月费(元)">
          <el-input-number v-model="contractForm.monthlyFeeYuan" :min="0" :step="10" style="width: 100%" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="contractForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="contractVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveContract">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import type { OrgNodeDto, SiteContractDto } from '@aicabinet/shared-types';

const loading = ref(false);
const saving = ref(false);
const tab = ref('org');
const orgTree = ref<OrgNodeDto[]>([]);
const contracts = ref<SiteContractDto[]>([]);
const deviceOptions = ref<{ deviceId: string; deviceName?: string }[]>([]);
const nodeVisible = ref(false);
const nodeForm = ref<{ nodeId: number | null; name: string; nodeType: string; parentId: number | null }>({
  nodeId: null,
  name: '',
  nodeType: 'BRANCH',
  parentId: null
});
const assignVisible = ref(false);
const assignNode = ref<OrgNodeDto | null>(null);
const assignDeviceIds = ref<string[]>([]);
const contractVisible = ref(false);
const contractForm = ref<{
  contractId: number | null;
  deviceId: string;
  siteName: string;
  address: string;
  landlordName: string;
  landlordPhone: string;
  startDate: string;
  endDate: string;
  monthlyFeeYuan: number;
  remark: string;
}>({
  contractId: null,
  deviceId: '',
  siteName: '',
  address: '',
  landlordName: '',
  landlordPhone: '',
  startDate: '',
  endDate: '',
  monthlyFeeYuan: 0,
  remark: ''
});

onMounted(async () => {
  await Promise.all([loadAll(), loadDevices()]);
});

async function loadAll() {
  loading.value = true;
  try {
    const [tree, list] = await Promise.all([
      api.request<OrgNodeDto[]>('/api/v2/ops/admin/org/tree', 'GET'),
      api.request<SiteContractDto[]>('/api/v2/ops/admin/site-contracts', 'GET')
    ]);
    orgTree.value = tree || [];
    contracts.value = list || [];
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

async function loadDevices() {
  try {
    deviceOptions.value =
      (await api.request<{ deviceId: string; deviceName?: string }[]>(
        '/api/v2/ops/admin/devices/ref',
        'GET'
      )) || [];
  } catch {
    deviceOptions.value = [];
  }
}

function openNode(parent: OrgNodeDto | null) {
  nodeForm.value = {
    nodeId: null,
    name: '',
    nodeType: 'BRANCH',
    parentId: parent?.nodeId ?? null
  };
  nodeVisible.value = true;
}

async function saveNode() {
  if (!nodeForm.value.name.trim()) {
    ElMessage.warning('请填写组织名称');
    return;
  }
  saving.value = true;
  try {
    await api.request('/api/v2/ops/admin/org/nodes', 'PUT', {
      nodeId: nodeForm.value.nodeId,
      parentId: nodeForm.value.parentId,
      name: nodeForm.value.name.trim(),
      nodeType: nodeForm.value.nodeType,
      sortOrder: 0
    });
    ElMessage.success('已保存');
    nodeVisible.value = false;
    await loadAll();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

async function toggleNode(node: OrgNodeDto) {
  try {
    await api.request(`/api/v2/ops/admin/org/nodes/${node.nodeId}/toggle?enabled=${!node.enabled}`, 'POST');
    await loadAll();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败');
  }
}

function openAssign(node: OrgNodeDto) {
  assignNode.value = node;
  assignDeviceIds.value = [...node.deviceIds];
  assignVisible.value = true;
}

async function saveAssign() {
  if (!assignNode.value) return;
  saving.value = true;
  try {
    await api.request(`/api/v2/ops/admin/org/nodes/${assignNode.value.nodeId}/devices`, 'PUT', {
      deviceIds: assignDeviceIds.value
    });
    ElMessage.success('设备归属已更新');
    assignVisible.value = false;
    await loadAll();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

function openContract(row: SiteContractDto | null) {
  contractForm.value = {
    contractId: row?.contractId ?? null,
    deviceId: row?.deviceId ?? '',
    siteName: row?.siteName ?? '',
    address: row?.address ?? '',
    landlordName: row?.landlordName ?? '',
    landlordPhone: row?.landlordPhone ?? '',
    startDate: row?.startDate ?? '',
    endDate: row?.endDate ?? '',
    monthlyFeeYuan: (row?.monthlyFeeCents ?? 0) / 100,
    remark: row?.remark ?? ''
  };
  contractVisible.value = true;
}

async function saveContract() {
  if (!contractForm.value.deviceId || !contractForm.value.siteName.trim()) {
    ElMessage.warning('请选择柜机并填写场地名称');
    return;
  }
  saving.value = true;
  try {
    await api.request(
      `/api/v2/ops/admin/site-contracts/${encodeURIComponent(contractForm.value.deviceId)}`,
      'PUT',
      {
        siteName: contractForm.value.siteName.trim(),
        address: contractForm.value.address,
        landlordName: contractForm.value.landlordName,
        landlordPhone: contractForm.value.landlordPhone,
        startDate: contractForm.value.startDate || null,
        endDate: contractForm.value.endDate || null,
        monthlyFeeCents: Math.round(contractForm.value.monthlyFeeYuan * 100),
        remark: contractForm.value.remark
      }
    );
    ElMessage.success('合同已保存');
    contractVisible.value = false;
    await loadAll();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

function contractStatusLabel(s: string) {
  return ({ ACTIVE: '有效', EXPIRING: '临期', EXPIRED: '已到期' } as Record<string, string>)[s] || s;
}

function contractStatusType(s: string) {
  return ({ ACTIVE: 'success', EXPIRING: 'warning', EXPIRED: 'danger' } as Record<string, string>)[s] || 'info';
}
</script>

<style scoped>
.org-toolbar {
  margin-bottom: 12px;
}
.tree-node {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 1;
  min-width: 0;
}
.tree-name {
  font-weight: 600;
}
.tree-actions {
  display: flex;
  gap: 4px;
  margin-left: 8px;
}
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 12px;
}
</style>
