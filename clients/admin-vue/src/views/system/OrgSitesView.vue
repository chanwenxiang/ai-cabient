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
          <el-button
            v-hasPermi="['ops:org:edit']"
            size="small"
            type="primary"
            @click="openNode(null)"
          >
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
                <el-button
                  v-hasPermi="['ops:org:edit']"
                  size="small"
                  link
                  @click.stop="openEditNode(data)"
                >
                  编辑
                </el-button>
                <el-button
                  v-hasPermi="['ops:org:edit']"
                  size="small"
                  link
                  @click.stop="openNode(data)"
                >
                  新增子级
                </el-button>
                <el-button
                  v-hasPermi="['ops:org:edit']"
                  size="small"
                  link
                  @click.stop="openAssign(data)"
                >
                  分配设备
                </el-button>
                <el-button
                  v-hasPermi="['ops:org:edit']"
                  size="small"
                  link
                  @click.stop="toggleNode(data)"
                >
                  {{ data.enabled ? '停用' : '启用' }}
                </el-button>
                <el-button
                  v-hasPermi="['ops:org:edit']"
                  size="small"
                  link
                  type="danger"
                  @click.stop="removeNode(data)"
                >
                  删除
                </el-button>
              </div>
            </div>
          </template>
        </el-tree>
      </el-tab-pane>

      <el-tab-pane label="场地合同" name="contracts">
        <div class="org-toolbar">
          <el-button
            v-hasPermi="['ops:org:edit']"
            size="small"
            type="primary"
            @click="openContract(null)"
          >
            新增合同
          </el-button>
        </div>
        <el-table v-loading="loading" :data="contracts" stripe border>
          <el-table-column prop="deviceName" label="柜机" min-width="140" show-overflow-tooltip />
          <el-table-column prop="deviceId" label="设备ID" min-width="110" show-overflow-tooltip />
          <el-table-column prop="siteName" label="场地" min-width="140" show-overflow-tooltip />
          <el-table-column prop="address" label="地址" min-width="150" show-overflow-tooltip />
          <el-table-column prop="landlordName" label="场地主" width="100" />
          <el-table-column label="联系电话" width="120" align="center">
            <template #default="{ row }">{{ row.landlordPhone || '暂无' }}</template>
          </el-table-column>
          <el-table-column label="月费" width="100" align="center">
            <template #default="{ row }">¥{{ (row.monthlyFeeCents / 100).toFixed(2) }}</template>
          </el-table-column>
          <el-table-column label="起租" width="110" align="center">
            <template #default="{ row }">{{ row.startDate || '暂无' }}</template>
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
          <el-table-column label="备注" min-width="100" show-overflow-tooltip>
            <template #default="{ row }">{{ row.remark || '暂无' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="220" align="center">
            <template #default="{ row }">
              <el-button v-hasPermi="['ops:org:edit']" size="small" @click="openContract(row)">
                编辑
              </el-button>
              <el-button v-hasPermi="['ops:org:edit']" size="small" @click="openRentSplit(row)">
                租金分账
              </el-button>
              <el-button
                v-hasPermi="['ops:org:edit']"
                size="small"
                type="danger"
                @click="removeContract(row)"
              >
                删除
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <PagePager
          :hydrated="contractsHydrated"
          v-model:current-page="contractPage"
          v-model:page-size="contractSize"
          :total="contractTotal"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="loadContracts"
          @size-change="onContractSizeChange"
        />
      </el-tab-pane>
    </el-tabs>

    <el-dialog
      v-model="nodeVisible"
      :title="nodeForm.nodeId ? '编辑组织' : '新增组织'"
      width="420px"
    >
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

    <el-dialog
      v-model="assignVisible"
      :title="`分配设备到 ${assignNode?.name || ''}`"
      width="480px"
    >
      <el-select
        v-model="assignDeviceIds"
        multiple
        filterable
        placeholder="选择柜机"
        style="width: 100%"
      >
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

    <el-dialog
      v-model="contractVisible"
      :title="contractForm.contractId ? '编辑合同' : '新增合同'"
      width="520px"
    >
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
            <el-date-picker
              v-model="contractForm.startDate"
              type="date"
              value-format="YYYY-MM-DD"
              style="width: 100%"
            />
          </el-form-item>
          <el-form-item label="合同到期">
            <el-date-picker
              v-model="contractForm.endDate"
              type="date"
              value-format="YYYY-MM-DD"
              style="width: 100%"
            />
          </el-form-item>
        </div>
        <el-form-item label="月费(元)">
          <el-input-number
            v-model="contractForm.monthlyFeeYuan"
            :min="0"
            :step="10"
            style="width: 100%"
          />
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

    <el-dialog
      v-model="rentSplitVisible"
      :title="`租金分账 · ${rentSplitSiteName}`"
      width="640px"
      destroy-on-close
    >
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="份额合计须为 100%（按万分比填写，合计 10000）；可填生效区间与固定金额（分）"
        style="margin-bottom: 12px"
      />
      <div v-for="(r, idx) in rentRules" :key="idx" class="rent-row">
        <el-select v-model="r.partyType" style="width: 110px">
          <el-option label="场地主" value="LANDLORD" />
          <el-option label="平台" value="PLATFORM" />
          <el-option label="商户" value="MERCHANT" />
          <el-option label="加盟" value="FRANCHISE" />
          <el-option label="其他" value="OTHER" />
        </el-select>
        <el-input v-model="r.partyId" placeholder="对方 ID" style="width: 110px" />
        <el-input-number v-model="r.shareBps" :min="0" :max="10000" controls-position="right" />
        <el-input-number
          v-model="r.fixedCents"
          :min="0"
          :step="100"
          controls-position="right"
          placeholder="固定金额(分)"
        />
        <el-date-picker
          v-model="r.effectiveFrom"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="生效起"
          style="width: 130px"
        />
        <el-date-picker
          v-model="r.effectiveTo"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="生效止"
          style="width: 130px"
        />
        <el-select v-model="r.status" style="width: 90px">
          <el-option label="生效" value="ACTIVE" />
          <el-option label="停用" value="INACTIVE" />
        </el-select>
        <el-button link type="danger" @click="rentRules.splice(idx, 1)">删</el-button>
      </div>
      <el-button
        size="small"
        @click="
          rentRules.push({
            partyType: 'LANDLORD',
            partyId: '',
            shareBps: 0,
            fixedCents: 0,
            effectiveFrom: '',
            effectiveTo: '',
            status: 'ACTIVE'
          })
        "
        >加一行</el-button
      >
      <template #footer>
        <el-button @click="rentSplitVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveRentSplit">保存分账</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import PagePager from '@/components/PagePager.vue';
