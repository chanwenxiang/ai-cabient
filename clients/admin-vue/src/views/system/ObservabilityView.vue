<template>
  <div class="observability">
    <div class="obs-header">
      <div>
        <h2>日志中心</h2>
        <p class="hint">
          全栈容器日志与链路追踪（Grafana · Loki / Tempo）。日志按主题拆成 5
          个页面，切上面的页签即可；看板与「DevOps 中心」共用同一个 Grafana
          实例，这里只是把它嵌到系统模块里直达。
        </p>
      </div>
      <div class="obs-actions">
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新状态</el-button>
        <el-button v-if="grafanaOnline" type="primary" link @click="openInNewTab">
          新窗口打开
        </el-button>
      </div>
    </div>

    <el-alert
      v-if="!loading && !grafanaOnline"
      class="obs-alert"
      type="warning"
      :closable="false"
      show-icon
      title="Grafana 未启动，无法嵌入看板"
      description="Grafana 随全栈启动（.\docker-up.ps1）。要看日志还需 Loki/promtail/tempo 在跑：infra\observability.ps1 on"
    />

    <el-card class="obs-panel" shadow="never">
      <template #header>
        <div class="obs-panel-head">
          <el-radio-group v-model="activeBoard" size="small">
            <el-radio-button v-for="b in boards" :key="b.key" :value="b.key">
              {{ b.title }}
            </el-radio-button>
          </el-radio-group>
          <span class="hint">{{ activeBoardMeta.hint }}</span>
        </div>
      </template>
      <div ref="frameWrap" class="obs-frame-wrap">
        <template v-if="grafanaOnline">
          <iframe
            v-for="b in boards"
            v-show="activeBoard === b.key"
            :key="b.key"
            :src="b.kioskUrl"
            :title="b.title"
            class="obs-frame"
            scrolling="no"
            referrerpolicy="no-referrer"
            @load="fitFrames"
          />
        </template>
        <el-empty v-else description="Grafana 未启动，无法嵌入看板" />
      </div>
    </el-card>

    <el-card class="obs-tips" shadow="never">
      <template #header>
        <div class="obs-panel-head">
          <span>看日志的其它入口</span>
          <span class="hint">完整用法与 6 个必知限制见 docs/OBSERVABILITY_USAGE.md</span>
        </div>
      </template>
      <ul class="obs-tip-list">
        <li>
          <b>终端跟日志</b>：<code>docker logs -f ai-cabinet-trade-service-1 --tail 100</code>
        </li>
        <li>
          <b>Grafana Explore</b>：在 Grafana 里直接写 LogQL，例如
          <code>{service=&quot;trade-service&quot;} |= &quot;412&quot;</code>
        </li>
        <li>
          <b>Loki API</b>：<code
            >curl --noproxy "*" -G http://127.0.0.1:13100/loki/api/v1/query_range --data-urlencode
            'query={service="trade-service"}'</code
          >
        </li>
      </ul>
      <p class="hint obs-warn">
        两个最容易踩的：promtail 只保留最近 10 分钟（停机超 10 分钟期间的日志永久丢失）； 日志里有
        traceId ≠ 该链路已上报 Tempo（采样概率默认 0.1）。
      </p>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { api } from '@/api/client';

import { AdminEndpoints } from '@/api/endpoints';

interface DevOpsTool {
  id: string;
  name: string;
  url: string;
  online: boolean;
}

interface DevOpsHub {
  tools: DevOpsTool[];
  githubUrl: string;
  grafanaEmbedPath?: string | null;
}

interface Board {
  key: string;
  title: string;
  hint: string;
  /** kiosk（纯净）视图，用于 iframe 嵌入 */
  kioskUrl: string;
  /** 完整 Grafana 视图，用于新窗口打开 */
  viewUrl: string;
}

const hub = ref<DevOpsHub | null>(null);
const loading = ref(false);
const activeBoard = ref('stream');

const grafanaTool = computed(() => hub.value?.tools.find((t) => t.id === 'grafana'));
const grafanaOnline = computed(() => grafanaTool.value?.online ?? false);

/**
 * 只取后端下发 URL 的 path 部分（如 /devops/grafana）再拼看板路径。
 * 不硬编码 host：绝对 URL 里的 localhost 换成别的访问地址就会指向错误的主机，
 * 而同源相对路径在任何访问入口下都成立。
 */
const grafanaPath = computed(() => {
  const raw = grafanaTool.value?.url || '';
  try {
    return new URL(raw).pathname.replace(/\/$/, '') || '/devops/grafana';
  } catch {
    return '/devops/grafana';
  }
});

