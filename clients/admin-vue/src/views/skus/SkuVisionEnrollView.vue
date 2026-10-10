<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">识别入驻</span>
            <span class="hint">
              识别能力在将邑端侧（云端不做识别）；让柜机「认识」一件商品 = 挂接 + 采集 + 学习出模型
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

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="不是每个商品都要采集"
      description="识别能力在将邑端侧：先查将邑已有模型库，有现成模型的商品直接走「模型同步 → 映射 → 下发」（本页第⑤⑥步），无需采集。只有将邑库里没有的新商品才需要柜内采集学习——把实物放进柜内，由柜机内置摄像头自动拍摄（约 2000 张/商品），全程无需人工上传图片；管理后台上传的商品图片仅用于展示，不参与识别训练。"
    />

    <el-steps class="jy-steps" direction="vertical" :active="6" space="72px">
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

    <el-alert
      class="jy-faq"
      type="warning"
      :closable="false"
      show-icon
      title="常见问题"
      description="采集期间柜机停止营业（顾客开门会提示「设备商品采集中」）；学习触发后由将邑云端异步完成，完成后在「识别映射」页核对 classId ↔ SKU 对照并激活；激活错误的映射会直接导致错误扣款，请逐行核对后再激活。"
    />
  </el-card>
</template>

<script setup lang="ts">
import { useNavAccess } from '@/composables/useNavAccess';

/**
 * 识别入驻（CB-023 改造）：云端识别方案废弃后，本页改为将邑识别体系入驻指引。
 * 管理动作分布在商品管理（挂接）、设备详情（采集编排/下发）、识别映射（映射管理），
 * 本页负责讲清链路与跳转，不重复承载管理功能。
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
    title: '② 柜内采集（仅将邑库中没有的商品）',
    desc: '先在设备详情进入采集模式：柜机锁定停止营业后放入实物，由柜机内置摄像头自动拍摄（约 2000 张/商品），无需人工上传图片。',
    points: [
      '采集模式期间营业开门直接 409「设备商品采集中」，顾客无法购物；退出采集模式即恢复营业',
      '采集批次操作在将邑商户 App 完成；进度与退出入口：设备详情 →「将邑接入」卡片'
    ],
    status: 'process',
    action: { label: '设备详情', path: '/devices', hint: '在设备列表打开对应柜机' }
  },
  {
    title: '③ 触发学习',
    desc: '采集完成后在设备详情的采集编排区块触发学习（将邑云端异步训练）。',
    points: [
      '学习范围 = 将邑侧已采集的全部商品（集合粒度）',
      '学习完成回执经 finishNotify 自动回填，已做防伪造校验'
    ],
    status: 'process'
  },
  {
    title: '④ 回填 textName',
    desc: '学习完成后按条码从将邑回填 textName（classes.txt 里的识别键）。',
    points: ['入口：商品管理 → 将邑挂接弹窗 →「回填 textName」'],
    status: 'process',
    action: { label: '去商品管理', path: '/skus' }
  },
  {
    title: '⑤ 预生成并激活映射',
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
    title: '⑥ 下发模型',
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
.jy-faq {
  margin-top: 8px;
}
</style>
