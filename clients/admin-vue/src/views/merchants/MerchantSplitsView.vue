<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">商户与分账</span>
            <span class="hint">组织树、功能包与自助写开关；分账明细可提交微信分账。功能包关闭后对应小程序入口与 API 一并失效。</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-hasPermi="['ops:merchant:export']" @click="onExport">{{ exportButtonLabel }}</el-button>
          <el-button :icon="Refresh" :loading="loading || loadingMerchants || loadingStatus" @click="refresh">
            刷新
          </el-button>
        </div>
      </div>
    </template>

    <el-tabs v-model="tab" @tab-change="onTabChange">
      <el-tab-pane label="组织树" name="org">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="上级商户可见全部下级货柜。运营账号绑定上级后，数据范围自动包含下级组织。"
          class="status-banner"
        />
        <div class="org-toolbar">
          <el-button v-if="canEdit" type="primary" @click="openOrgEdit()">新建商户</el-button>
          <el-button :icon="Refresh" :loading="loadingMerchants" @click="loadMerchants">刷新</el-button>
        </div>
        <el-tree
          v-loading="loadingMerchants"
          :data="merchantTree"
          node-key="merchantId"
          default-expand-all
          :indent="20"
          :expand-on-click-node="false"
          :props="{ label: 'label', children: 'children' }"
          class="org-tree"
        >
          <template #default="{ data }">
            <div class="org-node">
              <div class="org-node__meta">
                <strong>{{ data.merchantName || data.merchantId }}</strong>
                <small>{{ data.merchantId }} · 设备 {{ data.deviceCount || 0 }}</small>
              </div>
              <div class="org-node__actions">
                <el-button v-if="canEdit" link type="primary" @click.stop="openOrgEdit(data)">编辑</el-button>
                <el-button v-if="canEdit" link @click.stop="openAssignDevices(data)">挂载货柜</el-button>
              </div>
            </div>
          </template>
        </el-tree>
      </el-tab-pane>

      <el-tab-pane label="商户列表" name="merchants">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="功能包按商户独立控制（关闭父商户不会自动级联到子商户）；与角色权限同时生效。改货道/改价为包内细粒度写开关。"
          class="status-banner"
        />
        <div class="table-scroll">
          <div class="table-scroll-inner" style="min-width: 920px">
            <el-table
              v-loading="loadingMerchants"
              :data="merchants"
              stripe
              border
              class="report-table"
              row-key="merchantId"
              @selection-change="onMerchantsSelectionChange"
            >
              <template #empty><el-empty description="暂无商户" /></template>
              <el-table-column type="selection" width="48" align="center" />
              <el-table-column label="商户" min-width="200" class-name="col-text">
                <template #default="{ row }">
                  <div class="master-data-cell">
                    <strong>{{ row.merchantName || row.merchantId }}</strong>
                    <small>{{ row.merchantId }}</small>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="抽成" width="96" align="right">
                <template #default="{ row }">{{ (row.platformRateBps / 100).toFixed(1) }}%</template>
              </el-table-column>
              <el-table-column label="现场作业" width="100" align="center">
                <template #default="{ row }">
                  <el-switch
                    :model-value="row.packFieldEnabled !== false"
                    :disabled="!canEdit"
                    :aria-label="`${row.merchantName}功能包：现场作业`"
                    title="功能包：柜机 / 补货 / 待办 / 库存"
                    @change="(v: boolean) => toggleFlag(row, 'packField', v)"
                  />
                </template>
              </el-table-column>
              <el-table-column label="经营工具" width="100" align="center">
                <template #default="{ row }">
                  <el-switch
                    :model-value="row.packBizEnabled !== false"
                    :disabled="!canEdit"
                    :aria-label="`${row.merchantName}功能包：经营工具`"
                    title="功能包：订单 / 结算 / 定价 / 争议 / 分析"
                    @change="(v: boolean) => toggleFlag(row, 'packBiz', v)"
                  />
                </template>
              </el-table-column>
              <el-table-column label="团队设置" width="100" align="center">
                <template #default="{ row }">
                  <el-switch
                    :model-value="row.packTeamEnabled !== false"
                    :disabled="!canEdit"
                    :aria-label="`${row.merchantName}功能包：团队与设置`"
                    title="功能包：商户设置 / 团队成员"
                    @change="(v: boolean) => toggleFlag(row, 'packTeam', v)"
                  />
                </template>
              </el-table-column>
              <el-table-column label="商户改货道" width="110" align="center">
                <template #default="{ row }">
                  <el-switch
                    :model-value="row.allowMerchantPlanogramEdit"
                    :disabled="!canEdit || row.packFieldEnabled === false"
                    :aria-label="`${row.merchantName}允许修改货道`"
                    :title="`${row.merchantName}：允许修改货道`"
                    @change="(v: boolean) => toggleFlag(row, 'planogram', v)"
                  />
                </template>
              </el-table-column>
              <el-table-column label="商户改价" width="100" align="center">
                <template #default="{ row }">
                  <el-switch
                    :model-value="row.allowMerchantPricingEdit"
                    :disabled="!canEdit || row.packBizEnabled === false"
                    :aria-label="`${row.merchantName}允许修改价格`"
                    :title="`${row.merchantName}：允许修改价格`"
                    @change="(v: boolean) => toggleFlag(row, 'pricing', v)"
                  />
                </template>
              </el-table-column>
              <el-table-column prop="deviceCount" label="设备数" width="90" align="center" />
            </el-table>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="运营配置" name="ops-config">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          class="status-banner"
          title="组织级备货/理货策略；变更前请通知理货员。"
        />
        <el-form inline class="filter-bar">
          <el-form-item label="商户">
            <el-select v-model="opsConfigMerchantId" filterable placeholder="选择商户" style="width: 280px" @change="loadOpsConfig">
              <el-option
                v-for="m in merchants"
                :key="m.merchantId"
                :label="`${m.merchantName} (${m.merchantId})`"
                :value="m.merchantId"
              />
            </el-select>
          </el-form-item>
        </el-form>
        <el-form v-if="opsConfig" label-width="140px" style="max-width: 640px">
          <el-form-item label="备货类型">
            <el-radio-group v-model="opsConfig.stockingType">
              <el-radio value="CAPACITY">容量</el-radio>
              <el-radio value="SALES">销量</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="缺货阈值%">
            <el-input-number v-model="opsConfig.stockoutThresholdPct" :min="1" :max="100" />
          </el-form-item>
          <el-form-item label="理货模式">
            <el-radio-group v-model="opsConfig.tallyMode">
              <el-radio value="INDEPENDENT">盘点补货独立</el-radio>
              <el-radio value="ONCE">一次性盘点补货</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="使用备货单">
            <el-switch v-model="opsConfig.useStockingList" />
          </el-form-item>
          <el-form-item label="补货输入">
            <el-radio-group v-model="opsConfig.replenishInputType">
              <el-radio value="ADD_QTY">补充数量</el-radio>
              <el-radio value="AFTER_QTY">补后数量</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="盘点拍照">
            <el-switch v-model="opsConfig.photoStocktake" />
          </el-form-item>
          <el-form-item label="补货拍照">
            <el-switch v-model="opsConfig.photoReplenish" />
          </el-form-item>
          <el-form-item label="进行中订单上限">
            <el-radio-group v-model="opsConfig.maxInflightOrders">
              <el-radio :value="0">0</el-radio>
              <el-radio :value="1">1</el-radio>
              <el-radio :value="2">2</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item>
            <el-button v-if="canEdit" type="primary" :loading="savingOpsConfig" @click="saveOpsConfig">保存配置</el-button>
          </el-form-item>
        </el-form>
        <el-divider content-position="left">商户侧推荐岗位</el-divider>
        <el-table :data="roleTemplates" stripe border>
          <el-table-column prop="templateName" label="岗位" width="120" />
          <el-table-column prop="description" label="说明" min-width="220" />
          <el-table-column prop="permissionHint" label="权限提示" min-width="240" show-overflow-tooltip />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="分账明细" name="splits">
        <el-alert
          v-if="psStatus"
          class="status-banner"
          :type="psStatus.apiReady ? 'success' : 'warning'"
          :closable="false"
          show-icon
        >
          <template #title>
            {{ psStatus.note }}
            <span class="status-meta">
              启用={{ psStatus.enabled ? '是' : '否' }} · API={{ psStatus.apiReady ? '就绪' : '未就绪' }} ·
              微信={{ psStatus.wechatPayConfigured }}
            </span>
          </template>
        </el-alert>

        <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="onSplitFilterChange">
          <el-form-item label="状态">
            <el-select v-model="status" clearable placeholder="全部" style="width: 150px" @change="onSplitFilterChange">
              <el-option
                v-for="item in dictOptions('split_status')"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="onSplitFilterChange">查询</el-button>
            <el-button @click="resetSplitFilter">重置</el-button>
          </el-form-item>
        </el-form>

        <div class="table-scroll">
          <div class="table-scroll-inner" style="min-width: 1000px">
            <el-table
              v-loading="loading"
              :data="splits"
              stripe
              border
              class="report-table"
              row-key="splitId"
              @selection-change="onSplitsSelectionChange"
            >
              <template #empty><el-empty description="暂无分账明细" /></template>
              <el-table-column type="selection" width="48" align="center" />
              <el-table-column label="分账编号" min-width="150" class-name="col-text">
                <template #default="{ row }"><span class="cell-id">{{ row.splitId }}</span></template>
              </el-table-column>
              <el-table-column label="订单" min-width="130" class-name="col-text" show-overflow-tooltip>
                <template #default="{ row }">
                  <button
                    v-if="row.orderId"
                    type="button"
                    class="link-cell mono"
                    @click="goOrder(row.orderId)"
                  >{{ row.orderId }}</button>
                  <span v-else class="muted">-</span>
                </template>
              </el-table-column>
              <el-table-column label="商户" min-width="150" class-name="col-text" show-overflow-tooltip>
                <template #default="{ row }">
                  <div class="master-data-cell">
                    <strong>{{ row.merchantName || row.merchantId || '-' }}</strong>
                    <small v-if="row.merchantId">{{ row.merchantId }}</small>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="商户收入" width="110" align="right" class-name="col-money">
                <template #default="{ row }">¥{{ money(row.merchantCents) }}</template>
              </el-table-column>
              <el-table-column label="状态" width="120" align="center">
                <template #default="{ row }">
                  <el-tag size="small" :type="splitTagType(row.status)">
                    {{ dictLabel('split_status', row.status) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="失败原因" min-width="160" class-name="col-text" show-overflow-tooltip>
                <template #default="{ row }">{{ row.failureReason || '-' }}</template>
              </el-table-column>
              <el-table-column
                v-if="canSplit"
                label="操作"
                width="110"
                class-name="col-action"
                align="center"
              >
                <template #default="{ row }">
                  <TableActions
                    v-if="splitActions(row).length"
                    :actions="splitActions(row)"
                    @action="(key) => onSplitAction(key, row)"
                  />
                  <span v-else class="muted">-</span>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>

        <div class="page-pager">
          <el-pagination
            v-model:current-page="splitPage"
            v-model:page-size="splitSize"
            :total="splitTotal"
            :page-sizes="[10, 20, 50, 100]"
            layout="total, sizes, prev, pager, next, jumper"
            background
            @current-change="loadSplits"
            @size-change="onSplitSizeChange"
          />
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="submitDialog" title="提交微信分账" width="480px" destroy-on-close>
      <p class="dialog-hint">
        分账 <code>{{ current?.splitId }}</code> · 订单 {{ current?.orderId }}
      </p>
      <el-form label-position="top">
        <el-form-item label="微信交易号">
          <el-input
            v-model="wxTransactionId"
            clearable
            placeholder="余额支付订单必填；微信支付订单可留空"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="submitDialog = false">取消</el-button>
        <el-button type="primary" :loading="acting" @click="confirmSubmit">确认提交</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="orgDialog" :title="orgForm.editing ? '编辑商户组织' : '新建商户'" width="520px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="商户 ID">
          <el-input v-model="orgForm.merchantId" :disabled="orgForm.editing" placeholder="如 MCH-EAST" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="orgForm.merchantName" placeholder="组织 / 商户名称" />
        </el-form-item>
        <el-form-item label="上级商户">
          <el-select v-model="orgForm.parentMerchantId" clearable filterable placeholder="无上级（根节点）" style="width: 100%">
            <el-option
              v-for="m in parentOptions"
              :key="m.merchantId"
              :label="`${m.merchantName}（${m.merchantId}）`"
              :value="m.merchantId"
              :disabled="m.merchantId === orgForm.merchantId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="抽成（bps，1000=10%）">
          <el-input-number v-model="orgForm.platformRateBps" :min="0" :max="10000" :step="100" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="orgDialog = false">取消</el-button>
        <el-button type="primary" :loading="orgSaving" @click="saveOrg">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="assignDialog" title="挂载货柜到商户" width="560px" destroy-on-close>
      <p class="dialog-hint">将设备归属到 <strong>{{ assignTarget?.merchantName || assignTarget?.merchantId }}</strong></p>
      <el-select
        v-model="assignDeviceIds"
        multiple
        filterable
        collapse-tags
        collapse-tags-tooltip
        placeholder="选择货柜"
        style="width: 100%"
      >
        <el-option
          v-for="d in allDevices"
          :key="d.deviceId"
          :label="`${d.deviceName || d.deviceId}${d.merchantId ? ` · 当前 ${d.merchantId}` : ''}`"
          :value="d.deviceId"
        />
      </el-select>
      <template #footer>
        <el-button @click="assignDialog = false">取消</el-button>
        <el-button type="primary" :loading="assignSaving" @click="saveAssignDevices">保存归属</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { Refresh, RefreshRight, Upload } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { dictLabel, dictOptions } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useNavAccess } from '@/composables/useNavAccess';
import { useTableSelection } from '@/composables/useTableSelection';
import { useAuthStore } from '@/stores/auth';
import type { MerchantDto, PageResult, ProfitSharingStatus, RevenueSplit } from '@aicabinet/shared-types';

const route = useRoute();
const { router, goPath } = useNavAccess();
const auth = useAuthStore();
const canEdit = computed(() => auth.hasPerm('ops:merchant:edit'));
const canSplit = computed(() => auth.hasPerm('ops:merchant:split'));

const tab = ref('org');
const loading = ref(false);
const loadingMerchants = ref(false);
const loadingStatus = ref(false);
const acting = ref(false);
const status = ref('');
const splits = ref<RevenueSplit[]>([]);
const merchants = ref<MerchantDto[]>([]);
const splitsLoaded = ref(false);
const splitPage = ref(1);
const splitSize = ref(20);
const splitTotal = ref(0);
const psStatus = ref<ProfitSharingStatus | null>(null);
const opsConfigMerchantId = ref('');
const opsConfig = ref<any>(null);
const savingOpsConfig = ref(false);
const roleTemplates = ref<any[]>([]);

const submitDialog = ref(false);
const wxTransactionId = ref('');
const current = ref<RevenueSplit | null>(null);

const orgDialog = ref(false);
const orgSaving = ref(false);
const orgForm = ref({
  editing: false,
  merchantId: '',
  merchantName: '',
  parentMerchantId: '' as string | null,
  platformRateBps: 1000
});
const assignDialog = ref(false);
const assignSaving = ref(false);
const assignTarget = ref<MerchantDto | null>(null);
const assignDeviceIds = ref<string[]>([]);
const allDevices = ref<{ deviceId: string; deviceName?: string; merchantId?: string }[]>([]);

type OrgNode = MerchantDto & { label: string; children: OrgNode[] };

const merchantTree = computed(() => {
  const map = new Map<string, OrgNode>();
  for (const m of merchants.value) {
    map.set(m.merchantId, {
      ...m,
      label: m.merchantName || m.merchantId,
      children: []
    });
  }
  const roots: OrgNode[] = [];
  for (const node of map.values()) {
    const parentId = node.parentMerchantId || '';
    if (parentId && map.has(parentId) && parentId !== node.merchantId) {
      map.get(parentId)!.children.push(node);
    } else {
      roots.push(node);
    }
  }
  const sortRec = (nodes: OrgNode[]) => {
    nodes.sort((a, b) =>
      String(a.merchantName || a.merchantId).localeCompare(String(b.merchantName || b.merchantId), 'zh')
    );
    nodes.forEach((n) => n.children.length && sortRec(n.children));
  };
  sortRec(roots);
  return roots;
});

const parentOptions = computed(() => merchants.value);

const {
  onSelectionChange: onMerchantsSelectionChange,
  pickSelected: pickMerchants,
  exportButtonLabel: merchantsExportLabel,
  clearSelection: clearMerchantsSelection
} = useTableSelection<MerchantDto>((r) => r.merchantId);

const {
  onSelectionChange: onSplitsSelectionChange,
  pickSelected: pickSplits,
  exportButtonLabel: splitsExportLabel,
  clearSelection: clearSplitsSelection
} = useTableSelection<RevenueSplit>((r) => r.splitId);

const exportButtonLabel = computed(() =>
  tab.value === 'splits' ? splitsExportLabel.value : merchantsExportLabel.value
);

const { onExport: exportMerchants } = useListCsv({
  filePrefix: '商户',
  headers: ['商户编号', '名称', '抽成', '现场作业', '经营工具', '团队设置', '商户改货道', '商户改价', '设备数'],
  toRows: () =>
    pickMerchants(merchants.value).map((row) => [
      row.merchantId,
      row.merchantName,
      `${(row.platformRateBps / 100).toFixed(1)}%`,
      row.packFieldEnabled !== false ? '是' : '否',
      row.packBizEnabled !== false ? '是' : '否',
      row.packTeamEnabled !== false ? '是' : '否',
      row.allowMerchantPlanogramEdit ? '是' : '否',
      row.allowMerchantPricingEdit ? '是' : '否',
      row.deviceCount ?? 0
    ])
});

const { onExport: exportSplits } = useListCsv({
  filePrefix: '分账明细',
  headers: ['分账编号', '订单', '商户', '商户收入', '状态', '失败原因'],
  toRows: () =>
    pickSplits(splits.value).map((row) => [
      row.splitId,
      row.orderId,
      row.merchantName || '',
      `¥${money(row.merchantCents)}`,
      dictLabel('split_status', row.status),
      row.failureReason || '-'
    ])
});

function money(cents?: number) {
  return ((cents || 0) / 100).toFixed(2);
}

function onExport() {
  if (tab.value === 'splits') exportSplits();
  else exportMerchants();
}

function goOrder(orderId?: string) {
  const id = String(orderId || '').trim();
  if (id) goPath('/orders', { orderId: id });
  else goPath('/orders');
}

function splitTagType(s: string) {
  if (s === 'SETTLED' || s === 'WECHAT_FINISHED') return 'success';
  if (s === 'WECHAT_FAILED' || s === 'FAILED') return 'danger';
  if (s === 'WECHAT_SUBMITTED') return 'warning';
  return 'info';
}

function splitActions(row: RevenueSplit): TableAction[] {
  const actions: TableAction[] = [];
  if (['ACCRUED', 'LEDGER_ONLY', 'WECHAT_FAILED'].includes(row.status)) {
    actions.push({ key: 'submit', label: '提交', icon: Upload, type: 'primary' });
  }
  if (row.status === 'WECHAT_SUBMITTED' || row.status === 'WECHAT_FAILED') {
    actions.push({ key: 'refresh', label: '刷新', icon: RefreshRight, type: 'success' });
  }
  return actions;
}

function syncRouteQuery() {
  const query: Record<string, string> = { tab: tab.value };
  if (tab.value === 'splits' && status.value) query.status = status.value;
  router.replace({ query });
}

async function loadMerchants() {
  loadingMerchants.value = true;
  try {
    merchants.value = await api.request<MerchantDto[]>('/api/v2/ops/admin/merchants', 'GET');
    clearMerchantsSelection();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '商户加载失败');
  } finally {
    loadingMerchants.value = false;
  }
}

