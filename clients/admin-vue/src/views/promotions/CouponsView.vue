<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">优惠券</span>
            <span class="hint">券定义与发券；面值 / 门槛等金额列居中</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button
            v-if="crud.selectedKeys.length && auth.hasPerm('ops:coupon:edit')"
            type="warning"
            @click="batchDisable"
          >
            批量停用 ({{ crud.selectedKeys.length }})
          </el-button>
          <el-button v-hasPermi="['ops:coupon:create']" type="primary" @click="openCreate"
            >新建优惠券</el-button
          >
          <el-button v-hasPermi="['ops:coupon:create']" @click="showIssue = true"
            >手动发券</el-button
          >
          <el-button v-hasPermi="['ops:coupon:create']" @click="openBatchIssue">批量发券</el-button>
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="关键词">
        <el-input
          v-model="keyword"
          clearable
          placeholder="名称 / ID"
          style="width: 180px"
          @keyup.enter="search"
          @clear="search"
        />
      </el-form-item>
      <el-form-item label="状态">
        <el-select
          v-model="statusFilter"
          clearable
          placeholder="全部"
          style="width: 120px"
          @change="search"
        >
          <el-option :label="displayLabel('enable_status', 'ACTIVE')" value="ACTIVE" />
          <el-option :label="displayLabel('enable_status', 'INACTIVE')" value="INACTIVE" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </el-form-item>
    </el-form>

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <CrudTable
          :table="crud"
          row-key="couponDefId"
          selectable
          :actions="rowActions"
          :action-width="100"
          actions-testid="coupon"
          empty-text="暂无优惠券"
          sort-field-label="券定义编号"
          :csv="csvOptions"
          @action="onAction"
        >
          <el-table-column prop="couponDefId" label="券定义编号" width="100" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-id">{{ row.couponDefId }}</span>
            </template>
          </el-table-column>
          <el-table-column label="优惠券" min-width="150" class-name="col-text">
            <template #default="{ row }">{{ row.couponName || '无' }}</template>
          </el-table-column>
          <el-table-column
            label="类型"
            width="100"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{
                displayLabel('coupon_type', row.couponType, '未知类型')
              }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="面值" width="96" align="center" class-name="col-money">
            <template #default="{ row }">
              <template v-if="row.couponType === 'PERCENT_OFF'">
                {{ row.discountPercent || 0 }}%
              </template>
              <template v-else>¥{{ yuan(row.denominationCents) }}</template>
            </template>
          </el-table-column>
          <el-table-column label="最低消费" width="100" align="center" class-name="col-money">
            <template #default="{ row }">¥{{ yuan(row.minSpendCents) }}</template>
          </el-table-column>
          <el-table-column
            label="有效期"
            width="88"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">{{ row.validityDays }}天</template>
          </el-table-column>
          <el-table-column label="绑定活动" width="100" class-name="col-text">
            <template #default="{ row }">
              <span v-if="row.activityId" class="cell-id">{{ row.activityId }}</span>
              <span v-else>未绑定</span>
            </template>
          </el-table-column>
          <el-table-column
            label="发行/总量"
            width="110"
            class-name="col-text"
            label-class-name="col-text"
          >
            <template #default="{ row }"
              >{{ row.issuedCount }}/{{ row.maxIssueCount || '不限' }}</template
            >
          </el-table-column>
          <el-table-column
            label="剩余"
            width="88"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <span v-if="!row.maxIssueCount">不限</span>
              <span v-else>{{
                Math.max(0, Number(row.maxIssueCount) - Number(row.issuedCount || 0))
              }}</span>
            </template>
          </el-table-column>
          <el-table-column label="说明" min-width="140" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-ellipsis" :title="row.description || ''">{{
                row.description || '暂无'
              }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="状态"
            width="88"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
                {{ displayLabel('enable_status', row.status, '未知状态') }}
              </el-tag>
            </template>
          </el-table-column>
        </CrudTable>
      </div>
    </div>

    <el-dialog
      v-model="showCreate"
      :title="editingId ? '编辑优惠券' : '新建优惠券'"
      class="dialog-wide"
      destroy-on-close
    >
      <el-form :model="createForm" label-width="auto">
        <div class="form-group-title">券面信息</div>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="名称" required>
              <el-input v-model="createForm.couponName" placeholder="如 新人首单立减 3 元" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="类型">
              <el-select v-model="createForm.couponType" style="width: 100%">
                <el-option
                  v-for="item in dictOptions('coupon_type')"
                  :key="item.value"
                  :label="item.label"
                  :value="item.value"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col v-if="createForm.couponType !== 'PERCENT_OFF'" :span="12">
            <el-form-item label="面值(元)" required>
              <el-input-number
                v-model="createForm.denominationYuan"
                :min="0.01"
                :step="0.5"
                :precision="2"
                controls-position="right"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col v-if="createForm.couponType === 'PERCENT_OFF'" :span="12">
            <el-form-item label="折扣百分比" required>
              <el-input-number
                v-model="createForm.discountPercent"
                :min="1"
                :max="99"
                controls-position="right"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="最低消费(元)">
              <el-input-number
                v-model="createForm.minSpendYuan"
                :min="0"
                :step="1"
                :precision="2"
                controls-position="right"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="有效天数" required>
              <el-input-number
                v-model="createForm.validityDays"
                :min="1"
                :max="365"
                controls-position="right"
                style="width: 100%"
              />
              <div class="field-hint">自领取之日起算，到期自动失效</div>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="发放总量">
              <el-input-number
                v-model="createForm.maxIssueCount"
                :min="0"
                controls-position="right"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="描述">
              <el-input
                v-model="createForm.description"
                type="textarea"
                :rows="2"
                placeholder="券的使用说明，展示给顾客"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="绑定活动">
              <el-select
                v-model="createForm.activityId"
                clearable
                filterable
                placeholder="可不选，绑定后发券从该活动预算扣"
                style="width: 100%"
              >
                <el-option
                  v-for="a in activityOptions"
                  :key="a.activityId"
                  :label="a.activityName"
                  :value="a.activityId"
                />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>

        <div class="form-group-title">使用范围</div>
        <!-- 🔴 V319 券可用范围：迁移前 device_scope 是死字段（配了不生效），
             V319 起由后端 CouponScopeValidator 真正参与判定。 -->
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="可用范围">
              <el-select v-model="createForm.scopeType" style="width: 100%">
                <el-option
                  v-for="o in SCOPE_TYPE_OPTIONS"
                  :key="o.value"
                  :label="o.label"
                  :value="o.value"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col v-if="createForm.scopeType === 'MERCHANT'" :span="12">
            <el-form-item label="适用商户" required>
              <el-input
                v-model="createForm.scopeMerchantId"
                placeholder="填商户编号，如 MCH-DEFAULT"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col v-if="createForm.scopeType === 'DEVICE'" :span="24">
            <el-form-item label="适用设备">
              <el-select
                v-model="createForm.scopeDeviceIds"
                multiple
                filterable
                allow-create
                default-first-option
                placeholder="留空 = 所有设备可用；输入设备编号后回车添加"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
        </el-row>
        <!-- ⚠️ 为什么不物化「商户/设备」下拉而用文本输入：
             那需要额外的联想端点与权限（ops:merchant:list / ops:device:list），
             而券的运营者未必有这两项权限 ⇒ 用文本输入更安全（越权看不到列表）。 -->
      </el-form>
      <template #footer>
        <el-button @click="showCreate = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onCreateSubmit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="showIssue" title="手动发券" destroy-on-close>
      <el-form label-width="auto">
        <el-form-item label="优惠券">
          <el-select v-model="issueForm.couponDefId" style="width: 100%">
            <el-option
              v-for="d in activeCoupons"
              :key="d.couponDefId"
              :label="d.couponName"
              :value="d.couponDefId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="用户编号">
          <el-input-number
            v-model="issueForm.userId"
            :min="1"
            controls-position="right"
            style="width: 100%"
          />
          <div class="field-hint">在「用户管理」可查到该编号</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showIssue = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onIssueSubmit">发放</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="batchVisible" title="批量发券" destroy-on-close>
      <el-form label-width="auto">
        <el-form-item label="优惠券">
          <el-select v-model="batchForm.couponDefId" style="width: 100%">
            <el-option
              v-for="d in activeCoupons"
              :key="d.couponDefId"
              :label="d.couponName"
              :value="d.couponDefId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="用户编号">
          <el-input
            v-model="batchForm.userIdsText"
            type="textarea"
            :rows="6"
            placeholder="每行一个用户编号，也可用逗号分隔"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="batchVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onBatchIssueSubmit">批量发放</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onActivated, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { SwitchButton, Ticket, EditPen } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { dictOptions, displayLabel } from '@aicabinet/shared-dict';
