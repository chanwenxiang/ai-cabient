<template>
  <el-card shadow="never" class="page-card">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">进件工作台</span>
            <span class="hint"
              >微信 / 支付宝 / 支付分进件状态登记（本波不强制打通生产进件 API）</span
            >
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
          <el-button v-if="canEdit" type="primary" @click="openCreate">新建进件</el-button>
        </div>
      </div>
    </template>

    <el-alert
      :type="hints?.mockEnabled ? 'warning' : 'success'"
      :closable="false"
      show-icon
      class="mb"
      :title="hints?.hint || '加载支付模式…'"
    >
      <template #default>
        <span>
          微信 {{ hints?.wechatPayLive ? '正式' : '演示' }} · 支付宝
          {{ hints?.alipayPayLive ? '正式' : '演示' }} · 支付分
          {{ hints?.payScoreLive ? '正式' : '演示' }}
        </span>
      </template>
    </el-alert>

    <el-form inline class="filter-bar filter-bar--compact">
      <el-form-item label="商户编号">
        <el-input v-model="merchantId" clearable placeholder="精确匹配" style="width: 160px" />
      </el-form-item>
      <el-form-item label="渠道">
        <el-select v-model="channel" clearable placeholder="全部" style="width: 120px">
          <el-option value="WECHAT" label="微信" />
          <el-option value="ALIPAY" label="支付宝" />
          <el-option value="PAYSCORE" label="支付分" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="status" clearable placeholder="全部" style="width: 120px">
          <el-option value="DRAFT" label="草稿" />
          <el-option value="SUBMITTED" label="已提交" />
          <el-option value="ACTIVE" label="已生效" />
          <el-option value="REJECTED" label="已驳回" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
      </el-form-item>
    </el-form>

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <el-table
          :data="rows"
          v-loading="loading"
          stripe
          border
          empty-text=" "
          class="report-table"
        >
          <template #empty>
            <el-empty v-if="hydrated && !loading" description="暂无进件记录" />
          </template>
      <el-table-column prop="merchantId" label="商户" min-width="140">
        <template #default="{ row }">
          <div>{{ row.merchantName || row.merchantId }}</div>
          <div v-if="row.merchantName" class="muted">{{ row.merchantId }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="channel" label="渠道" width="100">
        <template #default="{ row }">{{ channelLabel(row.channel) }}</template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
          <div v-if="row.approvalStatus === 'PENDING'" class="muted">审批中</div>
        </template>
      </el-table-column>
      <el-table-column label="外部商户号" min-width="140" show-overflow-tooltip>
        <template #default="{ row }">{{ row.externalMchId || '' }}</template>
      </el-table-column>
      <el-table-column label="外部单号" min-width="120" show-overflow-tooltip>
        <template #default="{ row }">{{ row.externalRef || '' }}</template>
      </el-table-column>
      <el-table-column label="支付模式" width="90">
        <template #default="{ row }">
          <el-tag :type="row.payLiveHint ? 'success' : 'info'" size="small">
            {{ row.payLiveHint ? '正式' : '演示' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="备注" min-width="120" show-overflow-tooltip>
        <template #default="{ row }">{{ row.note || '' }}</template>
      </el-table-column>
      <el-table-column label="最近同步" width="160">
        <template #default="{ row }">{{
          row.lastSyncedAt ? formatDateTime(row.lastSyncedAt) : ''
        }}</template>
      </el-table-column>
      <el-table-column label="创建时间" width="160">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) || '' }}</template>
      </el-table-column>
      <el-table-column label="更新时间" width="160">
        <template #default="{ row }">{{ formatDateTime(row.updatedAt) || '' }}</template>
      </el-table-column>
          <el-table-column label="操作" width="180" fixed="right" class-name="col-action" align="center">
            <template #default="{ row }">
              <el-button
                v-if="canEdit && row.status !== 'SUBMITTED'"
                link
                type="primary"
                @click="openEdit(row)"
              >
                编辑
              </el-button>
              <template v-if="row.status === 'SUBMITTED' && row.approvalStatus === 'PENDING'">
                <el-button link type="success" @click="review(row, true)">通过</el-button>
                <el-button link type="danger" @click="review(row, false)">驳回</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <PagePager
      :hydrated="hydrated"
      v-model:current-page="page"
      v-model:page-size="pageSize"
      :total="total"
      :page-sizes="[10, 20, 50, 100]"
      layout="total, sizes, prev, pager, next, jumper"
      background
      @current-change="load"
      @size-change="onSizeChange"
    />
  </el-card>

  <el-dialog
    v-model="dlg"
    :title="form.onboardingId ? '编辑进件' : '新建进件'"
    width="520px"
    destroy-on-close
  >
    <el-form label-width="110px">
      <el-form-item label="商户编号" required>
        <el-input v-model="form.merchantId" :disabled="!!form.onboardingId" />
      </el-form-item>
      <el-form-item label="渠道" required>
        <el-select v-model="form.channel" :disabled="!!form.onboardingId" style="width: 100%">
          <el-option value="WECHAT" label="微信" />
          <el-option value="ALIPAY" label="支付宝" />
          <el-option value="PAYSCORE" label="支付分" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="form.status" style="width: 100%">
          <el-option value="DRAFT" label="草稿" />
          <el-option value="SUBMITTED" label="提交审批" />
          <el-option
            v-if="form.onboardingId && form.status === 'ACTIVE'"
            value="ACTIVE"
            label="已生效"
            disabled
          />
          <el-option value="REJECTED" label="已驳回" disabled />
        </el-select>
      </el-form-item>
      <el-form-item label="外部商户号">
        <el-input v-model="form.externalMchId" />
      </el-form-item>
      <el-form-item label="外部单号/引用">
        <el-input v-model="form.externalRef" />
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="form.note" type="textarea" :rows="2" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dlg = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import { api } from '@/api/client';
import PagePager from '@/components/PagePager.vue';
import { useAuthStore } from '@/stores/auth';
import { formatDateTime } from '@aicabinet/shared-uni/format';