async function loadStatus() {
  if (!canSplit.value) return;
  loadingStatus.value = true;
  try {
    psStatus.value = await api.request<ProfitSharingStatus>(
      '/api/v2/ops/admin/merchants/profit-sharing/status',
      'GET'
    );
  } catch {
    psStatus.value = null;
  } finally {
    loadingStatus.value = false;
  }
}

async function loadSplits() {
  if (!canSplit.value) {
    splits.value = [];
    splitTotal.value = 0;
    splitsLoaded.value = true;
    return;
  }
  loading.value = true;
  try {
    const q = new URLSearchParams({
      page: String(Math.max(0, splitPage.value - 1)),
      size: String(splitSize.value)
    });
    if (status.value) q.set('status', status.value);
    const data = await api.request<PageResult<RevenueSplit>>(
      `/api/v2/ops/admin/merchants/revenue-splits?${q}`,
      'GET'
    );
    splits.value = data.items || [];
    splitTotal.value = data.total ?? splits.value.length;
    splitsLoaded.value = true;
    clearSplitsSelection();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '分账明细加载失败');
  } finally {
    loading.value = false;
  }
}

function onSplitSizeChange() {
  splitPage.value = 1;
  loadSplits();
}

function onSplitFilterChange() {
  splitPage.value = 1;
  syncRouteQuery();
  loadSplits();
}