import { yuanText, yuanToCents } from '@/utils/display';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import CrudTable, { type CrudCsvOptions, type CrudRowAction } from '@/components/CrudTable.vue';
import { useCrudTable } from '@/composables/useCrudTable';
import { useAuthStore } from '@/stores/auth';
import { errorMessage } from '@/utils/error-message';
import type {
  OpenApiCouponDefinitionDto,
  OpenApiPromotionActivityDto
} from '@aicabinet/shared-types';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const saving = ref(false);
const keyword = ref('');
const statusFilter = ref('');
const activeCoupons = ref<OpenApiCouponDefinitionDto[]>([]);
const activityOptions = ref<OpenApiPromotionActivityDto[]>([]);
const showCreate = ref(false);
const editingId = ref<number | null>(null);

// 列表状态机统一交给 CrudTable：分页 / 排序 / 多选 / 竞态 / 空态 全部内建
const crud = useCrudTable<OpenApiCouponDefinitionDto>({
  rowKey: (r) => r.couponDefId ?? 0,
  // 首查前需先应用路由查询参数（applyRouteQuery），故关闭 autoLoad 由 onMounted 显式首查
  autoLoad: false,
  fetchPage: (params) => {
    const q = new URLSearchParams({ page: String(params.page), size: String(params.size) });
    if (keyword.value.trim()) q.set('q', keyword.value.trim());
    if (statusFilter.value) q.set('status', statusFilter.value);
    return api.request<{ items: OpenApiCouponDefinitionDto[]; total: number }>(
      AdminEndpoints.couponDefinitionsList(q),
      'GET'
    );
  },
  sort: { prop: 'couponDefId', mode: 'local' }
});

