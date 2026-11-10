<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title"
              >识别入驻<HelpTip
                content="识别能力在将邑端侧。上新=挂接/建档+手机采集审核（商户小程序拍照，将邑审图）；上架=补货上架（设备将邑卡「同步上架到将邑」）+柜内采集审核（上架后在设备将邑卡发起）。命中标准库已有采集可免拍。结算主路径=thirdPid 挂接（识别上报的商品 ID→SKU），classId 映射仅兜底；「识别映射→复核」是结算闸口。管理后台上传的商品图仅展示，不参与识别训练。"
                aria-label="采集说明"
            /></span>
            <span class="hint">
              让柜机「认识并卖」一件商品 = 上新（挂接/建档 + 手机采集审核）+ 上架（补货上架 +
              柜内采集审核）+ 识别结算确认（thirdPid 挂接 + 复核）。识别在将邑端侧，云端不做识别
            </span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-if="canAccessPath('/skus')" @click="goPath('/skus')">商品管理</el-button>
          <el-button
            v-if="canAccessPath('/vision-mappings')"
            type="primary"
            @click="goPath('/vision-mappings')"
            >识别映射</el-button
          >
        </div>
      </div>
    </template>

    <VisionFlowSteps :active="1" />

    <el-steps class="jy-steps" direction="vertical" :active="5" space="72px">
      <el-step v-for="step in steps" :key="step.title" :title="step.title" :status="step.status">
        <template #description>
          <div class="jy-step-body">
            <div class="jy-step-desc">{{ step.desc }}</div>
            <ul class="jy-step-points">
              <li v-for="p in step.points" :key="p">{{ p }}</li>
            </ul>
            <div v-if="step.action" class="jy-step-action">
              <el-button
                v-if="step.action.path"
                size="small"
                :type="step.action.primary ? 'primary' : 'default'"
                @click="goPath(step.action.path)"
              >
                {{ step.action.label }}
              </el-button>
              <span v-if="step.action.hint" class="jy-step-hint">{{ step.action.hint }}</span>
            </div>
          </div>
        </template>
      </el-step>
    </el-steps>

    <div class="jy-faq-help">
      <span>常见问题</span>
      <HelpTip
        content="采集提交后由将邑异步审图，驳回原因在「将邑商品」页按条码查询。上新（认识商品）与上架（铺货进柜）是两步：先采集审核就绪，再补货上架+柜内采集。结算主路径=thirdPid 挂接（识别上报商品 ID→SKU），不受重训平移；classId 预生成/激活仅兜底。「识别映射→复核（结算闸口）」未通过会暂停结算，重训后需重新复核。"
        aria-label="常见问题"
      />
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { useNavAccess } from '@/composables/useNavAccess';
import VisionFlowSteps from '@/components/vision/VisionFlowSteps.vue';
import HelpTip from '@/components/HelpTip.vue';

/**
 * 识别入驻（CB-023 改造）：云端识别方案废弃后，本页改为将邑识别体系入驻指引。
 * 管理动作分布在商品管理（挂接）、将邑商品（建档/状态/驳回原因）、识别映射（映射管理）；
 * 采集在商户小程序（手机拍照）与设备将邑卡（柜内采集）完成。本页负责讲清链路与跳转，
 * 不重复承载管理功能。旧学习链（采集模式/触发学习/学习库）已退场，不再保留入口。
 */

const { canAccessPath, goPath } = useNavAccess();

interface StepView {
  title: string;
  desc: string;
  points: string[];
  status: 'finish' | 'process' | 'wait';
  action?: { label: string; path?: string; hint?: string; primary?: boolean };
}

const steps: StepView[] = [
  {
    title: '① 商品挂接 / 建档（上新）',
    desc: '把我们的 SKU 与将邑商品关联。优先绑厂商标准品（识别上报的 thirdPid，结算直接命中、不受重训平移）；将邑库里没有的新商品才新增建档。',
    points: [
      '入口：商品管理 → 行操作「将邑挂接」；或「将邑商品」页建档',
      '挂接以设备识别上报的 thirdPid 为准（如冰红茶 4183），不要只按名称猜标准品',
      '一个将邑商品至多挂一个 SKU；换绑会自动退役同条码旧行'
    ],
    status: 'finish',
    action: { label: '去商品管理', path: '/skus', primary: true }
  },
  {
    title: '② 手机采集 + 审核（上新·仅库中没有的商品）',
    desc: '商户小程序「商品采集」页按条码拍照提交，由将邑审图；命中标准库已有采集的可免拍。审核通过才就绪，驳回原因在「将邑商品」页按条码查。',
    points: [
      '手机采集在商户小程序「商品采集」页，不在后台上传图片',
      '后台「将邑商品」页可建档、批量刷新状态、看驳回原因',
      '后台上传的商品图仅展示用，不参与识别训练'
    ],
    status: 'process',
    action: { label: '去将邑商品', path: '/jiangyi-products', primary: true }
  },
  {
    title: '③ 补货上架（上架·把货放进柜）',
    desc: '在设备将邑卡「同步上架到将邑」提交补货清单，厂商受理后货进柜在售。上架是「铺货」，与「上新（让厂商认识商品）」是两回事。',
    points: [
      '入口：设备详情 → 将邑卡 →「同步上架到将邑」',
      '需柜机在线；提交后等厂商受理',
      '上架之后才能做柜内采集（第④步）'
    ],
    status: 'process',
    action: { label: '去设备详情', path: '/devices', primary: true }
  },
  {
    title: '④ 柜内采集 + 审核（上架后）',
    desc: '上架后在设备将邑卡「柜内采集」面板发起，柜内拍照提交将邑审图；审核通过后商品转就绪。柜内采集不可替代手机采集。',
    points: [
      '入口：设备详情 → 将邑卡 →「柜内采集」',
      '流程：开锁 → 拿放/关门 → 上传 → 厂商审核 → 通过',
      '审核通过后在「将邑商品」页可见状态就绪'
    ],
    status: 'process',
    action: { label: '去设备详情', path: '/devices' }
  },
  {
    title: '⑤ 识别结算确认（让它能扣款）',
    desc: '确认识别挂接已生效，并在「识别映射 → 复核（结算闸口）」人工确认通过；之后开门拿货即自动识别结算。',
    points: [
      '主路径 = thirdPid 挂接；classId 预生成/映射下发仅为 thirdPid 未命中时的兜底',
      '复核未通过会暂停识别结算与在售面，重训换模型后需重新复核',
      '拿一件实测：识别命中 SKU、订单按实付扣款即链路通'
    ],
    status: 'wait',
    action: { label: '去识别映射', path: '/vision-mappings' }
  }
];
</script>

<style scoped>
.page-card-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  flex-wrap: wrap;
}
.page-card-head__meta {
  min-width: 0;
}
.page-card-head__title {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.title {
  font-weight: 600;
  font-size: var(--admin-font-size-title);
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: var(--admin-font-size-sm);
  line-height: 1.4;
}
.page-card-head__actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.jy-steps {
  margin-top: 20px;
}
.jy-step-body {
  padding-bottom: 8px;
}
.jy-step-desc {
  font-size: 13px;
  margin-bottom: 4px;
}
.jy-step-points {
  margin: 0;
  padding-left: 18px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.8;
}
.jy-step-action {
  margin-top: 6px;
  display: flex;
  align-items: center;
  gap: 10px;
}
.jy-step-hint {
  font-size: 12px;
  color: var(--el-text-color-tertiary);
}
.jy-faq-help {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 16px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
</style>
