<template>
  <div class="page">
    <div class="page-header">
      <h2>营销活动管理</h2>
      <el-button type="primary" @click="showCreate = true">新建活动</el-button>
    </div>

    <div v-if="error" class="error-state">
      <el-alert :title="error" type="error" show-icon />
      <el-button size="small" @click="load">重试</el-button>
    </div>
    <el-table :data="list" border stripe v-loading="loading">
      <el-table-column prop="activityId" label="ID" width="80" />
      <el-table-column prop="activityName" label="活动名称" min-width="160" />
      <el-table-column prop="activityType" label="类型" width="100">
        <template #default="{ row }">{{ typeMap[row.activityType] || row.activityType }}</template>
      </el-table-column>
      <el-table-column label="时间" min-width="200">
        <template #default="{ row }">{{ formatTime(row.startTime) }} ~ {{ formatTime(row.endTime) }}</template>
      </el-table-column>
      <el-table-column prop="budgetCents" label="预算" width="120">
        <template #default="{ row }">¥{{ (row.budgetCents / 100).toFixed(0) }}</template>
      </el-table-column>
      <el-table-column prop="usedCents" label="已使用" width="100">
        <template #default="{ row }">¥{{ (row.usedCents / 100).toFixed(0) }}</template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)">{{ statusMap[row.status] || row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="onEdit(row)">编辑</el-button>
          <el-button v-if="row.status === 'DRAFT'" size="small" type="success" @click="onLaunch(row)">发布</el-button>
          <el-button v-if="row.status === 'ACTIVE'" size="small" type="danger" @click="onStop(row)">停止</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && !error && !list.length" description="暂无活动" />

    <!-- 新建/编辑对话框 -->
    <el-dialog v-model="showCreate" title="新建活动" width="600px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="活动名称"><el-input v-model="form.activityName" /></el-form-item>
        <el-form-item label="活动类型">
          <el-select v-model="form.activityType">
            <el-option label="满减" value="FULL_REDUCE" />
            <el-option label="折扣" value="DISCOUNT" />
            <el-option label="买赠" value="BUY_GIFT" />
            <el-option label="第二件半价" value="SECOND_HALF" />
          </el-select>
        </el-form-item>
        <el-form-item label="开始时间"><el-date-picker v-model="form.startTime" type="datetime" style="width:100%" /></el-form-item>
        <el-form-item label="结束时间"><el-date-picker v-model="form.endTime" type="datetime" style="width:100%" /></el-form-item>
        <el-form-item label="预算(分)"><el-input-number v-model="form.budgetCents" :min="0" style="width:100%" /></el-form-item>
        <el-form-item label="每人限制"><el-input-number v-model="form.userLimit" :min="1" :max="100" style="width:100%" /></el-form-item>
        <el-form-item label="描述"><el-input type="textarea" v-model="form.description" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreate = false">取消</el-button>
        <el-button type="primary" @click="onSubmit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { get, post } from '@/api/client';
import { ElMessage, ElMessageBox } from 'element-plus';

const loading = ref(false);
const error = ref('');
const list = ref<any[]>([]);
const showCreate = ref(false);
const form = ref<any>({ activityName: '', activityType: 'FULL_REDUCE', startTime: '', endTime: '', budgetCents: 0, userLimit: 1, description: '' });

const typeMap: Record<string, string> = { FULL_REDUCE: '满减', DISCOUNT: '折扣', BUY_GIFT: '买赠', SECOND_HALF: '第二件半价' };
const statusMap: Record<string, string> = { DRAFT: '草稿', ACTIVE: '进行中', STOPPED: '已停止', ENDED: '已结束' };

onMounted(() => load());

async function load() {
  loading.value = true;
  try { const res = await get('/api/v2/ops/promotions'); list.value = res.data ?? []; }
  catch (e: any) { error.value = e?.message || '加载失败'; ElMessage.error('加载失败'); }
  finally { loading.value = false; }
}

function statusType(s: string) {
  const m: Record<string, string> = { DRAFT: 'info', ACTIVE: 'success', STOPPED: 'warning', ENDED: 'info' };
  return m[s] || 'info';
}

function formatTime(t: string) { if (!t) return ''; return t.substring(0, 16).replace('T', ' '); }

async function onSubmit() {
  try {
    const f = form.value;
    f.startTime = new Date(f.startTime).toISOString();
    f.endTime = new Date(f.endTime).toISOString();
    await post('/api/v2/ops/promotions', f);
    ElMessage.success('创建成功');
    showCreate.value = false;
    load();
  } catch (e: any) { ElMessage.error(e?.message || '创建失败'); }
}

async function onLaunch(row: any) {
  try { await post(`/api/v2/ops/promotions/${row.activityId}/launch`); ElMessage.success('已发布'); load(); }
  catch (e: any) { ElMessage.error(e?.message || '发布失败'); }
}

async function onStop(row: any) {
  try { await post(`/api/v2/ops/promotions/${row.activityId}/stop`); ElMessage.success('已停止'); load(); }
  catch (e: any) { ElMessage.error(e?.message || '停止失败'); }
}

function onEdit(row: any) { /* TODO */ ElMessage.info('编辑功能开发中'); }
</script>

<style scoped>
.page { padding: 20px; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; }
.page-header h2 { margin: 0; font-size: 20px; }
</style>