function resetSplitFilter() {
  status.value = '';
  splitPage.value = 1;
  syncRouteQuery();
  loadSplits();
}

function onTabChange(name: string | number) {
  const next = String(name);
  tab.value = next;
  syncRouteQuery();
  if (next === 'splits') {
    if (!splitsLoaded.value) loadSplits();
    if (!psStatus.value) loadStatus();
  }
  if (next === 'ops-config') {
    loadRoleTemplates();
    if (!opsConfigMerchantId.value && merchants.value.length) {
      opsConfigMerchantId.value = merchants.value[0].merchantId;
      loadOpsConfig();
    }
  }
}

async function loadRoleTemplates() {
  try {
    roleTemplates.value = await api.request('/api/v2/ops/admin/merchant-role-templates', 'GET');
  } catch {
    roleTemplates.value = [];
  }
}

async function loadOpsConfig() {
  if (!opsConfigMerchantId.value) {
    opsConfig.value = null;
    return;
  }
  try {
    opsConfig.value = await api.request(
      `/api/v2/ops/admin/merchants/${encodeURIComponent(opsConfigMerchantId.value)}/ops-config`,
      'GET'
    );
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载运营配置失败');
  }
}

async function saveOpsConfig() {
  if (!opsConfigMerchantId.value || !opsConfig.value) return;
  savingOpsConfig.value = true;
  try {
    opsConfig.value = await api.request(
      `/api/v2/ops/admin/merchants/${encodeURIComponent(opsConfigMerchantId.value)}/ops-config`,
      'PUT',
      opsConfig.value
    );
    ElMessage.success('运营配置已保存');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    savingOpsConfig.value = false;
  }
}