interface OnboardRow {
  onboardingId: number;
  merchantId: string;
  merchantName?: string;
  channel: string;
  status: string;
  externalMchId?: string;
  externalRef?: string;
  note?: string;
  lastSyncedAt?: string;
  createdAt?: string;
  updatedAt?: string;
  payLiveHint?: boolean;
  approvalStatus?: string;
}

const auth = useAuthStore();
const canEdit = computed(() => auth.hasPerm('ops:merchant:onboard:edit'));
const loading = ref(false);
const hydrated = ref(false);
const saving = ref(false);
const rows = ref<OnboardRow[]>([]);
const page = ref(1);
const pageSize = ref(20);
const total = ref(0);
const merchantId = ref('');
const channel = ref('');
const status = ref('');
const hints = ref<Record<string, any> | null>(null);
const dlg = ref(false);
const form = reactive({
  onboardingId: null as number | null,
  merchantId: '',
  channel: 'WECHAT',
  status: 'DRAFT',
  externalMchId: '',
  externalRef: '',
  note: ''
});

function channelLabel(c?: string) {
  return (
    ({ WECHAT: '微信', ALIPAY: '支付宝', PAYSCORE: '支付分' } as Record<string, string>)[
      String(c || '')
    ] ||
    c ||
    ''
  );
}
function statusLabel(s?: string) {
  return (
    (
      { DRAFT: '草稿', SUBMITTED: '已提交', ACTIVE: '已生效', REJECTED: '已驳回' } as Record<
        string,
        string
      >
    )[String(s || '')] ||
    s ||
    ''
  );
}
function statusTag(s?: string): 'info' | 'warning' | 'success' | 'danger' {
  switch (String(s || '')) {
    case 'ACTIVE':
      return 'success';
    case 'SUBMITTED':
      return 'warning';
    case 'REJECTED':
      return 'danger';
    default:
      return 'info';
  }
}

async function load() {
  loading.value = true;
  try {
    const q = new URLSearchParams({
      page: String(page.value - 1),
      size: String(pageSize.value)
    });
    if (merchantId.value.trim()) q.set('merchantId', merchantId.value.trim());
    if (channel.value) q.set('channel', channel.value);
    if (status.value) q.set('status', status.value);
    const [list, h] = await Promise.all([
      api.request<{ items: OnboardRow[]; total: number }>(
        `/api/v2/ops/admin/merchant-onboarding?${q}`,
        'GET'
      ),
      api
        .request<Record<string, any>>('/api/v2/ops/admin/merchant-onboarding/live-hints', 'GET')
        .catch(() => null)
    ]);
    rows.value = list.items || [];
    total.value = Number(list.total) || 0;
    hints.value = h;
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
    hydrated.value = true;
  }
}

function onSizeChange() {
  page.value = 1;
  load();
}

function search() {
  page.value = 1;
  load();
}

function openCreate() {
  Object.assign(form, {
    onboardingId: null,
    merchantId: '',
    channel: 'WECHAT',
    status: 'DRAFT',
    externalMchId: '',
    externalRef: '',
    note: ''
  });
  dlg.value = true;
}

function openEdit(row: OnboardRow) {
  Object.assign(form, {
    onboardingId: row.onboardingId,
    merchantId: row.merchantId,
    channel: row.channel,
    status: row.status,
    externalMchId: row.externalMchId || '',
    externalRef: row.externalRef || '',
    note: row.note || ''
  });
  dlg.value = true;
}

async function save() {
  if (!form.merchantId.trim() || !form.channel) {
    ElMessage.warning('请填写商户与渠道');
    return;
  }
  saving.value = true;
  try {
    const body = {
      merchantId: form.merchantId.trim(),
      channel: form.channel,
      status: form.status,
      externalMchId: form.externalMchId,
      externalRef: form.externalRef,
      note: form.note
    };
    if (form.onboardingId) {
      await api.request(`/api/v2/ops/admin/merchant-onboarding/${form.onboardingId}`, 'PUT', body);
    } else {
      await api.request('/api/v2/ops/admin/merchant-onboarding', 'POST', body);
    }
    ElMessage.success(form.status === 'SUBMITTED' ? '已提交审批' : '已保存');
    dlg.value = false;
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

async function review(row: OnboardRow, approve: boolean) {
  try {
    await ElMessageBox.confirm(
      approve
        ? `确认通过进件 ${row.merchantName || row.merchantId} · ${channelLabel(row.channel)}？`
        : '确认驳回该进件？',
      approve ? '审批通过' : '审批驳回',
      { type: approve ? 'info' : 'warning' }
    );
  } catch {
    return;
  }
  try {
    await api.request(`/api/v2/ops/admin/merchant-onboarding/${row.onboardingId}/review`, 'POST', {
      approve,
      remark: approve ? '审批通过' : '审批驳回'
    });
    ElMessage.success(approve ? '已通过' : '已驳回');
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '审批失败');
  }
}

onMounted(load);
</script>

<style scoped>
.mb {
  margin-bottom: 12px;
}
.muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