async function loadActiveCoupons() {
  try {
    activeCoupons.value =
      (
        await api.request<{ items: OpenApiCouponDefinitionDto[] }>(
          AdminEndpoints.couponDefinitionsActive,
          'GET'
        )
      ).items || [];
  } catch {
    activeCoupons.value = [];
  }
}

async function loadActivityOptions() {
  try {
    activityOptions.value =
      (
        await api.request<{ items: OpenApiPromotionActivityDto[] }>(
          AdminEndpoints.promotionsOptions,
          'GET'
        )
      ).items || [];
  } catch {
    activityOptions.value = [];
  }
}

const showIssue = ref(false);
const batchVisible = ref(false);
const batchForm = ref<{ couponDefId: number | null; userIdsText: string }>({
  couponDefId: null,
  userIdsText: ''
});

async function batchDisable() {
  const targets = crud.items.filter(
    (r) =>
      r.couponDefId != null && crud.selectedKeys.includes(r.couponDefId) && r.status === 'ACTIVE'
  );
  if (!targets.length) return ElMessage.warning('请勾选已启用的优惠券');
  try {
    await ElMessageBox.confirm(`确认停用选中的 ${targets.length} 张优惠券？`, '批量停用', {
      type: 'warning'
    });
    for (const row of targets) {
      await api.request(
        AdminEndpoints.couponDefinitionStatus(row.couponDefId ?? 0, 'INACTIVE'),
        'PUT'
      );
    }
    ElMessage.success(`已停用 ${targets.length} 张优惠券`);
    crud.clearSelection();
    await crud.load();
    await loadActiveCoupons();
  } catch (e: unknown) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(errorMessage(e, '批量停用失败'));
    }
  }
}

const createForm = ref<{
  couponName: string;
  couponType: string;
  denominationYuan: number;
  minSpendYuan: number;
  discountPercent: number | null;
  validityDays: number;
  maxIssueCount: number;
  description: string;
  activityId: number | null;
  // ---- V319 券可用范围 ----
  /** ALL=全平台 / DEVICE=指定柜机 / MERCHANT=指定商户 */
  scopeType: 'ALL' | 'DEVICE' | 'MERCHANT';
  /** MERCHANT 范围时选中的商户 ID */
  scopeMerchantId: string | null;
  /** DEVICE 范围时选中的柜机 ID 集合（空 = 不限制） */
  scopeDeviceIds: string[];
}>({
  couponName: '',
  couponType: 'AMOUNT_OFF',
  denominationYuan: 1,
  minSpendYuan: 0,
  discountPercent: null,
  validityDays: 30,
  maxIssueCount: 0,
  description: '',
  activityId: null,
  scopeType: 'ALL',
  scopeMerchantId: null,
  scopeDeviceIds: []
});

/**
 * V319 范围类型的中文标签。🔴 刻意不在模板里内联字符串 ——
 * 后端枚举是英文码，界面上暴露英文码会让运营以为要填代码。
 */