function refresh() {
  loadMerchants();
  if (tab.value === 'splits') {
    loadSplits();
    loadStatus();
  }
  if (tab.value === 'ops-config') {
    loadOpsConfig();
    loadRoleTemplates();
  }
}

function onSplitAction(key: string, row: RevenueSplit) {
  if (key === 'submit') openSubmit(row);
  else if (key === 'refresh') doRefresh(row);
}

function openSubmit(row: RevenueSplit) {
  current.value = row;
  wxTransactionId.value = row.wechatTransactionId || '';
  submitDialog.value = true;
}

async function confirmSubmit() {
  if (!current.value) return;
  acting.value = true;
  try {
    const body = wxTransactionId.value.trim()
      ? { wxTransactionId: wxTransactionId.value.trim() }
      : {};
    await api.request(
      `/api/v2/ops/admin/merchants/revenue-splits/${encodeURIComponent(current.value.splitId)}/wechat-submit`,
      'POST',
      body
    );
    submitDialog.value = false;
    ElMessage.success('已提交分账');
    await loadSplits();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '提交失败');
  } finally {
    acting.value = false;
  }
}

async function doRefresh(row: RevenueSplit) {
  acting.value = true;
  try {
    await api.request(
      `/api/v2/ops/admin/merchants/revenue-splits/${encodeURIComponent(row.splitId)}/wechat-refresh`,
      'POST'
    );
    ElMessage.success('已刷新状态');
    await loadSplits();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '刷新失败');
  } finally {
    acting.value = false;
  }
}