const boards = computed<Board[]>(() => {
  const base = grafanaPath.value;
  const query = 'orgId=1&kiosk=tv&theme=light';
  const page = (key: string, uid: string, title: string, hint: string): Board => ({
    key,
    title,
    hint,
    kioskUrl: `${base}/d/${uid}/?${query}`,
    viewUrl: `${base}/d/${uid}/?orgId=1&theme=light`
  });
  return [
    page(
      'stream',
      'ai-cabinet-logs-stream',
      '全栈日志流',
      '原始日志，不预过滤：服务可多选，关键词可做包含过滤'
    ),
    page(
      'errors',
      'ai-cabinet-logs-errors',
      '错误与告警',
      '只看 error / warn / exception / fail 的行 —— 排查故障先看这页'
    ),
    page(
      'rate',
      'ai-cabinet-logs-rate',
      '日志速率',
      '每服务日志行速率（行/秒）：突增＝刷屏或重试风暴，骤降＝服务卡住'
    ),
    page(
      'errorcount',
      'ai-cabinet-logs-errorcount',
      'ERROR 计数',
      '5 分钟内 ERROR 行数（⚠️ 日志计数，不是业务失败率）'
    ),
    page(
      'trace',
      'ai-cabinet-logs-trace',
      '一次调用追踪',
      '粘一个 traceId，把这次调用的日志按时间串起来'
    ),
    page(
      'overview',
      'ai-cabinet-overview',
      '运营概览',
      'Prometheus 指标：开门成功率、设备在线、对账 MISMATCH、MQTT 链路等'
    )
  ];
});

const activeBoardMeta = computed(
  () => boards.value.find((b) => b.key === activeBoard.value) ?? boards.value[0]
);

async function load() {
  loading.value = true;
  try {
    hub.value = await api.request<DevOpsHub>(AdminEndpoints.devopsHub, 'GET');
  } finally {
    loading.value = false;
  }
}

function openInNewTab() {
  window.open(activeBoardMeta.value.viewUrl, '_blank', 'noopener,noreferrer');
}

/**
 * 消除 iframe 内滚动条：后台页与 Grafana 都经同一个 gateway（同源），
 * 可读 iframe 文档里的真实内容高度，把 iframe 撑到与内容齐平 —— 只留外层页面一条滚动条。
 *
 * ⚠️ 不能量 `documentElement.scrollHeight`：Grafana 给 html/body/main 都设了 `height:100%`，
 * 这个值恒等于 iframe 自身高度（实测视口 640 时内容实际需要 960，它仍返回 640），
 * 照它设高就是原样保持、内部照旧滚。必须量**面板元素的几何底边**。
 */
function measureContentHeight(doc: Document): number {
  const scrolled = doc.scrollingElement?.scrollTop ?? 0;
  let bottom = 0;
  doc.querySelectorAll<HTMLElement>('[data-panelid]').forEach((el) => {
    const rect = el.getBoundingClientRect();
    if (rect.height <= 0) return;
    bottom = Math.max(bottom, rect.bottom + scrolled);
  });
  // 面板下方留 16px 呼吸位；仪表盘没有面板（加载中）时返回 0 表示「这次不调」
  return bottom > 0 ? Math.ceil(bottom + 16) : 0;
}

function fitFrames() {
  document.querySelectorAll<HTMLIFrameElement>('.obs-frame').forEach((frame) => {
    if (frame.offsetWidth === 0) return; // v-show 隐藏的页签不参与测量
    let height = 0;
    try {
      const doc = frame.contentDocument;
      if (!doc || !doc.documentElement) throw new Error('no doc');
      height = measureContentHeight(doc);
    } catch {
      /* 跨源（如绕过 gateway 直连容器端口）取不到文档 ⇒ 退回可滚动 */
      frame.setAttribute('scrolling', 'auto');
      return;
    }
    frame.setAttribute('scrolling', 'no');
    if (height < 200) return;
    // 只在真的有差异时改，避免每次轮询都触发重排
    const current = parseFloat(frame.style.height || '0');
    if (Math.abs(current - height) > 4) frame.style.height = `${height}px`;
  });
}

let fitTimer: ReturnType<typeof setInterval> | undefined;

watch(activeBoard, () => {
  nextTick(fitFrames);
});

onMounted(() => {
  load();
  // Grafana 异步加载面板数据，内容高度会变化：轮询兜底，直到卸载
  fitTimer = setInterval(fitFrames, 1500);
});

onBeforeUnmount(() => {
  if (fitTimer) clearInterval(fitTimer);
});
</script>

<style scoped>
.observability {
  padding: 16px;
}

.obs-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.obs-header h2 {
  margin: 0 0 4px;
}

.obs-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.obs-alert {
  margin-bottom: 12px;
}

.obs-panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

/* 日志按主题拆成 5 页 + 运营概览，共 6 个页签：窄屏时允许换行，不要横向溢出 */
.obs-panel-head :deep(.el-radio-group) {
  flex-wrap: wrap;
  row-gap: 6px;
}

.obs-frame-wrap {
  /* 不再当「框」：背景透明、不裁剪，高度由 fitFrames 撑到 iframe 内容高度 */
  min-height: 320px;
}

/* iframe 与卡片边缘齐平，视觉上「长」在页面里而不是嵌在一个盒子里 */
.obs-panel :deep(.el-card__body) {
  padding: 0;
}

.obs-frame {
  width: 100%;
  height: 640px; /* 兜底：同源测量不可用时使用 */
  border: 0;
  display: block;
  overflow: hidden;
}

.obs-tips {
  margin-top: 16px;
}

.obs-tip-list {
  margin: 0;
  padding-left: 18px;
  line-height: 1.9;
}

.obs-tip-list code {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--el-fill-color-light);
  font-size: var(--admin-font-size-table);
}

.obs-warn {
  margin-bottom: 0;
}
</style>