import type { OrgNodeDto, SiteContractDto } from '@aicabinet/shared-types';
import { dictLabel, displayLabel } from '@aicabinet/shared-dict';

const loading = ref(false);
const saving = ref(false);
const tab = ref('org');
const orgTree = ref<OrgNodeDto[]>([]);
const contracts = ref<SiteContractDto[]>([]);
const contractsHydrated = ref(false);
const contractPage = ref(1);
const contractSize = ref(20);
const contractTotal = ref(0);
const deviceOptions = ref<{ deviceId: string; deviceName?: string }[]>([]);
const nodeVisible = ref(false);
const nodeForm = ref<{
  nodeId: number | null;
  name: string;
  nodeType: string;
  parentId: number | null;
}>({
  nodeId: null,
  name: '',
  nodeType: 'BRANCH',
  parentId: null
});
const assignVisible = ref(false);
const assignNode = ref<OrgNodeDto | null>(null);
const assignDeviceIds = ref<string[]>([]);
const contractVisible = ref(false);
const rentSplitVisible = ref(false);
const rentSplitContractId = ref<number | null>(null);
const rentSplitSiteName = ref('');
const rentRules = ref<
  {
    partyType: string;
    partyId: string;
    shareBps: number;
    fixedCents: number;
    effectiveFrom: string;
    effectiveTo: string;
    status: string;
  }[]
>([]);
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
    orgTree.value = (await api.request<OrgNodeDto[]>('/api/v2/ops/admin/org/tree', 'GET')) || [];
    await loadContracts();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

async function loadContracts() {
  loading.value = true;
  try {
    const q = new URLSearchParams({
      page: String(contractPage.value - 1),
      size: String(contractSize.value)
    });
    const data = await api.request<{ items: SiteContractDto[]; total: number }>(
      `/api/v2/ops/admin/site-contracts?${q}`,
      'GET'
    );
    contracts.value = data.items || [];
    contractTotal.value = Number(data.total) || 0;
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '合同加载失败');
  } finally {
    contractsHydrated.value = true;
    loading.value = false;
  }
}