async function toggleFlag(
  row: MerchantDto,
  kind: 'planogram' | 'pricing' | 'packField' | 'packBiz' | 'packTeam',
  value: boolean
) {
  if (!canEdit.value) return;
  try {
    await api.request('/api/v2/ops/admin/merchants', 'POST', {
      merchantId: row.merchantId,
      merchantName: row.merchantName,
      contactPhone: row.contactPhone,
      platformRateBps: row.platformRateBps,
      wechatReceiverId: row.wechatReceiverId,
      status: row.status,
      remark: row.remark,
      parentMerchantId: row.parentMerchantId ?? '',
      allowMerchantPlanogramEdit: kind === 'planogram' ? value : row.allowMerchantPlanogramEdit,
      allowMerchantPricingEdit: kind === 'pricing' ? value : row.allowMerchantPricingEdit,
      packFieldEnabled: kind === 'packField' ? value : row.packFieldEnabled !== false,
      packBizEnabled: kind === 'packBiz' ? value : row.packBizEnabled !== false,
      packTeamEnabled: kind === 'packTeam' ? value : row.packTeamEnabled !== false
    });
    if (kind === 'planogram') row.allowMerchantPlanogramEdit = value;
    else if (kind === 'pricing') row.allowMerchantPricingEdit = value;
    else if (kind === 'packField') row.packFieldEnabled = value;
    else if (kind === 'packBiz') row.packBizEnabled = value;
    else row.packTeamEnabled = value;
    ElMessage.success('已更新');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '更新失败');
  }
}

