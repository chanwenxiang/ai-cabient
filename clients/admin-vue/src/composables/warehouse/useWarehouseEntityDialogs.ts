import { computed, reactive, ref, type ComputedRef, type Ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { yuanToCents } from '@/utils/display';
import { errorMessage } from '@/utils/error-message';
import type { AdminDynamicRow } from '@/types/admin-dynamic-row';
import type { PageResult } from '@aicabinet/shared-types';

/** 仓储动态行（D15：禁止散落 Record<string, any>） */
export type WarehouseEntityRow = AdminDynamicRow;

export type UseWarehouseEntityDialogsDeps = {
  saving: Ref<boolean>;
  dialogBootLoading: Ref<boolean>;
  tab: Ref<string>;
  loadedTabs: Ref<Set<string>>;
  loadTab: (name: string, force?: boolean) => Promise<void>;
  filterWarehouseId: Ref<string>;
  activeWarehouses: ComputedRef<WarehouseEntityRow[]>;
  skus: Ref<WarehouseEntityRow[]>;
  loadWarehousesSoft: () => Promise<void>;
  ensureMeta: () => Promise<void>;
};

function localDate() {
  const now = new Date();
  return new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
}

/**
 * 主数据与杂项写路径：仓库 / 供应商 CRUD、应付付款、其他入库。
 * 列表加载与筛选项仍留在 WarehouseView。
 */
export function useWarehouseEntityDialogs(deps: UseWarehouseEntityDialogsDeps) {
  const warehouseDialog = ref(false);
  const supplierDialog = ref(false);
  const paymentDialog = ref(false);
  const inboundDialog = ref(false);

  const warehouseForm = reactive({
    editing: false,
    warehouseId: '',
    warehouseName: '',
    address: '',
    status: 'ACTIVE',
    managerUserId: null as number | null
  });
  const managerOptions = ref<{ userId: number; name?: string; phoneNumber?: string; status?: string }[]>(
    []
  );
  const managerLoading = ref(false);
  const supplierForm = reactive({
    editing: false,
    supplierId: '',
    supplierName: '',
    contactName: '',
    contactPhone: '',
    paymentTermsDays: 30,
    creditLimitYuan: 0,
    status: 'ACTIVE'
  });
  const paymentForm = reactive<WarehouseEntityRow>({
    payableId: null,
    amountYuan: 0,
    notes: ''
  });
  const payTarget = ref<WarehouseEntityRow>({});
  const inboundForm = reactive<WarehouseEntityRow>({
    warehouseId: '',
    refNo: '',
    notes: '',
    lines: []
  });

  const payMaxYuan = computed(() => Number((payTarget.value.balanceCents || 0) / 100));

  function managerOptionLabel(op: { userId: number; name?: string; phoneNumber?: string }) {
    const name = (op.name || '').trim() || '未命名';
    const phone = (op.phoneNumber || '').trim();
    return phone ? `${name}（${phone}）` : `${name}（${op.userId}）`;
  }

  function warehouseManagerLabel(userId?: number | string | null) {
    if (userId == null || userId === '') return '未绑定';
    const id = Number(userId);
    if (!Number.isFinite(id) || id <= 0) return '未绑定';
    const op = managerOptions.value.find((item) => item.userId === id);
    if (op) return managerOptionLabel(op);
    return `用户 ${id}`;
  }

  async function loadManagerOptions() {
    if (managerLoading.value) return;
    managerLoading.value = true;
    try {
      const data = await api.request<PageResult<{ userId: number; name?: string; phoneNumber?: string; status?: string }>>(
        AdminEndpoints.rbacOperatorsPage(0, 200),
        'GET'
      );
      managerOptions.value = (data.items || []).filter((item) => !item.status || item.status === 'ACTIVE');
    } catch {
      /* 下拉空则仍可手看编号；保存不依赖名单 */
    } finally {
      managerLoading.value = false;
    }
  }

  void loadManagerOptions();

  function newInboundLine() {
    return {
      skuId: deps.skus.value[0]?.skuId || '',
      batchNo: '',
      productionDate: localDate(),
      expiryDate: '',
      quantity: 1
    };
  }

  function addInboundLine() {
    inboundForm.lines.push(newInboundLine());
  }

  function openWarehouse(row?: WarehouseEntityRow) {
    const rawManager = Number(row?.managerUserId);
    Object.assign(warehouseForm, {
      editing: !!row,
      warehouseId: row?.warehouseId || '',
      warehouseName: row?.warehouseName || '',
      address: row?.address || '',
      status: row?.status || 'ACTIVE',
      managerUserId: Number.isFinite(rawManager) && rawManager > 0 ? rawManager : null
    });
    warehouseDialog.value = true;
    void loadManagerOptions();
  }

  async function saveWarehouse() {
    if (!warehouseForm.warehouseName.trim()) {
      return ElMessage.warning('请填写仓库名称');
    }
    if (warehouseForm.editing && !warehouseForm.warehouseId.trim()) {
      return ElMessage.warning('缺少仓库编号');
    }
    deps.saving.value = true;
    try {
      const idPath = warehouseForm.editing ? warehouseForm.warehouseId.trim() : 'new';
      await api.request(AdminEndpoints.warehouseItem(idPath), 'PUT', {
        warehouseName: warehouseForm.warehouseName.trim(),
        address: warehouseForm.address,
        status: warehouseForm.status,
        managerUserId: Number(warehouseForm.managerUserId) > 0 ? Number(warehouseForm.managerUserId) : 0
      });
      warehouseDialog.value = false;
      ElMessage.success(warehouseForm.editing ? '仓库已保存' : '仓库已创建（编号由系统分配）');
      deps.loadedTabs.value.delete('warehouses');
      await deps.loadTab('warehouses', true);
    } catch (e) {
      ElMessage.error(errorMessage(e, '保存失败'));
    } finally {
      deps.saving.value = false;
    }
  }

  function openSupplier(row?: WarehouseEntityRow) {
    Object.assign(supplierForm, {
      editing: !!row,
      supplierId: row?.supplierId || '',
      supplierName: row?.supplierName || '',
      contactName: row?.contactName || '',
      contactPhone: row?.contactPhone || '',
      paymentTermsDays: row?.paymentTermsDays || 30,
      creditLimitYuan: row?.creditLimitCents == null ? 0 : Number(row.creditLimitCents) / 100,
      status: row?.status || 'ACTIVE'
    });
    supplierDialog.value = true;
  }

  async function saveSupplier() {
    if (!supplierForm.supplierName.trim()) {
      return ElMessage.warning('请填写供应商名称');
    }
    if (supplierForm.editing && !supplierForm.supplierId.trim()) {
      return ElMessage.warning('缺少供应商编号');
    }
    deps.saving.value = true;
    try {
      const idPath = supplierForm.editing ? supplierForm.supplierId.trim() : 'new';
      await api.request(AdminEndpoints.supplier(idPath), 'PUT', {
        supplierId: idPath,
        supplierName: supplierForm.supplierName.trim(),
        contactName: supplierForm.contactName,
        contactPhone: supplierForm.contactPhone,
        paymentTermsDays: Number(supplierForm.paymentTermsDays) || 30,
        creditLimitCents: yuanToCents(supplierForm.creditLimitYuan) ?? 0,
        status: supplierForm.status
      });
      supplierDialog.value = false;
      ElMessage.success(supplierForm.editing ? '供应商已保存' : '供应商已创建（编号由系统分配）');
      deps.loadedTabs.value.delete('suppliers');
      await deps.loadTab('suppliers', true);
    } catch (e) {
      ElMessage.error(errorMessage(e, '保存失败'));
    } finally {
      deps.saving.value = false;
    }
  }

  function openPay(row: WarehouseEntityRow) {
    // P2：整行替换而非合并——Object.assign 会残留上一行字段造成串显
    payTarget.value = { ...row };
    paymentForm.payableId = row.payableId;
    paymentForm.amountYuan = Number((Number(row.balanceCents) || 0) / 100);
    paymentForm.notes = '';
    paymentDialog.value = true;
  }

  async function savePayment() {
    if (!paymentForm.payableId) return;
    const amountCents = yuanToCents(paymentForm.amountYuan);
    if (amountCents == null || amountCents <= 0) return ElMessage.warning('请输入付款金额');
    deps.saving.value = true;
    try {
      await api.request(AdminEndpoints.suppliersPayablePay(paymentForm.payableId), 'POST', {
        amountCents,
        notes: paymentForm.notes
      });
      paymentDialog.value = false;
      ElMessage.success('付款登记成功');
      deps.loadedTabs.value.delete('payables');
      await deps.loadTab('payables', true);
    } catch (e) {
      ElMessage.error(errorMessage(e, '付款登记失败'));
    } finally {
      deps.saving.value = false;
    }
  }

  async function removeInboundLine(index: number) {
    if (inboundForm.lines.length <= 1) return;
    try {
      await ElMessageBox.confirm('确定删除该入库行吗？', '删除行', {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        appendTo: document.body
      });
    } catch {
      return;
    }
    inboundForm.lines.splice(index, 1);
  }

  async function openInbound() {
    Object.assign(inboundForm, {
      warehouseId: deps.filterWarehouseId.value || '',
      refNo: '',
      notes: '',
      lines: [newInboundLine()]
    });
    inboundDialog.value = true;
    deps.dialogBootLoading.value = true;
    try {
      await Promise.all([deps.loadWarehousesSoft(), deps.ensureMeta()]);
      if (!inboundForm.warehouseId) {
        inboundForm.warehouseId = deps.activeWarehouses.value[0]?.warehouseId || '';
      }
    } finally {
      deps.dialogBootLoading.value = false;
    }
  }

  async function saveInbound() {
    if (
      !inboundForm.warehouseId ||
      inboundForm.lines.some(
        (l: WarehouseEntityRow) => !l.skuId || !l.batchNo || !l.expiryDate || !l.quantity
      )
    ) {
      return ElMessage.warning('请完整填写仓库、商品、批次、到期日和数量');
    }
    deps.saving.value = true;
    try {
      await api.request(AdminEndpoints.warehouseInbound, 'POST', {
        warehouseId: inboundForm.warehouseId,
        refNo: inboundForm.refNo,
        notes: inboundForm.notes,
        lines: inboundForm.lines
      });
      inboundDialog.value = false;
      ElMessage.success('入库完成');
      deps.loadedTabs.value.delete('inventory');
      deps.loadedTabs.value.delete('movements');
      deps.tab.value = 'inventory';
      await deps.loadTab('inventory', true);
    } catch (e) {
      ElMessage.error(errorMessage(e, '入库失败'));
    } finally {
      deps.saving.value = false;
    }
  }

  return {
    warehouseDialog,
    supplierDialog,
    paymentDialog,
    inboundDialog,
    warehouseForm,
    managerOptions,
    managerLoading,
    managerOptionLabel,
    warehouseManagerLabel,
    supplierForm,
    paymentForm,
    payTarget,
    inboundForm,
    payMaxYuan,
    openWarehouse,
    saveWarehouse,
    openSupplier,
    saveSupplier,
    openPay,
    savePayment,
    addInboundLine,
    removeInboundLine,
    openInbound,
    saveInbound
  };
}
