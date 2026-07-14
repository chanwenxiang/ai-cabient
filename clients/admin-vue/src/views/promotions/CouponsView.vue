<template>
  <div class="page">
    <div class="page-header">
      <h2>优惠券管理</h2>
      <el-button type="primary" @click="showCreate = true">新建优惠券</el-button>
      <el-button @click="showIssue = true">手动发券</el-button>
    </div>

    <div v-if="error" class="error-state">
      <el-alert :title="error" type="error" show-icon />
      <el-button size="small" @click="load">重试</el-button>
    </div>
    <el-table :data="list" border stripe v-loading="loading">
      <el-table-column prop="couponDefId" label="ID" width="80" />
      <el-table-column prop="couponName" label="名称" min-width="150" />
      <el-table-column prop="couponType" label="类型" width="100">
        <template #default="{ row }">{{ typeMap[row.couponType] || row.couponType }}</template>
      </el-table-column>
      <el-table-column label="面值" width="100">
        <template #default="{ row }">¥{{ (row.denominationCents / 100).toFixed(0) }}</template>
      </el-table-column>
      <el-table-column label="最低消费" width="120">
        <template #default="{ row }">¥{{ (row.minSpendCents / 100).toFixed(0) }}</template>
      </el-table-column>
      <el-table-column label="有效期" width="80">
        <template #default="{ row }">{{ row.validityDays }}天</template>
      </el-table-column>
      <el-table-column label="发行/总量" width="120">
        <template #default="{ row }">{{ row.issuedCount }}/{{ row.maxIssueCount || '不限' }}</template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="onIssue(row)">发券</el-button>
          <el-button size="small" @click="onToggleStatus(row)">
            {{ row.status === 'ACTIVE' ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && !error && !list.length" description="暂无优惠券" />

    <!-- 新建对话框 -->
    <el-dialog v-model="showCreate" title="新建优惠券" width="500px">
      <el-form :model="createForm" label-width="100px">
        <el-form-item label="名称"><el-input v-model="createForm.couponName" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="createForm.couponType">
            <el-option label="满减券" value="AMOUNT_OFF" />
            <el-option label="折扣券" value="PERCENT_OFF" />
            <el-option label="兑换券" value="EXCHANGE" />
          </el-select>
        </el-form-item>
        <el-form-item label="面值(分)"><el-input-number v-model="createForm.denominationCents" :min="1" style="width:100%" /></el-form-item>
        <el-form-item label="最低消费(分)"><el-input-number v-model="createForm.minSpendCents" :min="0" style="width:100%" /></el-form-item>
        <el-form-item label="折扣百分比"><el-input-number v-model="createForm.discountPercent" :min="1" :max="99" style="width:100%" /></el-form-item>
        <el-form-item label="有效天数"><el-input-number v-model="createForm.validityDays" :min="1" :max="365" style="width:100%" /></el-form-item>
        <el-form-item label="总量限制"><el-input-number v-model="createForm.maxIssueCount" :min="0" style="width:100%" /></el-form-item>
        <el-form-item label="描述"><el-input type="textarea" v-model="createForm.description" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreate = false">取消</el-button>
        <el-button type="primary" @click="onCreateSubmit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 发券对话框 -->
    <el-dialog v-model="showIssue" title="手动发券" width="450px">
      <el-form label-width="80px">
        <el-form-item label="优惠券">
          <el-select v-model="issueForm.couponDefId" style="width:100%">
            <el-option v-for="d in list" :key="d.couponDefId" :label="d.couponName" :value="d.couponDefId" />
          </el-select>
        </el-form-item>
        <el-form-item label="用户ID">
          <el-input-number v-model="issueForm.userId" :min="1" style="width:100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showIssue = false">取消</el-button>
        <el-button type="primary" @click="onIssueSubmit">发放</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { get, post } from '@/api/client';
import { ElMessage } from 'element-plus';

const loading = ref(false);
const error = ref('');
const list = ref<any[]>([]);
const showCreate = ref(false);
const showIssue = ref(false);

const createForm = ref<any>({
  couponName: '', couponType: 'AMOUNT_OFF', denominationCents: 100,
  minSpendCents: 0, discountPercent: 90, validityDays: 30, maxIssueCount: 0, description: ''
});
const issueForm = ref<any>({ couponDefId: null, userId: null });

const typeMap: Record<string, string> = { AMOUNT_OFF: '满减券', PERCENT_OFF: '折扣券', EXCHANGE: '兑换券', FREE_SHIPPING: '免运费' };

onMounted(() => load());

async function load() {
  loading.value = true;
  try { const res = await get('/api/v2/coupons/definitions'); list.value = res.data ?? []; }
  catch (e: any) { error.value = e?.message || '加载失败'; ElMessage.error('加载失败'); }
  finally { loading.value = false; }
}

async function onCreateSubmit() {
  try {
    await post('/api/v2/coupons/definitions', createForm.value);
    ElMessage.success('创建成功');
    showCreate.value = false;
    load();
  } catch (e: any) { ElMessage.error(e?.message || '创建失败'); }
}

async function onIssueSubmit() {
  try {
    await post('/api/v2/coupons/issue', issueForm.value);
    ElMessage.success('发券成功');
    showIssue.value = false;
  } catch (e: any) { ElMessage.error(e?.message || '发券失败'); }
}

async function onIssue(row: any) {
  issueForm.value.couponDefId = row.couponDefId;
  showIssue.value = true;
}

function onToggleStatus(row: any) { ElMessage.info('功能开发中'); }
</script>

<style scoped>
.page { padding: 20px; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; gap: 12px; }
.page-header h2 { margin: 0; font-size: 20px; }
</style>
