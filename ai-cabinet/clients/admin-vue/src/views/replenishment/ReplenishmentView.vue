<template>
  <div class="replenishment-page">
    <section class="hero-card">
      <div>
        <p class="eyebrow">补货运营中心</p>
        <h2>路线、出库与现场任务</h2>
        <p>按设备缺货情况规划路线，创建后自动衔接仓库出库和补货员任务。</p>
      </div>
      <div class="hero-actions">
        <el-button type="primary" @click="openPlan">规划补货路线</el-button>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </section>

    <div class="summary-grid">
      <div class="summary-card"><span>待执行路线</span><strong>{{ plannedCount }}</strong></div>
      <div class="summary-card"><span>待处理设备</span><strong>{{ pendingTaskCount }}</strong></div>
      <div class="summary-card"><span>商户要货</span><strong>{{ requests.length }}</strong></div>
    </div>

    <el-card class="page-card" shadow="never">
      <el-tabs v-model="tab">
        <el-tab-pane label="补货路线" name="routes">
          <el-table :data="routes" stripe empty-text="暂无补货路线">
            <el-table-column type="expand">
              <template #default="{ row }">
                <div class="route-detail">
                  <div class="route-meta">
                    <span>计划日期：{{ row.plannedDate || '-' }}</span>
                    <span>负责人：{{ row.assigneeUserId || '未分配' }}</span>
                    <span>预计里程：{{ row.totalDistanceM ? `${row.totalDistanceM} 米` : '未计算' }}</span>
                  </div>
                  <el-table :data="row.tasks || []" size="small" empty-text="该路线暂无设备任务">
                    <el-table-column label="设备" min-width="210"><template #default="scope"><div class="master-data-cell"><strong>{{ deviceName(scope.row.deviceId) }}</strong><small>{{ scope.row.deviceId }}</small></div></template></el-table-column>
                    <el-table-column label="任务状态" width="120"><template #default="scope"><el-tag :type="dictTagType(scope.row.status)">{{ dictLabel('replenishment_task_status', scope.row.status) }}</el-tag></template></el-table-column>
                    <el-table-column prop="notes" label="路线说明" min-width="220" show-overflow-tooltip />
                  </el-table>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="routeId" label="路线编号" width="110" />
            <el-table-column prop="routeName" label="路线名称" min-width="200" />
            <el-table-column label="设备数" width="100"><template #default="{ row }">{{ row.tasks?.length || 0 }}</template></el-table-column>
            <el-table-column prop="plannedDate" label="计划日期" width="130" />
            <el-table-column label="状态" width="120"><template #default="{ row }"><el-tag :type="dictTagType(row.status)">{{ dictLabel('replenishment_route_status', row.status) }}</el-tag></template></el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="商户要货" name="requests">
          <el-table :data="requests" stripe empty-text="暂无待处理要货申请">
            <el-table-column prop="requestId" label="要货单" width="110" />
            <el-table-column prop="merchantName" label="商户" min-width="180" />
            <el-table-column label="目标设备" min-width="200"><template #default="{ row }"><div class="master-data-cell"><strong>{{ deviceName(row.deviceId) }}</strong><small>{{ row.deviceId }}</small></div></template></el-table-column>
            <el-table-column label="状态" width="120"><template #default="{ row }"><el-tag :type="dictTagType(row.status)">{{ dictLabel('replenishment_request_status', row.status) }}</el-tag></template></el-table-column>
            <el-table-column prop="createdAt" label="提交时间" width="180" />
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-dialog v-model="planDialog" title="规划补货路线" width="620px">
      <el-alert type="info" :closable="false" title="路线创建后，系统会按柜机缺货建议尝试生成仓库出库单。库存不足时路线仍会保留，并进入运营待处理。" />
      <el-form label-width="96px" class="plan-form">
        <el-form-item label="路线名称"><el-input v-model="planForm.routeName" maxlength="80" placeholder="例如：浦东早班补货路线" /></el-form-item>
        <el-form-item label="计划日期"><input v-model="planForm.plannedDate" class="native-date" type="date" /></el-form-item>
        <el-form-item label="负责人"><el-input-number v-model="planForm.assigneeUserId" :min="1" :precision="0" controls-position="right" /></el-form-item>
        <el-form-item label="目标设备">
          <el-select v-model="planForm.deviceIds" multiple filterable collapse-tags :max-collapse-tags="3" placeholder="选择需要补货的柜机">
            <el-option v-for="device in devices" :key="device.deviceId" :label="`${device.deviceName || device.deviceId}（${device.deviceId}）`" :value="device.deviceId" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="planDialog=false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="createPlan">创建路线</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import { dictLabel, dictTagType } from '@aicabinet/shared-dict';

