<template>
  <div class="franchise-page">
    <div class="page-header">
      <h2>加盟商管理</h2>
      <el-button type="primary" @click="handleAdd">新增加盟商</el-button>
    </div>

    <!-- 搜索栏 -->
    <div class="search-bar">
      <el-input v-model="searchForm.keyword" placeholder="搜索加盟商名称/联系人" style="width: 200px" />
      <el-select v-model="searchForm.status" placeholder="状态" clearable style="width: 120px">
        <el-option label="全部" value="" />
        <el-option label="正常" value="active" />
        <el-option label="冻结" value="frozen" />
      </el-select>
      <el-button type="primary" @click="loadFranchisees">查询</el-button>
    </div>

    <!-- 表格 -->
    <el-table :data="franchisees" stripe>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="name" label="加盟商名称" min-width="150" />
      <el-table-column prop="contactPerson" label="联系人" width="100" />
      <el-table-column prop="contactPhone" label="联系电话" width="120" />
      <el-table-column label="设备数量" width="100">
        <template #default="{ row }">
          <span>{{ row.deviceCount || 0 }}台</span>
        </template>
      </el-table-column>
      <el-table-column label="分账比例" width="100">
        <template #default="{ row }">
          <span>{{ (row.shareRate * 100).toFixed(1) }}%</span>
        </template>
      </el-table-column>
      <el-table-column label="累计收入" width="120">
        <template #default="{ row }">
          <span>￥{{ (row.totalIncome / 100).toFixed(2) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.status === 'active' ? 'success' : 'danger'">
            {{ row.status === 'active' ? '正常' : '冻结' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="handleView(row)">查看</el-button>
          <el-button size="small" type="primary" @click="handleEdit(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
    <el-pagination
      v-model:current-page="pagination.page"
      v-model:page-size="pagination.size"
      :total="pagination.total"
      layout="total, sizes, prev, pager, next"
      @change="loadFranchisees"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { api } from '@/api/client';

const searchForm = ref({
  keyword: '',
  status: ''
});

const franchisees = ref<any[]>([]);
const pagination = ref({
  page: 1,
  size: 20,
  total: 0
});

onMounted(() => loadFranchisees());

async function loadFranchisees() {
  try {
    const params = new URLSearchParams();
    if (searchForm.value.keyword) params.append('keyword', searchForm.value.keyword);
    if (searchForm.value.status) params.append('status', searchForm.value.status);
    params.append('page', String(pagination.value.page - 1));
    params.append('size', String(pagination.value.size));
    const res = await api.request<any>(`/api/v2/admin/franchisees?${params.toString()}`, 'GET');
    franchisees.value = res?.items ?? [];
    pagination.value.total = res?.total ?? 0;
  } catch (error) {
    console.error('加载加盟商列表失败', error);
  }
}

function handleAdd() {
  // 跳转到新增页面
}

function handleView(row: any) {
  // 跳转到详情页面
}

function handleEdit(row: any) {
  // 跳转到编辑页面
}
</script>

<style scoped>
.franchise-page {
  padding: 20px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.page-header h2 {
  margin: 0;
  font-size: 20px;
}

.search-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
}

.el-pagination {
  margin-top: 20px;
  justify-content: flex-end;
}
</style>