function openOrgEdit(row?: MerchantDto) {
  if (row) {
    orgForm.value = {
      editing: true,
      merchantId: row.merchantId,
      merchantName: row.merchantName || '',
      parentMerchantId: row.parentMerchantId || '',
      platformRateBps: row.platformRateBps ?? 1000
    };
  } else {
    orgForm.value = {
      editing: false,
      merchantId: '',
      merchantName: '',
      parentMerchantId: '',
      platformRateBps: 1000
    };
  }
  orgDialog.value = true;
}

async function saveOrg() {
  const f = orgForm.value;
  if (!f.merchantId.trim() || !f.merchantName.trim()) {
    ElMessage.warning('请填写商户 ID 与名称');
    return;
  }
  orgSaving.value = true;
  try {
    const existing = merchants.value.find((m) => m.merchantId === f.merchantId.trim());
    await api.request('/api/v2/ops/admin/merchants', 'POST', {
      merchantId: f.merchantId.trim(),
      merchantName: f.merchantName.trim(),
      contactPhone: existing?.contactPhone,
      platformRateBps: f.platformRateBps,
      wechatReceiverId: existing?.wechatReceiverId,
      status: existing?.status || 'ACTIVE',
      remark: existing?.remark,
      parentMerchantId: f.parentMerchantId || '',
      allowMerchantPlanogramEdit: existing?.allowMerchantPlanogramEdit ?? false,
      allowMerchantPricingEdit: existing?.allowMerchantPricingEdit ?? false,
      packFieldEnabled: existing?.packFieldEnabled !== false,
      packBizEnabled: existing?.packBizEnabled !== false,
      packTeamEnabled: existing?.packTeamEnabled !== false
    });
    ElMessage.success('已保存组织');
    orgDialog.value = false;
    await loadMerchants();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    orgSaving.value = false;
  }
}