const SCOPE_TYPE_OPTIONS = [
  { value: 'ALL', label: '全平台通用' },
  { value: 'MERCHANT', label: '指定商户（该商户名下所有柜机）' },
  { value: 'DEVICE', label: '指定柜机' }
] as const;
const issueForm = ref<{ couponDefId: number | null; userId: number | null }>({
  couponDefId: null,
  userId: null
});

const typeCodeByLabel: Record<string, string> = Object.fromEntries(
  dictOptions('coupon_type').flatMap(
    (o) =>
      [
        [o.label, o.value],
        [o.value, o.value]
      ] as [string, string][]
  )
);
const CSV_HEADERS = [
  '名称',
  '类型',
  '面值(元)',
  '最低消费(元)',
  '折扣百分比',
  '有效天数',
  '总量限制',
  '描述',
  '状态'
];

// 导出/下载模板/导入并入 CrudTable 内建工具条（选中优先导出、文件命名走共享 csvFileName）
const csvOptions: CrudCsvOptions = {
  filePrefix: '优惠券',
  exportPerm: 'ops:coupon:export',
  importPerm: 'ops:coupon:import',
  headers: CSV_HEADERS,
  toRows: (rows) =>
    rows.map((row) => [
      row.couponName,
      displayLabel('coupon_type', row.couponType, '未知类型'),
      yuan(row.denominationCents || 0),
      yuan(row.minSpendCents || 0),
      row.discountPercent ?? '',
      row.validityDays,
      row.maxIssueCount || 0,
      row.description || '',
      displayLabel('enable_status', row.status, '未知状态')
    ]),
  templateSample: [
    '示例优惠券',
    '满减券',
    '5',
    '0',
    '90',
    '30',
    '100',
    '示例描述',
    displayLabel('enable_status', 'INACTIVE')
  ],
  onImportRows: async (rows) => {
    let ok = 0;
    for (const row of rows) {
      const name = row['名称'] || row.couponName;
      if (!name?.trim()) continue;
      const created = await api.request<OpenApiCouponDefinitionDto>(
        AdminEndpoints.couponDefinitions,
        'POST',
        {
          couponName: name.trim(),
          couponType: typeCodeByLabel[row['类型'] || row.couponType] || 'AMOUNT_OFF',
          denominationCents: yuanToCents(row['面值(元)'] || row.denominationYuan) ?? 0,
          minSpendCents: yuanToCents(row['最低消费(元)'] || row.minSpendYuan) ?? 0,
          discountPercent: Number(row['折扣百分比'] || row.discountPercent) || 90,
          validityDays: Number(row['有效天数'] || row.validityDays) || 30,
          maxIssueCount: Number(row['总量限制'] || row.maxIssueCount) || 0,
          description: row['描述'] || row.description || ''
        }
      );
      const statusRaw = (row['状态'] || row.status || '').trim();
      const wantsActive =
        statusRaw.toUpperCase() === 'ACTIVE' ||
        statusRaw === displayLabel('enable_status', 'ACTIVE');
      if (!wantsActive && created?.couponDefId) {
        await api.request(
          AdminEndpoints.couponDefinitionStatus(created.couponDefId, 'INACTIVE'),
          'PUT'
        );
      }
      ok++;
    }
    await crud.load();
    await loadActiveCoupons();
    return ok;
  }
};

function yuan(cents?: number | null) {
  // 审计批次4：金额展示收敛 display.ts.yuanText（单一格式：¥ 前缀 + 空值文案）
  return yuanText(cents, '¥0.00');
}