type Row = Record<string, any>;
const loading = ref(false);
const saving = ref(false);
const tab = ref('routes');
const routes = ref<Row[]>([]);
const requests = ref<Row[]>([]);
const devices = ref<Row[]>([]);
const planDialog = ref(false);
const planForm = reactive({ routeName:'', plannedDate:'', assigneeUserId:1, deviceIds:[] as string[] });
const plannedCount = computed(() => routes.value.filter(item => ['PLANNED','IN_PROGRESS'].includes(item.status)).length);
const pendingTaskCount = computed(() => routes.value.flatMap(item => item.tasks || []).filter(item => ['PENDING','IN_PROGRESS'].includes(item.status)).length);

function deviceName(deviceId:string) { return devices.value.find(item => item.deviceId === deviceId)?.deviceName || deviceId || '-'; }
function localDate() { const now=new Date(); return new Date(now.getTime()-now.getTimezoneOffset()*60000).toISOString().slice(0,10); }
function openPlan() {
  Object.assign(planForm, { routeName:`${new Date().toLocaleDateString('zh-CN')} 补货路线`, plannedDate:localDate(), assigneeUserId:1, deviceIds:[] });
  planDialog.value = true;
}

async function createPlan() {
  if (!planForm.routeName.trim()) return ElMessage.warning('请填写路线名称');
  if (!planForm.deviceIds.length) return ElMessage.warning('请至少选择一台柜机');
  saving.value = true;
  try {
    await api.request('/api/v2/ops/admin/replenishment/plan', 'POST', { ...planForm, startLatitude:null, startLongitude:null });
    planDialog.value = false;
    tab.value = 'routes';
    ElMessage.success('补货路线已创建，正在准备仓库出库单');
    await load();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '路线创建失败');
  } finally { saving.value = false; }
}

async function load() {
  loading.value = true;
  try {
    const [r, req, deviceRows] = await Promise.all([
      api.request<Row[]>('/api/v2/ops/admin/replenishment/routes', 'GET'),
      api.request<Row[]>('/api/v2/ops/admin/replenishment/requests?status=SUBMITTED', 'GET').catch(() => []),
      api.request<Row[]>('/api/v2/ops/admin/devices', 'GET')
    ]);
    routes.value = r || [];
    requests.value = req || [];
    devices.value = deviceRows || [];
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '补货数据加载失败');
  } finally { loading.value = false; }
}

onMounted(load);
</script>

<style scoped>
.replenishment-page{display:grid;gap:16px}.hero-card{display:flex;align-items:center;justify-content:space-between;gap:24px;padding:24px 28px;border-radius:18px;color:#fff;background:linear-gradient(135deg,#064e3b,#059669 62%,#14b8a6);box-shadow:0 18px 40px rgba(5,150,105,.18)}.eyebrow{margin:0 0 6px;font-size:12px;letter-spacing:.14em;opacity:.75}.hero-card h2{margin:0;font-size:26px}.hero-card p:last-child{margin:8px 0 0;opacity:.84}.hero-actions{display:flex;gap:10px;flex-wrap:wrap}.summary-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:14px}.summary-card{display:grid;gap:6px;padding:18px 20px;border-radius:14px;background:#fff;border:1px solid #e2e8f0}.summary-card span{color:#64748b;font-size:13px}.summary-card strong{font-size:26px;color:#0f172a}.page-card{border:0;border-radius:16px}.route-detail{padding:12px 46px 18px}.route-meta{display:flex;gap:24px;flex-wrap:wrap;margin-bottom:12px;color:#64748b;font-size:13px}.master-data-cell{display:grid;gap:2px}.master-data-cell strong{color:#1e293b}.master-data-cell small{color:#94a3b8;font-size:11px}.plan-form{margin-top:18px}.plan-form .el-select{width:100%}.native-date{width:100%;height:32px;padding:0 10px;border:1px solid #dcdfe6;border-radius:4px;color:#303133;background:#fff;box-sizing:border-box}.native-date:focus{outline:none;border-color:#409eff}@media(max-width:760px){.hero-card{align-items:flex-start;flex-direction:column}.summary-grid{grid-template-columns:1fr}.route-detail{padding:10px}}
</style>