async function openAssignDevices(row: MerchantDto) {
  assignTarget.value = row;
  assignDialog.value = true;
  try {
    if (!allDevices.value.length) {
      allDevices.value = await api.request('/api/v2/ops/admin/devices?page=0&size=200', 'GET').then((page: any) =>
        page?.items || page || []
      );
    }
    assignDeviceIds.value = allDevices.value
      .filter((d) => d.merchantId === row.merchantId)
      .map((d) => d.deviceId);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载设备失败');
  }
}

async function saveAssignDevices() {
  if (!assignTarget.value) return;
  assignSaving.value = true;
  try {
    const targetId = assignTarget.value.merchantId;
    const selected = new Set(assignDeviceIds.value);
    const jobs: Promise<unknown>[] = [];
    for (const d of allDevices.value) {
      const shouldBelong = selected.has(d.deviceId);
      const belongs = d.merchantId === targetId;
      if (shouldBelong && !belongs) {
        jobs.push(
          api.request(`/api/v2/ops/admin/devices/${encodeURIComponent(d.deviceId)}`, 'PATCH', {
            merchantId: targetId
          })
        );
      } else if (!shouldBelong && belongs) {
        jobs.push(
          api.request(`/api/v2/ops/admin/devices/${encodeURIComponent(d.deviceId)}`, 'PATCH', {
            merchantId: ''
          })
        );
      }
    }
    await Promise.all(jobs);
    ElMessage.success('货柜归属已更新');
    assignDialog.value = false;
    allDevices.value = [];
    await loadMerchants();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    assignSaving.value = false;
  }
}