function rowActions(row: OpenApiCouponDefinitionDto): CrudRowAction[] {
  const acts: CrudRowAction[] = [];
  if (auth.hasPerm('ops:coupon:edit')) {
    acts.push({ key: 'edit', label: '编辑', icon: EditPen, type: 'primary' });
  }
  if (auth.hasPerm('ops:coupon:create') && row.status === 'ACTIVE') {
    acts.push({ key: 'issue', label: '发券', icon: Ticket, type: 'primary' });
  }
  if (auth.hasPerm('ops:coupon:edit')) {
    acts.push({
      key: 'toggle',
      label: displayLabel('enable_status', row.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'),
      icon: SwitchButton,
      type: row.status === 'ACTIVE' ? 'warning' : 'success'
    });
  }
  return acts;
}

function onAction({ key, row }: { key: string; row: OpenApiCouponDefinitionDto }) {
  if (key === 'edit') {
    openEdit(row);
  } else if (key === 'issue') {
    issueForm.value.couponDefId = row.couponDefId ?? null;
    showIssue.value = true;
  } else if (key === 'toggle') {
    void onToggleStatus(row);
  }
}

function openCreate() {
  editingId.value = null;
  createForm.value = {
    couponName: '',
    couponType: 'AMOUNT_OFF',
    denominationYuan: 1,
    minSpendYuan: 0,
    discountPercent: null,
    validityDays: 30,
    maxIssueCount: 0,
    description: '',
    activityId: null,
    scopeType: 'ALL',
    scopeMerchantId: null,
    scopeDeviceIds: []
  };
  void loadActivityOptions();
  showCreate.value = true;
}

function openEdit(row: OpenApiCouponDefinitionDto) {
  editingId.value = row.couponDefId ?? null;
  createForm.value = {
    couponName: row.couponName || '',
    couponType: row.couponType || 'AMOUNT_OFF',
    denominationYuan: Number(((Number(row.denominationCents) || 0) / 100).toFixed(2)) || 1,
    minSpendYuan: Number(((Number(row.minSpendCents) || 0) / 100).toFixed(2)),
    discountPercent: row.discountPercent ?? null,
    validityDays: row.validityDays || 30,
    maxIssueCount: row.maxIssueCount || 0,
    description: row.description || '',
    activityId: row.activityId ?? null,
    // 🔴 编辑时必须回填范围，否则一打开就看到「全平台通用」——
    //    运营会以为范围没配过而重填一次（今天在退货那里踩过同类残留坑）。
    scopeType: (row.scopeType as 'ALL' | 'DEVICE' | 'MERCHANT') || 'ALL',
    scopeMerchantId: row.scopeMerchantId ?? null,
    scopeDeviceIds: row.scopeDeviceIds ?? []
  };
  void loadActivityOptions();
  showCreate.value = true;
}

async function onCreateSubmit() {
  const form = createForm.value;
  if (!form.couponName.trim()) return ElMessage.warning('请填写名称');
  if (!form.couponType) return ElMessage.warning('请选择类型');
  if (!form.validityDays || form.validityDays < 1) {
    return ElMessage.warning('有效天数须至少为 1');
  }
  const isPercent = form.couponType === 'PERCENT_OFF';
  if (isPercent) {
    const pct = Number(form.discountPercent);
    if (!Number.isFinite(pct) || pct < 1 || pct > 99) {
      return ElMessage.warning('折扣百分比须在 1～99 之间');
    }
  } else {
    const yuan = Number(form.denominationYuan);
    if (!Number.isFinite(yuan) || yuan <= 0) {
      return ElMessage.warning('请填写大于 0 的面值');
    }
  }
  if (form.minSpendYuan != null && Number(form.minSpendYuan) < 0) {
    return ElMessage.warning('最低消费不能为负数');
  }
  // 🔴 V319：范围自洽性在提交前拦一次（后端也会 400，但那时用户已经填完一堆字段）
  if (form.scopeType === 'MERCHANT' && !(form.scopeMerchantId || '').trim()) {
    return ElMessage.warning('范围选了「指定商户」，请填写商户 ID');
  }
  saving.value = true;
  try {
    const body = {
      couponName: form.couponName.trim(),
      couponType: form.couponType,
      denominationCents: isPercent ? 0 : (yuanToCents(form.denominationYuan) ?? 0),
      minSpendCents: yuanToCents(form.minSpendYuan) ?? 0,
      discountPercent: isPercent ? Number(form.discountPercent) : null,
      validityDays: form.validityDays,
      maxIssueCount: form.maxIssueCount || 0,
      description: form.description,
      activityId: form.activityId || null,
      scopeType: form.scopeType,
      scopeMerchantId: form.scopeType === 'MERCHANT' ? (form.scopeMerchantId || '').trim() : null,
      scopeDeviceIds: form.scopeType === 'DEVICE' ? form.scopeDeviceIds : []
    };
    if (editingId.value) {
      await api.request(AdminEndpoints.couponDefinition(editingId.value), 'PUT', body);
      ElMessage.success('已更新');
    } else {
      await api.request(AdminEndpoints.couponDefinitions, 'POST', body);
      ElMessage.success('创建成功');
    }
    showCreate.value = false;
    editingId.value = null;
    await crud.load();
    await loadActiveCoupons();
  } catch (e) {
    ElMessage.error(errorMessage(e, '保存失败'));
  } finally {
    saving.value = false;
  }
}

async function onIssueSubmit() {
  if (!issueForm.value.couponDefId || !issueForm.value.userId) {
    return ElMessage.warning('请选择优惠券并填写用户编号');
  }
  try {
    await ElMessageBox.confirm(
      `确认向用户 ${issueForm.value.userId} 发放优惠券 ${issueForm.value.couponDefId}？`,
      '发券确认',
      { type: 'warning' }
    );
  } catch {
    return;
  }
  saving.value = true;
  try {
    await api.request(AdminEndpoints.couponIssue, 'POST', issueForm.value);
    ElMessage.success('发券成功');
    showIssue.value = false;
  } catch (e) {
    ElMessage.error(errorMessage(e, '发券失败'));
  } finally {
    saving.value = false;
  }
}

function openBatchIssue() {
  batchForm.value = { couponDefId: null, userIdsText: '' };
  batchVisible.value = true;
}

async function onBatchIssueSubmit() {
  if (!batchForm.value.couponDefId) {
    ElMessage.warning('请选择优惠券');
    return;
  }
  const userIds = batchForm.value.userIdsText
    .split(/\r?\n/)
    .map((s) => s.trim())
    .filter((s) => /^\d+$/.test(s))
    .map(Number);
  if (!userIds.length) {
    ElMessage.warning('请至少填写一个有效的用户ID');
    return;
  }
  try {
    await ElMessageBox.confirm(
      `确认向 ${userIds.length} 个用户批量发放优惠券 ${batchForm.value.couponDefId}？`,
      '批量发券确认',
      { type: 'warning' }
    );
  } catch {
    return;
  }
  saving.value = true;
  try {
    await api.request(AdminEndpoints.couponBatchIssue, 'POST', {
      couponDefId: batchForm.value.couponDefId,
      userIds
    });
    ElMessage.success(`已向 ${userIds.length} 个用户发券`);
    batchVisible.value = false;
  } catch (e) {
    ElMessage.error(errorMessage(e, '发券失败'));
  } finally {
    saving.value = false;
  }
}

async function onToggleStatus(row: OpenApiCouponDefinitionDto) {
  const next = row.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE';
  const action = displayLabel('enable_status', next);
  try {
    await ElMessageBox.confirm(`确认${action}优惠券「${row.couponName}」？`, '优惠券状态', {
      type: 'warning'
    });
    await api.request(AdminEndpoints.couponDefinitionStatus(row.couponDefId ?? 0, next), 'PUT');
    ElMessage.success(`已${action}`);
    await crud.load();
    await loadActiveCoupons();
  } catch (e: unknown) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : `${action}失败`);
    }
  }
}

