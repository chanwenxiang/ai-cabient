<template>
  <div class="franchise-page">
    <div class="page-header">
      <h2>�����̹���</h2>
      <div class="actions">
        <el-button @click="onExport">����</el-button>
        <el-button type="primary" @click="handleAdd">����������</el-button>
      </div>
    </div>

    <div class="search-bar">
      <el-input v-model="searchForm.keyword" placeholder="����������/��ϵ��" style="width: 200px" />
      <el-select v-model="searchForm.status" placeholder="״̬" clearable style="width: 120px">
        <el-option label="ȫ��" value="" />
        <el-option label="����" value="active" />
        <el-option label="����" value="frozen" />
      </el-select>
      <el-button type="primary" @click="loadFranchisees">��ѯ</el-button>
    </div>

    <div class="table-scroll">
      <div class="table-scroll-inner" style="min-width: 1030px">
        <el-table :data="franchisees" stripe border>
          <el-table-column prop="id" label="ID" width="80" />
          <el-table-column prop="name" label="����������" min-width="150" />
          <el-table-column prop="contactPerson" label="��ϵ��" width="100" />
          <el-table-column prop="contactPhone" label="��ϵ�绰" width="120" />
          <el-table-column label="�豸����" width="100">
            <template #default="{ row }">
              <span>{{ row.deviceCount || 0 }}̨</span>
            </template>
          </el-table-column>
          <el-table-column label="���˱���" width="100">
            <template #default="{ row }">
              <span>{{ (row.shareRate * 100).toFixed(1) }}%</span>
            </template>
          </el-table-column>
          <el-table-column label="�ۼ�����" width="120">
            <template #default="{ row }">
              <span>?{{ (row.totalIncome / 100).toFixed(2) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="״̬" width="80">
            <template #default="{ row }">
              <el-tag :type="row.status === 'active' ? 'success' : 'danger'">
                {{ row.status === 'active' ? '����' : '����' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="����" width="180" class-name="col-action" align="center">
            <template #default="{ row }">
              <el-button size="small" @click="handleView(row)">�鿴</el-button>
              <el-button size="small" type="primary" @click="handleEdit(row)">�༭</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

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
import { useListCsv } from '@/composables/useListCsv';

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

const { onExport } = useListCsv({
  filePrefix: '������',
  headers: ['ID', '����������', '��ϵ��', '��ϵ�绰', '�豸����', '���˱���', '�ۼ�����', '״̬'],
  toRows: () =>
    franchisees.value.map((row) => [
      row.id,
      row.name,
      row.contactPerson,
      row.contactPhone,
      row.deviceCount || 0,
      `${((row.shareRate || 0) * 100).toFixed(1)}%`,
      ((row.totalIncome || 0) / 100).toFixed(2),
      row.status === 'active' ? '����' : '����'
    ])
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
    console.error('���ؼ������б�ʧ��', error);
  }
}

function handleAdd() {
  // ��ת����ҳ��
}

function handleView(row: any) {
  // ��ת����ҳ��
}

function handleEdit(row: any) {
  // ��ת�༭ҳ��
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
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 20px;
}

.page-header h2 {
  margin: 0;
  font-size: 20px;
}

.actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.search-bar {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 20px;
}

.el-pagination {
  margin-top: 20px;
  justify-content: flex-end;
}
</style>
