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
            v-if="contractHasSelection"
            v-hasPermi="['ops:org:edit']"
            type="danger"
            :loading="contractBatchLoading"
            @click="batchDeleteContracts"
          >
            批量删除
          </el-button>
          <el-button @click="onExportContracts">{{ contractExportLabel }}</el-button>
          <el-button
            v-hasPermi="['ops:org:edit']"
            size="small"
            type="primary"
            @click="openContract(null)"
          >
            新增合同
          </el-button>
        </div>
        <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="searchContracts">
          <el-form-item label="关键词">
            <el-input
              v-model="contractKeyword"
              clearable
              placeholder="柜机 / 设备ID / 场地 / 场地主"
              style="width: 240px"
              @keyup.enter="searchContracts"
              @clear="searchContracts"
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="searchContracts">查询</el-button>
            <el-button @click="resetContracts">重置</el-button>
          </el-form-item>
        </el-form>
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <el-table
              ref="contractTableRef"
              v-loading="loading"
              :data="displayContracts"
              stripe
              border
              row-key="contractId"
              @selection-change="onContractSelectionChange"
            >
              <el-table-column type="selection" width="48" align="center" reserve-selection />
              <el-table-column
                prop="deviceName"
                label="柜机"
                min-width="140"
                show-overflow-tooltip
              />
              <el-table-column
                prop="deviceId"
                label="设备ID"
                min-width="110"
                show-overflow-tooltip
              />
              <el-table-column prop="siteName" label="场地" min-width="140" show-overflow-tooltip />
              <el-table-column prop="address" label="地址" min-width="150" show-overflow-tooltip />
              <el-table-column prop="landlordName" label="场地主" width="100" />
              <el-table-column label="联系电话" width="120" align="center">
                <template #default="{ row }">{{ row.landlordPhone || '暂无' }}</template>
              </el-table-column>
              <el-table-column label="月费" width="110" align="center">
                <template #default="{ row }"
                  >¥{{ (row.monthlyFeeCents / 100).toFixed(2) }}</template
                >
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
              <el-table-column label="操作" width="300" align="center" fixed="right">
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
                    @click="openGenerateBill(row)"
                  >
                    出账
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
          </div>
        </div>
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

      <el-tab-pane label="费用账单" name="bills">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="租金按合同月费×分账出账；流量费按柜机「流量费(分/月)」出账。标记已付仅改台账，不自动打款/扣款。账期留空时按系统配置偏移推算。"
          style="margin-bottom: 12px"
        />
        <div class="org-toolbar">
          <el-radio-group v-model="feeBillKind" size="small" @change="onFeeBillKindChange">
            <el-radio-button value="SITE_RENT">场地租金</el-radio-button>
            <el-radio-button value="DATA_FEE">流量费</el-radio-button>
          </el-radio-group>
          <el-date-picker
            v-model="billMonthFilter"
            type="month"
            value-format="YYYY-MM"
            placeholder="账期（可空=配置默认）"
            clearable
            style="width: 180px"
            @change="loadBills"
          />
          <el-select
            v-model="billStatusFilter"
            clearable
            placeholder="状态"
            style="width: 120px"
            @change="loadBills"
          >
            <el-option label="待付" value="UNPAID" />
            <el-option label="已付" value="PAID" />
            <el-option label="已作废" value="VOID" />
          </el-select>
          <el-button type="primary" :loading="loading" @click="loadBills">查询</el-button>
          <el-button v-hasPermi="['ops:org:edit']" :loading="saving" @click="openGenerateBill(null)">
            批量出账
          </el-button>
        </div>
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <el-table
              v-if="feeBillKind === 'SITE_RENT'"
              v-loading="loading"
              :data="rentBills"
              stripe
              border
            >
              <el-table-column prop="billMonth" label="账期" width="90" align="center" />
              <el-table-column prop="siteName" label="场地" min-width="120" show-overflow-tooltip />
              <el-table-column
                prop="deviceId"
                label="设备ID"
                min-width="100"
                show-overflow-tooltip
              />
              <el-table-column label="收款方" width="100" align="center">
                <template #default="{ row }">{{ rentPartyLabel(row.partyType) }}</template>
              </el-table-column>
              <el-table-column label="对方ID" width="100" show-overflow-tooltip>
                <template #default="{ row }">{{ row.partyId || '—' }}</template>
              </el-table-column>
              <el-table-column label="份额" width="80" align="center">
                <template #default="{ row }">{{ (row.shareBps / 100).toFixed(2) }}%</template>
              </el-table-column>
              <el-table-column label="月费基数" width="100" align="center">
                <template #default="{ row }"
                  >¥{{ (row.baseFeeCents / 100).toFixed(2) }}</template
                >
              </el-table-column>
              <el-table-column label="应付" width="100" align="center">
                <template #default="{ row }">¥{{ (row.amountCents / 100).toFixed(2) }}</template>
              </el-table-column>
              <el-table-column label="状态" width="90" align="center">
                <template #default="{ row }">
                  <el-tag size="small" :type="billStatusType(row.status)">
                    {{ billStatusLabel(row.status) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column
                v-if="showRentBillActionColumn"
                label="操作"
                width="160"
                align="center"
                fixed="right"
              >
                <template #default="{ row }">
                  <el-button
                    v-if="row.status === 'UNPAID'"
                    v-hasPermi="['ops:org:edit']"
                    size="small"
                    type="success"
                    @click="markBillPaid(row)"
                  >
                    标记已付
                  </el-button>
                  <el-button
                    v-if="row.status === 'UNPAID'"
                    v-hasPermi="['ops:org:edit']"
                    size="small"
                    type="danger"
                    link
                    @click="voidBill(row)"
                  >
                    作废
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-table v-else v-loading="loading" :data="dataFeeBills" stripe border>
              <el-table-column prop="billMonth" label="账期" width="90" align="center" />
              <el-table-column
                prop="deviceName"
                label="柜机"
                min-width="120"
                show-overflow-tooltip
              />
              <el-table-column
                prop="deviceId"
                label="设备ID"
                min-width="110"
                show-overflow-tooltip
              />
              <el-table-column label="商户" width="120" show-overflow-tooltip>
                <template #default="{ row }">{{ row.merchantId || '—' }}</template>
              </el-table-column>
              <el-table-column label="应付" width="110" align="center">
                <template #default="{ row }">¥{{ (row.amountCents / 100).toFixed(2) }}</template>
              </el-table-column>
              <el-table-column label="状态" width="90" align="center">
                <template #default="{ row }">
                  <el-tag size="small" :type="billStatusType(row.status)">
                    {{ dataFeeBillStatusLabel(row.status) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column
                v-if="showDataFeeBillActionColumn"
                label="操作"
                width="160"
                align="center"
                fixed="right"
              >
                <template #default="{ row }">
                  <el-button
                    v-if="row.status === 'UNPAID'"
                    v-hasPermi="['ops:org:edit']"
                    size="small"
                    type="success"
                    @click="markDataFeePaid(row)"
                  >
                    标记已付
                  </el-button>
                  <el-button
                    v-if="row.status === 'UNPAID'"
                    v-hasPermi="['ops:org:edit']"
                    size="small"
                    type="danger"
                    link
                    @click="voidDataFeeBill(row)"
                  >
                    作废
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>
        <PagePager
          :hydrated="billsHydrated"
          v-model:current-page="billPage"
          v-model:page-size="billSize"
          :total="billTotal"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="loadBills"
          @size-change="onBillSizeChange"
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
          <div class="field-hint">用于「租金账单」出账；不会自动打款</div>
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
      width="720px"
      destroy-on-close
      class="rent-split-dialog"
    >
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="出账时按各方份额拆分合同月费，并可叠加固定金额。份额合计须为 100%。标记已付不会自动打款。"
        style="margin-bottom: 12px"
      />
      <div class="rent-sum" :class="{ 'is-ok': rentShareSumOk, 'is-bad': !rentShareSumOk }">
        份额合计 <strong>{{ rentShareSumPct.toFixed(2) }}%</strong>
        <span v-if="rentShareSumOk">（已满 100%，可保存）</span>
        <span v-else>（还差 {{ (100 - rentShareSumPct).toFixed(2) }}%，或超出请调低）</span>
      </div>
      <div v-for="(r, idx) in rentRules" :key="idx" class="rent-card">
        <div class="rent-card__head">
          <span class="rent-card__title">第 {{ idx + 1 }} 方</span>
          <el-button link type="danger" @click="rentRules.splice(idx, 1)">删除</el-button>
        </div>
        <div class="rent-card__grid">
          <div class="rent-field">
            <label>角色</label>
            <el-select v-model="r.partyType" style="width: 100%">
              <el-option label="场地主" value="LANDLORD" />
              <el-option label="平台" value="PLATFORM" />
              <el-option label="商户" value="MERCHANT" />
              <el-option label="加盟" value="FRANCHISE" />
              <el-option label="其他" value="OTHER" />
            </el-select>
          </div>
          <div class="rent-field">
            <label>对方 ID（可选）</label>
            <el-input v-model="r.partyId" clearable placeholder="商户号 / 账号标识，平台可空" />
          </div>
          <div class="rent-field">
            <label>份额 %</label>
            <el-input-number
              v-model="r.sharePct"
              :min="0"
              :max="100"
              :step="1"
              :precision="2"
              controls-position="right"
              style="width: 100%"
            />
          </div>
          <div class="rent-field">
            <label>固定金额（元/期）</label>
            <el-input-number
              v-model="r.fixedYuan"
              :min="0"
              :step="1"
              :precision="2"
              controls-position="right"
              style="width: 100%"
            />
          </div>
          <div class="rent-field">
            <label>生效起</label>
            <el-date-picker
              v-model="r.effectiveFrom"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="不限"
              style="width: 100%"
            />
          </div>
          <div class="rent-field">
            <label>生效止</label>
            <el-date-picker
              v-model="r.effectiveTo"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="不限"
              style="width: 100%"
            />
          </div>
          <div class="rent-field">
            <label>状态</label>
            <el-select v-model="r.status" style="width: 100%">
              <el-option label="生效" value="ACTIVE" />
              <el-option label="停用" value="INACTIVE" />
            </el-select>
          </div>
        </div>
      </div>
      <el-button size="small" @click="addRentRule">加一方</el-button>
      <template #footer>
        <el-button @click="rentSplitVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!rentShareSumOk" @click="saveRentSplit"
          >保存分账</el-button
        >
      </template>
    </el-dialog>

    <el-dialog
      v-model="generateVisible"
      :title="generateDialogTitle"
      width="420px"
    >
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        :title="generateDialogHint"
        style="margin-bottom: 12px"
      />
      <el-form label-position="top">
        <el-form-item label="账期（可留空，按系统配置默认账期）">
          <el-date-picker
            v-model="generateMonth"
            type="month"
            value-format="YYYY-MM"
            placeholder="默认账期见服务配置"
            clearable
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="generateVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitGenerateBills">生成账单</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import { yuanToCents } from '@/utils/display';
import PagePager from '@/components/PagePager.vue';
import { useAdminListTable } from '@/composables/useAdminListTable';
import { useListCsv } from '@/composables/useListCsv';
import { useAuthStore } from '@/stores/auth';
import type {
  DeviceDataFeeBillDto,
  OrgNodeDto,
  SiteContractDto,
  SiteRentBillDto
} from '@aicabinet/shared-types';
import { displayLabel } from '@aicabinet/shared-dict';

const FEE_KIND_RENT = 'SITE_RENT';
const FEE_KIND_DATA = 'DATA_FEE';

const auth = useAuthStore();
const canEditOrg = computed(() => auth.hasPerm('ops:org:edit'));
const loading = ref(false);
const saving = ref(false);
const tab = ref('org');
const orgTree = ref<OrgNodeDto[]>([]);
const contracts = ref<SiteContractDto[]>([]);
const contractBatchLoading = ref(false);
const {
  tableRef: contractTableRef,
  keyword: contractKeyword,
  hasSelection: contractHasSelection,
  onSelectionChange: onContractSelectionChange,
  pickSelected: pickContracts,
  exportButtonLabel: contractExportLabel,
  clearSelection: clearContractSelection,
  filterByKeyword: filterContracts,
  resetKeyword: resetContractKeyword
} = useAdminListTable<SiteContractDto>((r) => r.contractId);

const displayContracts = computed(() =>
  filterContracts(contracts.value, (row, kw) => {
    return (
      String(row.deviceName || '')
        .toLowerCase()
        .includes(kw) ||
      String(row.deviceId || '')
        .toLowerCase()
        .includes(kw) ||
      String(row.siteName || '')
        .toLowerCase()
        .includes(kw) ||
      String(row.landlordName || '')
        .toLowerCase()
        .includes(kw)
    );
  })
);

const { onExport: onExportContracts } = useListCsv({
  filePrefix: '场地合同',
  headers: ['柜机', '设备ID', '场地', '地址', '场地主', '月费(元)', '起租', '到期', '状态'],
  toRows: () =>
    pickContracts(displayContracts.value).map((r) => [
      r.deviceName || '',
      r.deviceId,
      r.siteName,
      r.address || '',
      r.landlordName || '',
      (r.monthlyFeeCents / 100).toFixed(2),
      r.startDate || '',
      r.endDate || '',
      contractStatusLabel(r.status)
    ])
});
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
const generateVisible = ref(false);
const generateContractId = ref<number | null>(null);
const generateSiteName = ref('');
const generateMonth = ref('');
const feeBillKind = ref<'SITE_RENT' | 'DATA_FEE'>(FEE_KIND_RENT);
const rentBills = ref<SiteRentBillDto[]>([]);
const dataFeeBills = ref<DeviceDataFeeBillDto[]>([]);

/** 当前页无未付账单或无编辑权限时隐藏操作列 */
const showRentBillActionColumn = computed(
  () => canEditOrg.value && rentBills.value.some((row) => row.status === 'UNPAID')
);
const showDataFeeBillActionColumn = computed(
  () => canEditOrg.value && dataFeeBills.value.some((row) => row.status === 'UNPAID')
);
const billsHydrated = ref(false);
const billPage = ref(1);
const billSize = ref(20);
const billTotal = ref(0);
const billMonthFilter = ref('');
const billStatusFilter = ref('');

const generateDialogTitle = computed(() => {
  if (feeBillKind.value === FEE_KIND_DATA) {
    return '批量出账 · 流量费';
  }
  return generateContractId.value ? `出账 · ${generateSiteName.value}` : '批量出账 · 场地租金';
});
const generateDialogHint = computed(() =>
  feeBillKind.value === FEE_KIND_DATA
    ? '将对已配置流量费(>0)的柜机生成应付台账。同柜机同账期不可重复。不会自动扣款。'
    : '将按月费 × 分账规则生成应付台账。同一合同同一账期不可重复出账。不会自动打款。'
);
type RentRuleForm = {
  partyType: string;
  partyId: string;
  /** 展示用百分比 0–100；保存时换算万分比 shareBps */
  sharePct: number;
  /** 展示用元；保存时换算分 */
  fixedYuan: number;
  effectiveFrom: string;
  effectiveTo: string;
  status: string;
};
const rentRules = ref<RentRuleForm[]>([]);
const rentShareSumPct = computed(() =>
  rentRules.value.reduce((s, r) => s + (Number(r.sharePct) || 0), 0)
);
const rentShareSumOk = computed(() => Math.abs(rentShareSumPct.value - 100) < 0.005);

function emptyRentRule(partial?: Partial<RentRuleForm>): RentRuleForm {
  return {
    partyType: 'LANDLORD',
    partyId: '',
    sharePct: 0,
    fixedYuan: 0,
    effectiveFrom: '',
    effectiveTo: '',
    status: 'ACTIVE',
    ...partial
  };
}

function addRentRule() {
  rentRules.value.push(emptyRentRule());
}
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
  // 默认不写死账期：筛选可空，出账弹窗也留空 → 后端按 fee-bill 配置推算
  billMonthFilter.value = '';
  generateMonth.value = '';
  await Promise.all([loadAll(), loadDevices()]);
});

watch(tab, (name) => {
  if (name === 'bills') {
    void loadBills();
  }
});

async function loadAll() {
  loading.value = true;
  try {
    orgTree.value = (await api.request<OrgNodeDto[]>('/api/v2/ops/admin/org/tree', 'GET')) || [];
    await loadContracts();
    if (tab.value === 'bills') {
      await loadBills();
    }
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
    clearContractSelection();
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

function searchContracts() {
  contractPage.value = 1;
  loadContracts();
}

function resetContracts() {
  resetContractKeyword();
  contractPage.value = 1;
  loadContracts();
}

async function batchDeleteContracts() {
  const targets = pickContracts(displayContracts.value);
  if (!targets.length) {
    ElMessage.warning('请先勾选合同');
    return;
  }
  try {
    await ElMessageBox.confirm(`确认删除选中的 ${targets.length} 份场地合同？`, '批量删除', {
      type: 'warning'
    });
  } catch {
    return;
  }
  contractBatchLoading.value = true;
  const results = await Promise.allSettled(
    targets.map((row) =>
      api.request(`/api/v2/ops/admin/site-contracts/${row.contractId}`, 'DELETE')
    )
  );
  contractBatchLoading.value = false;
  const ok = results.filter((r) => r.status === 'fulfilled').length;
  ElMessage.success(`批量删除完成：成功 ${ok}，失败 ${targets.length - ok}`);
  await loadAll();
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
        monthlyFeeCents: yuanToCents(contractForm.value.monthlyFeeYuan) ?? 0,
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
    rentRules.value = (rules || []).map((r) =>
      emptyRentRule({
        partyType: r.partyType,
        partyId: r.partyId || '',
        sharePct: Number(r.shareBps || 0) / 100,
        fixedYuan: Number(r.fixedCents || 0) / 100,
        effectiveFrom: r.effectiveFrom || '',
        effectiveTo: r.effectiveTo || '',
        status: r.status || 'ACTIVE'
      })
    );
    if (!rentRules.value.length) {
      rentRules.value = [
        emptyRentRule({ partyType: 'LANDLORD', sharePct: 70 }),
        emptyRentRule({ partyType: 'PLATFORM', sharePct: 30 })
      ];
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载分账失败');
  }
}

async function saveRentSplit() {
  if (rentSplitContractId.value == null) return;
  if (!rentShareSumOk.value) {
    ElMessage.warning(`份额合计 ${rentShareSumPct.value.toFixed(2)}%，须等于 100%`);
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
          shareBps: Math.round((Number(r.sharePct) || 0) * 100),
          fixedCents: yuanToCents(r.fixedYuan) ?? 0,
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

function rentPartyLabel(s: string) {
  return displayLabel('site_rent_party_type', s, s || '未知');
}

function billStatusLabel(s: string) {
  return displayLabel('site_rent_bill_status', s, s || '未知');
}

function dataFeeBillStatusLabel(s: string) {
  return displayLabel('device_data_fee_bill_status', s, s || '未知');
}

function billStatusType(s: string) {
  return ({ UNPAID: 'warning', PAID: 'success', VOID: 'info' } as Record<string, string>)[s] || 'info';
}

function onFeeBillKindChange() {
  billPage.value = 1;
  void loadBills();
}

async function loadBills() {
  loading.value = true;
  try {
    // 账单接口约定 page 从 1 起（FeeBillMonthResolver.clampPage）；合约/事件等为 0 起，勿混用
    const q = new URLSearchParams({
      page: String(billPage.value),
      size: String(billSize.value)
    });
    if (billMonthFilter.value) q.set('billMonth', billMonthFilter.value);
    if (billStatusFilter.value) q.set('status', billStatusFilter.value);
    if (feeBillKind.value === FEE_KIND_DATA) {
      const data = await api.request<{ items: DeviceDataFeeBillDto[]; total: number }>(
        `/api/v2/ops/admin/device-data-fee-bills?${q}`,
        'GET'
      );
      dataFeeBills.value = data?.items || [];
      rentBills.value = [];
      billTotal.value = Number(data?.total) || 0;
    } else {
      const data = await api.request<{ items: SiteRentBillDto[]; total: number }>(
        `/api/v2/ops/admin/site-rent-bills?${q}`,
        'GET'
      );
      rentBills.value = data?.items || [];
      dataFeeBills.value = [];
      billTotal.value = Number(data?.total) || 0;
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '账单加载失败');
  } finally {
    billsHydrated.value = true;
    loading.value = false;
  }
}

function onBillSizeChange() {
  billPage.value = 1;
  void loadBills();
}

function openGenerateBill(row: SiteContractDto | null) {
  if (row) {
    feeBillKind.value = FEE_KIND_RENT;
  }
  generateContractId.value = row?.contractId ?? null;
  generateSiteName.value = row?.siteName ?? '';
  generateVisible.value = true;
}

async function submitGenerateBills() {
  saving.value = true;
  try {
    const body = { billMonth: generateMonth.value || null };
    if (feeBillKind.value === FEE_KIND_DATA) {
      await api.request('/api/v2/ops/admin/device-data-fee-bills/generate', 'POST', body);
    } else if (generateContractId.value != null) {
      await api.request(
        `/api/v2/ops/admin/site-contracts/${generateContractId.value}/rent-bills/generate`,
        'POST',
        body
      );
    } else {
      await api.request('/api/v2/ops/admin/site-rent-bills/generate', 'POST', body);
    }
    ElMessage.success('账单已生成');
    generateVisible.value = false;
    if (generateMonth.value) {
      billMonthFilter.value = generateMonth.value;
    }
    tab.value = 'bills';
    await loadBills();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '出账失败');
  } finally {
    saving.value = false;
  }
}

async function markBillPaid(row: SiteRentBillDto) {
  try {
    await ElMessageBox.confirm(
      `确认将「${row.siteName} · ${row.billMonth}」标记为已付？仅改台账状态，不会打款。`,
      '标记已付',
      { type: 'warning' }
    );
  } catch {
    return;
  }
  try {
    await api.request(`/api/v2/ops/admin/site-rent-bills/${row.billId}/pay`, 'POST');
    ElMessage.success('已标记已付');
    await loadBills();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败');
  }
}

async function voidBill(row: SiteRentBillDto) {
  try {
    await ElMessageBox.confirm(`确认作废该账单？`, '作废账单', { type: 'warning' });
  } catch {
    return;
  }
  try {
    await api.request(`/api/v2/ops/admin/site-rent-bills/${row.billId}/void`, 'POST');
    ElMessage.success('已作废');
    await loadBills();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败');
  }
}

async function markDataFeePaid(row: DeviceDataFeeBillDto) {
  try {
    await ElMessageBox.confirm(
      `确认将「${row.deviceName || row.deviceId} · ${row.billMonth}」流量费标记已付？仅改台账，不会扣款。`,
      '标记已付',
      { type: 'warning' }
    );
  } catch {
    return;
  }
  try {
    await api.request(`/api/v2/ops/admin/device-data-fee-bills/${row.billId}/pay`, 'POST');
    ElMessage.success('已标记已付');
    await loadBills();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败');
  }
}

async function voidDataFeeBill(row: DeviceDataFeeBillDto) {
  try {
    await ElMessageBox.confirm(`确认作废该流量费账单？`, '作废账单', { type: 'warning' });
  } catch {
    return;
  }
  try {
    await api.request(`/api/v2/ops/admin/device-data-fee-bills/${row.billId}/void`, 'POST');
    ElMessage.success('已作废');
    await loadBills();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败');
  }
}
</script>

<style scoped>
.org-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
/* 组织树区域：随内容增高，避免整页白底空荡 */
:deep(.el-tree) {
  --el-tree-node-content-height: 40px;
  padding: 4px 0 8px;
  background: transparent;
}
:deep(.el-tree-node__content) {
  height: auto;
  min-height: 40px;
  padding: 4px 8px 4px 0;
  border-radius: 8px;
}
:deep(.el-tree-node__content:hover) {
  background: var(--el-fill-color-light);
}
.tree-node {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 10px;
  flex: 1;
  min-width: 0;
  padding: 2px 0;
}
.tree-name {
  font-weight: 600;
}
.tree-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-left: auto;
}
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 12px;
}
.rent-sum {
  margin-bottom: 12px;
  padding: 8px 12px;
  border-radius: 8px;
  font-size: 13px;
  background: var(--el-fill-color-light);
  color: var(--layout-muted, #64748b);
}
.rent-sum strong {
  font-variant-numeric: tabular-nums;
  color: var(--layout-text, #1e293b);
}
.rent-sum.is-ok {
  background: color-mix(in srgb, var(--el-color-success) 12%, var(--layout-card, #fff));
  color: var(--el-color-success);
}
.rent-sum.is-bad {
  background: color-mix(in srgb, var(--el-color-warning) 12%, var(--layout-card, #fff));
  color: var(--el-color-warning-dark-2, #b45309);
}
.rent-card {
  margin-bottom: 12px;
  padding: 12px 14px;
  border: 1px solid var(--layout-border, #e5e7eb);
  border-radius: 10px;
  background: var(--layout-card, #fff);
}
.rent-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.rent-card__title {
  font-weight: 600;
  font-size: 13px;
  color: var(--layout-text, #1e293b);
}
.rent-card__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 12px;
}
@media (min-width: 720px) {
  .rent-card__grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
.rent-field label {
  display: block;
  margin-bottom: 4px;
  font-size: 12px;
  color: var(--layout-muted, #64748b);
}
.rent-field :deep(.el-input-number) {
  width: 100%;
}
.field-hint {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.4;
  color: var(--layout-muted, #64748b);
}
</style>