function syncRouteQuery() {
  const query: Record<string, string> = {};
  if (keyword.value.trim()) query.keyword = keyword.value.trim();
  if (statusFilter.value) query.status = statusFilter.value;
  router.replace({ query });
}

function search() {
  syncRouteQuery();
  void crud.search();
}

function resetFilters() {
  keyword.value = '';
  statusFilter.value = '';
  syncRouteQuery();
  void crud.search();
}

function applyRouteQuery() {
  let changed = false;
  const qKeyword = typeof route.query.keyword === 'string' ? route.query.keyword : '';
  if (qKeyword !== keyword.value) {
    keyword.value = qKeyword;
    changed = true;
  }
  const qStatus = typeof route.query.status === 'string' ? route.query.status : '';
  if (qStatus !== statusFilter.value) {
    statusFilter.value = qStatus;
    changed = true;
  }
  return changed;
}

async function reloadFromRouteQuery() {
  if (!applyRouteQuery()) return;
  await crud.search();
}

watch(
  () => [route.query.keyword, route.query.status] as const,
  () => {
    void reloadFromRouteQuery();
  }
);

// 首查前需先应用路由查询参数，故保留显式首查（crud 已配 autoLoad: false）
onMounted(() => {
  applyRouteQuery();
  void loadActiveCoupons();
  void crud.load();
});
onActivated(() => {
  void reloadFromRouteQuery();
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
/* 弹窗分组标题（券面信息 / 使用范围）与字段提示：与商品建档弹窗同一口径 */
.form-group-title {
  margin: 4px 0 12px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
  font-size: var(--admin-font-size-sm);
}
.form-group-title:not(:first-child) {
  margin-top: 8px;
  padding-top: 12px;
  border-top: 1px solid var(--el-border-color-lighter);
}
.field-hint {
  color: var(--el-text-color-secondary);
  font-size: var(--admin-font-size-sm);
  line-height: 1.4;
}
</style>