function applyRouteQuery() {
  let changed = false;
  const qTab = typeof route.query.tab === 'string' ? route.query.tab : '';
  if (qTab === 'merchants' || qTab === 'splits' || qTab === 'org') {
    if (tab.value !== qTab) {
      tab.value = qTab;
      changed = true;
    }
  } else if (!qTab && tab.value !== 'org') {
    tab.value = 'org';
    changed = true;
  }
  const qStatus = typeof route.query.status === 'string' ? route.query.status : '';
  if (qStatus !== status.value) {
    status.value = qStatus;
    changed = true;
  }
  return changed;
}

async function reloadFromRouteQuery() {
  if (!applyRouteQuery()) return;
  if (tab.value === 'splits') {
    await Promise.all([loadSplits(), loadStatus()]);
  } else {
    await loadMerchants();
  }
}

watch(
  () => [route.query.tab, route.query.status] as const,
  () => {
    void reloadFromRouteQuery();
  }
);

onMounted(() => {
  applyRouteQuery();
  syncRouteQuery();
  loadMerchants();
  if (tab.value === 'splits') {
    loadSplits();
    loadStatus();
  }
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
.page-card-head__meta { min-width: 0; }
.page-card-head__title { display: flex; flex-direction: column; gap: 4px; }
.title { font-weight: 600; font-size: 15px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.4; }
.page-card-head__actions { display: flex; gap: 8px; }
.master-data-cell { display: grid; gap: 2px; line-height: 1.35; }
.master-data-cell strong { font-weight: 650; }
.master-data-cell small {
  color: var(--el-text-color-secondary);
  font-size: 11px;
  font-family: var(--app-font-mono);
}
.link-cell {
  appearance: none;
  border: 0;
  padding: 0;
  margin: 0;
  background: transparent;
  color: var(--el-color-primary);
  cursor: pointer;
  text-align: left;
  font: inherit;
}
.link-cell:hover { text-decoration: underline; }
.link-cell.mono { font-family: var(--app-font-mono); font-size: 12px; }
.status-banner { margin-bottom: 12px; }
.status-meta { margin-left: 8px; font-weight: 400; opacity: 0.85; font-size: 12px; }
.muted { color: var(--layout-muted); font-size: 13px; }
.dialog-hint { margin: 0 0 12px; color: var(--layout-muted); line-height: 1.5; }
.dialog-hint code { font-size: 12px; }
.org-toolbar { display: flex; gap: 8px; margin-bottom: 12px; }
.org-tree {
  padding: 8px 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  min-height: 240px;
}
/* 默认节点高度过矮，双行文案会叠到下一行；放开高度并保证缩进层级可见 */
.org-tree :deep(.el-tree-node__content) {
  height: auto !important;
  min-height: 48px;
  padding-top: 8px;
  padding-bottom: 8px;
  align-items: center;
}
.org-tree :deep(.el-tree-node__expand-icon) {
  align-self: center;
  padding: 6px;
}
.org-tree :deep(.el-tree-node__label) {
  flex: 1;
  overflow: visible;
  width: 100%;
}
.org-node {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-right: 8px;
  min-width: 0;
  width: 100%;
}
.org-node__meta {
  display: grid;
  gap: 2px;
  min-width: 0;
  line-height: 1.35;
}
.org-node__meta strong {
  font-weight: 650;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.org-node__meta small {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.3;
}
.org-node__actions { display: flex; gap: 4px; flex-shrink: 0; }
</style>
