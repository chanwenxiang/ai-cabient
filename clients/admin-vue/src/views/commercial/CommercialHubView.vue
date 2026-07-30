<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">商业化中心</span>
            <span class="hint">进件 / 平台储值 / 识别算力（商业模式能力）</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button :icon="Refresh" :loading="loading" @click="refresh">刷新</el-button>
        </div>
      </div>
    </template>

    <el-tabs v-model="tab">
      <el-tab-pane label="商户进件" name="onboarding">
        <div class="toolbar">
          <el-button type="primary" @click="openOnboard">新增进件</el-button>
        </div>
        <el-table v-loading="loading" :data="onboardings" stripe border>
          <template #empty><el-empty description="暂无进件" /></template>
          <el-table-column prop="merchantId" label="商户" min-width="160" />
          <el-table-column prop="merchantName" label="名称" min-width="160" />
          <el-table-column prop="subjectType" label="主体" width="100" />
          <el-table-column prop="alipayRegStatus" label="支付宝登记" width="120" />
          <el-table-column prop="wechatPayscoreStatus" label="支付分" width="120" />
          <el-table-column prop="onboardStatus" label="入驻状态" width="120" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="平台储值" name="stored">
        <el-form inline>
          <el-form-item label="商户ID">
            <el-input v-model="merchantId" style="width: 200px" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="loadStored">查询</el-button>
          </el-form-item>
        </el-form>
        <el-descriptions v-if="stored" :column="2" border>
          <el-descriptions-item label="余额(元)">{{ (stored.balanceCents / 100).toFixed(2) }}</el-descriptions-item>
          <el-descriptions-item label="预警额度(元)">{{ (stored.warnThresholdCents / 100).toFixed(2) }}</el-descriptions-item>
          <el-descriptions-item label="通知人">{{ stored.notifyPhone || '-' }}</el-descriptions-item>
        </el-descriptions>
        <div class="toolbar" style="margin-top: 12px">
          <el-input-number v-model="rechargeYuan" :min="1" :precision="2" />
          <el-button type="primary" style="margin-left: 8px" @click="doRecharge">充值(元)</el-button>
        </div>
        <el-alert
          type="warning"
          :closable="false"
          show-icon
          style="margin-top: 12px"
          title="储值可用于平台功能费；余额可为负表示欠费，需产品策略决定是否停柜。"
        />
      </el-tab-pane>

      <el-tab-pane label="识别算力" name="compute">
        <el-form inline>
          <el-form-item label="商户ID">
            <el-input v-model="merchantId" style="width: 200px" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="loadCompute">查询</el-button>
          </el-form-item>
        </el-form>
        <el-row v-if="compute" :gutter="12">
          <el-col :span="8"><el-statistic title="剩余算力" :value="compute.remaining" /></el-col>
          <el-col :span="8"><el-statistic title="累计算力" :value="compute.cumulative" /></el-col>
          <el-col :span="8"><el-statistic title="已使用" :value="compute.used" /></el-col>
        </el-row>
        <div class="toolbar" style="margin-top: 12px">
          <el-input-number v-model="grantTimes" :min="1" />
          <el-button type="primary" style="margin-left: 8px" @click="doGrant">发放次数</el-button>
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="obDlg" title="新增进件" width="480px" destroy-on-close>
      <el-form label-width="110px">
        <el-form-item label="商户ID" required>
          <el-input v-model="obForm.merchantId" />
        </el-form-item>
        <el-form-item label="主体类型">
          <el-select v-model="obForm.subjectType" style="width: 100%">
            <el-option label="企业" value="ENTERPRISE" />
            <el-option label="个体" value="INDIVIDUAL" />
          </el-select>
        </el-form-item>
        <el-form-item label="支付宝登记">
          <el-select v-model="obForm.alipayRegStatus" style="width: 100%">
            <el-option label="待登记" value="PENDING" />
            <el-option label="登记通过" value="PASSED" />
          </el-select>
        </el-form-item>
        <el-form-item label="支付分绑定">
          <el-select v-model="obForm.wechatPayscoreStatus" style="width: 100%">
            <el-option label="待绑定" value="PENDING" />
            <el-option label="绑定成功" value="BOUND" />
          </el-select>
        </el-form-item>
        <el-form-item label="入驻状态">
          <el-select v-model="obForm.onboardStatus" style="width: 100%">
            <el-option label="草稿" value="DRAFT" />
            <el-option label="已签约" value="SIGNED" />
            <el-option label="入驻成功" value="SUCCESS" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="obDlg = false">取消</el-button>
        <el-button type="primary" @click="saveOnboard">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';

const tab = ref('onboarding');
const loading = ref(false);
const merchantId = ref('');
const onboardings = ref<any[]>([]);
const stored = ref<any>(null);
const compute = ref<any>(null);
const rechargeYuan = ref(100);
const grantTimes = ref(100);
const obDlg = ref(false);
const obForm = reactive({
  merchantId: '',
  subjectType: 'ENTERPRISE',
  alipayRegStatus: 'PENDING',
  wechatPayscoreStatus: 'PENDING',
  onboardStatus: 'DRAFT'
});

async function loadOnboarding() {
  onboardings.value = await api.request('/api/v2/ops/admin/commercial/onboarding', 'GET');
}

async function refresh() {
  loading.value = true;
  try {
    await loadOnboarding();
    if (merchantId.value) {
      await loadStored();
      await loadCompute();
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

async function loadStored() {
  if (!merchantId.value) return;
  stored.value = await api.request(`/api/v2/ops/admin/commercial/stored-value/${merchantId.value}`, 'GET');
}

async function loadCompute() {
  if (!merchantId.value) return;
  compute.value = await api.request(`/api/v2/ops/admin/commercial/compute/${merchantId.value}`, 'GET');
}

async function doRecharge() {
  if (!merchantId.value) {
    ElMessage.warning('请填写商户ID');
    return;
  }
  stored.value = await api.request(
    `/api/v2/ops/admin/commercial/stored-value/${merchantId.value}/recharge`,
    'POST',
    { amountCents: Math.round(rechargeYuan.value * 100) }
  );
  ElMessage.success('已充值');
}

async function doGrant() {
  if (!merchantId.value) {
    ElMessage.warning('请填写商户ID');
    return;
  }
  compute.value = await api.request(
    `/api/v2/ops/admin/commercial/compute/${merchantId.value}/grant`,
    'POST',
    { gained: grantTimes.value }
  );
  ElMessage.success('已发放');
}

function openOnboard() {
  obForm.merchantId = merchantId.value || '';
  obDlg.value = true;
}

async function saveOnboard() {
  await api.request('/api/v2/ops/admin/commercial/onboarding', 'POST', { ...obForm });
  ElMessage.success('已保存');
  obDlg.value = false;
  await loadOnboarding();
}

onMounted(refresh);
</script>

<style scoped>
.toolbar { margin-bottom: 12px; }
</style>