function onContractSizeChange() {
  contractPage.value = 1;
  loadContracts();
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

function openEditNode(node: OrgNodeDto) {
  nodeForm.value = {
    nodeId: node.nodeId,
    name: node.name,
    nodeType: node.nodeType || 'BRANCH',
    parentId: node.parentId ?? null
  };
  nodeVisible.value = true;
}

async function removeNode(node: OrgNodeDto) {
  try {
    await ElMessageBox.confirm(
      `确认删除组织「${node.name}」？需无子节点且无绑定设备。`,
      '删除组织',
      {
        type: 'warning'
      }
    );
  } catch {
    return;
  }
  try {
    await api.request(`/api/v2/ops/admin/org/nodes/${node.nodeId}`, 'DELETE');
    ElMessage.success('已删除');
    await loadAll();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败');
  }
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
    await api.request(
      `/api/v2/ops/admin/org/nodes/${node.nodeId}/toggle?enabled=${!node.enabled}`,
      'POST'
    );
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

async function removeContract(row: SiteContractDto) {
  try {
    await ElMessageBox.confirm(`确认删除「${row.siteName}」场地合同？`, '删除合同', {
      type: 'warning'
    });
  } catch {
    return;
  }
  try {
    await api.request(`/api/v2/ops/admin/site-contracts/${row.contractId}`, 'DELETE');
    ElMessage.success('已删除');
    await loadAll();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败');
  }
}

async function openRentSplit(row: SiteContractDto) {
  rentSplitContractId.value = row.contractId;
  rentSplitSiteName.value = row.siteName;
  rentSplitVisible.value = true;
  try {
    const rules = await api.request<
      {
        partyType: string;
        partyId?: string;
        shareBps: number;
        fixedCents?: number;
        effectiveFrom?: string;
        effectiveTo?: string;
        status?: string;
      }[]
    >(`/api/v2/ops/admin/site-contracts/${row.contractId}/rent-split-rules`, 'GET');
    rentRules.value = (rules || []).map((r) => ({
      partyType: r.partyType,
      partyId: r.partyId || '',
      shareBps: r.shareBps,
      fixedCents: Number(r.fixedCents || 0),
      effectiveFrom: r.effectiveFrom || '',
      effectiveTo: r.effectiveTo || '',
      status: r.status || 'ACTIVE'
    }));
    if (!rentRules.value.length) {
      rentRules.value = [
        {
          partyType: 'LANDLORD',
          partyId: '',
          shareBps: 7000,
          fixedCents: 0,
          effectiveFrom: '',
          effectiveTo: '',
          status: 'ACTIVE'
        },
        {
          partyType: 'PLATFORM',
          partyId: '',
          shareBps: 3000,
          fixedCents: 0,
          effectiveFrom: '',
          effectiveTo: '',
          status: 'ACTIVE'
        }
      ];
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载分账失败');
  }
}

async function saveRentSplit() {
  if (rentSplitContractId.value == null) return;
  const sum = rentRules.value.reduce((s, r) => s + (Number(r.shareBps) || 0), 0);
  if (sum !== 10000) {
    ElMessage.warning(`份额合计 ${sum}，须等于 10000`);
    return;
  }
  saving.value = true;
  try {
    await api.request(
      `/api/v2/ops/admin/site-contracts/${rentSplitContractId.value}/rent-split-rules`,
      'PUT',
      {
        rules: rentRules.value.map((r) => ({
          partyType: r.partyType,
          partyId: r.partyId || null,
          shareBps: Number(r.shareBps) || 0,
          fixedCents: Number(r.fixedCents) || 0,
          status: r.status || 'ACTIVE',
          effectiveFrom: r.effectiveFrom || null,
          effectiveTo: r.effectiveTo || null
        }))
      }
    );
    ElMessage.success('租金分账已保存');
    rentSplitVisible.value = false;
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

function contractStatusLabel(s: string) {
  return displayLabel('site_contract_status', s, '未知');
}

function contractStatusType(s: string) {
  return (
    ({ ACTIVE: 'success', EXPIRING: 'warning', EXPIRED: 'danger' } as Record<string, string>)[s] ||
    'info'
  );
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
.rent-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}
</style>
