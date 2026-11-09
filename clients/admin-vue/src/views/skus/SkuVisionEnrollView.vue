<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title"
              >识别入驻<HelpTip
                content="识别能力在将邑端侧：先查将邑已有模型库，有现成模型的商品直接走「模型同步 → 映射 → 下发」（本页第④⑤步），无需采集。只有将邑库里没有的新商品才需要采集——手机拍照采集在商户小程序「商品采集」页完成，柜内采集在运营后台设备将邑卡「柜内采集」面板发起；采集提交后由将邑审图，全程无需人工上传图片。管理后台上传的商品图片仅用于展示，不参与识别训练。"
                aria-label="采集说明"
            /></span>
            <span class="hint">
              识别能力在将邑端侧（云端不做识别）；让柜机「认识」一件商品 = 挂接 + 采集（手机/柜内）+
              映射下发
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
        content="采集提交后由将邑异步审图，驳回原因在「将邑商品」页按条码查询；商品状态就绪后在设备将邑卡「整单上架」铺货，并在「识别映射」页核对 classId ↔ SKU 对照并激活；激活错误的映射会直接导致错误扣款，请逐行核对后再激活。"
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
    title: '① 商品挂接',
    desc: '把我们的 SKU 与将邑商品库商品关联（或将我们的商品新增到将邑）。',
    points: [
      '入口：商品管理 → 行操作「将邑挂接」',
      '一个将邑商品至多挂一个 SKU；条码不一致会被拒绝',
      '也可直接在弹窗里把我们的商品新增到将邑商品库'
    ],
    status: 'finish',
    action: { label: '去商品管理', path: '/skus', primary: true }
  },
  {
    title: '② 商品采集（仅将邑库中没有的商品）',
    desc: '手机拍照采集在商户小程序「商品采集」页完成；柜内采集在运营后台设备将邑卡「柜内采集」面板发起。采集提交后由将邑审图，状态与驳回原因在「将邑商品」页按条码查询。',
    points: [
      '手机采集：商户小程序 → 商品采集（jiangyi-capture），按条码拍照提交',
      '柜内采集：设备详情 → 将邑卡「柜内采集」面板，先核对厂商在售清单再发起',
      '后台「将邑商品」页可建档、批量刷新状态、查看驳回原因；旧「采集台/触发学习」已退场'
    ],
    status: 'process',
    action: { label: '去将邑商品', path: '/jiangyi-products', primary: true }
  },
  {
    title: '③ 回填 textName',
    desc: '商品就绪后按条码从将邑回填 textName（classes.txt 里的识别键）。',
    points: ['入口：商品管理 → 将邑挂接弹窗 →「回填 textName」'],
    status: 'process',
    action: { label: '去商品管理', path: '/skus' }
  },
  {
    title: '④ 预生成并激活映射',
    desc: '拉取模型列表 → 预生成 classId ↔ SKU 映射（停用态）→ 逐行核对后激活。',
    points: [
      '将邑库里已有现成模型的商品走本步即可，无需采集',
      'classId 行号方向（0/1 起）可用基数参数切换后重新预生成',
      '错位映射一旦激活会直接错误扣款，务必逐行确认'
    ],
    status: 'process',
    action: { label: '去识别映射', path: '/vision-mappings', primary: true }
  },
  {
    title: '⑤ 下发模型',
    desc: '激活映射后把模型下发到柜机，柜机应用后回执确认。',
    points: [
      '设备登记机型须与模型机型一致（字符串校验）',
      '下发后 600s 未收到回执自动置失败，可重新下发'
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
