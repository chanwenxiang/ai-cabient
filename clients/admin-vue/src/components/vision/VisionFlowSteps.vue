<template>
  <div class="flow-steps">
    <el-steps :active="active" align-center finish-status="success" class="steps-bar">
      <el-step
        v-for="(step, i) in steps"
        :key="step.title"
        :title="step.title"
        :description="descriptions[i]"
        :status="stepStatus(i)"
        class="step"
        @click="go(i)"
      />
    </el-steps>
    <el-alert
      v-if="summary?.vendorError"
      class="flow-error"
      type="warning"
      :closable="false"
      show-icon
      :title="`流程数字面板部分降级：${summary.vendorError}`"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { errorMessage } from '@/utils/error-message';

/**
 * 识别链路四步导航条（旧学习链退场后）：商品挂接 → 商品采集（手机/柜内）→ 映射下发 → 整单上架。
 * 数字来自 GET /jiangyi/flow-summary（本地读面）；点击整步跳转到该步的主操作页。
 * 手机采集在商户小程序 jiangyi-capture 页、柜内采集在设备将邑卡面板；旧「柜前采集/厂商审核/
 * 提交学习/学习库上架」步骤随 _AI-OK 旧柜退场，不再保留入口。
 * active = 当前页主责的步骤下标；调用方各自传入。
 */

interface FlowSummary {
  linkedSkus: number;
  boundCabinets: number;
  verifiedCabinets: number;
  activeMappings: number;
  pendingGather: number | null;
  vendorError: string | null;
}

const props = defineProps<{ active: number; deviceId?: string }>();

const router = useRouter();
const summary = ref<FlowSummary | null>(null);
const loading = ref(false);

const steps = [
  { title: '商品挂接', route: '/skus' },
  { title: '商品采集', route: '/jiangyi-products' },
  { title: '映射复核', route: '/vision-mappings' },
  { title: '补货上架', route: '/devices' }
];

const descriptions = computed(() => {
  const s = summary.value;
  const q = (v: number | null | undefined) => (v === null || v === undefined ? '？' : String(v));
  return [
    s ? `已挂 ${q(s.linkedSkus)} 个 SKU` : '',
    s ? `柜机 ${q(s.boundCabinets)} 台 · 待采/审核 ${q(s.pendingGather)}` : '',
    s ? `未复核柜机 ${q(s.boundCabinets - s.verifiedCabinets)}` : '',
    s ? `在架类别 ${q(s.activeMappings)}` : ''
  ];
});

/**
 * 步骤圆点状态按真实数据来（未复核=红叉，进行中=蓝，达成=绿对号），
 * 不按「当前页位置」打勾——位置打勾会让卡住的步骤也显示成功。
 */
function stepStatus(i: number) {
  const s = summary.value;
  if (!s) return i === props.active ? 'process' : 'wait';
  switch (i) {
    case 0:
      return s.linkedSkus > 0 ? 'finish' : 'wait';
    case 1:
      return (s.pendingGather ?? 0) > 0 ? 'process' : 'wait';
    case 2: {
      // 有已复核柜机、只是个别测试柜没复核 → 蓝色进行中（数量在描述里看）；
      // 全部未复核才算卡住（红叉）
      if (s.verifiedCabinets > 0) return 'finish';
      return s.boundCabinets > 0 ? 'error' : 'wait';
    }
    default:
      return s.activeMappings > 0 ? 'finish' : 'wait';
  }
}

function go(i: number) {
  // 补货上架是「 per 柜」动作：已选柜机时直达该柜详情（将邑卡→同步上架到将邑），
  // 否则才回设备管理列表挑柜，避免点「上架」却落在无关的设备列表。
  const route = i === 3 && props.deviceId ? `/devices/${props.deviceId}` : steps[i].route;
  void router.push(route);
}

async function load() {
  loading.value = true;
  try {
    summary.value = await api.request<FlowSummary>(AdminEndpoints.jiangyiFlowSummary, 'GET');
  } catch (e) {
    ElMessage.error(errorMessage(e, '读取流程数字失败'));
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  void load();
});

let activatedOnce = false;
onActivated(() => {
  if (!activatedOnce) {
    activatedOnce = true;
    return;
  }
  void load();
});
</script>

<style scoped>
.flow-steps {
  margin: 4px 0 8px;
}
.steps-bar :deep(.el-step) {
  cursor: pointer;
}
.steps-bar :deep(.el-step__title) {
  font-size: 13px;
}
.steps-bar :deep(.el-step__description) {
  font-size: 12px;
}
.flow-error {
  margin-top: 8px;
}
</style>
