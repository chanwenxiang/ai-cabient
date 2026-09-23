<template>
  <div class="map-page" :class="{ 'map-dark': isDark }" v-loading="loading">
    <div ref="mapEl" class="map-canvas" />

    <div class="map-float-bar">
      <el-checkbox v-model="onlineOnly" @change="applyFilters">在线</el-checkbox>
      <el-checkbox v-model="selfOperatedOnly" @change="applyFilters">自营</el-checkbox>
      <el-input
        v-model="machineNo"
        clearable
        placeholder="请输入机器编号…"
        class="float-input"
        @keyup.enter="applyFilters"
        @clear="applyFilters"
      >
        <template #prepend>机器编号</template>
      </el-input>
      <el-input
        v-model="areaKeyword"
        clearable
        placeholder="请输入地区…"
        class="float-input"
        @keyup.enter="applyFilters"
        @clear="applyFilters"
      >
        <template #prepend>地区</template>
      </el-input>
      <el-input
        v-model="keyword"
        clearable
        placeholder="请输入关键字…"
        class="float-input"
        @keyup.enter="applyFilters"
        @clear="applyFilters"
      >
        <template #prepend>关键字</template>
      </el-input>
      <el-select v-model="lifecycleStatus" style="width: 120px" @change="load">
        <el-option
          v-for="item in lifecycleOptions"
          :key="item.value"
          :label="item.label"
          :value="item.value"
        />
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </div>

    <div class="map-float-meta">
      <button
        type="button"
        class="map-count-btn"
        :aria-label="
          listHydrated ? `共 ${filteredPoints.length} 个柜机落点` : `柜机落点 ${UI_COPY.loading}`
        "
        tabindex="-1"
      >
        {{ listHydrated ? `共 ${filteredPoints.length} 个柜机落点` : `落点${UI_COPY.loading}` }}
      </button>
      <span v-if="tileHint" class="tile-hint">{{ tileHint }}</span>
    </div>

    <div class="map-side-panel">
      <div class="map-side__title">点位列表</div>
      <el-scrollbar max-height="70vh">
        <div
          v-for="p in filteredPoints"
          :key="p.deviceId"
          class="map-side__item"
          :class="{ active: selectedId === p.deviceId }"
          @click="focusPoint(p)"
        >
          <div class="row-main">
            <strong>{{ p.deviceName || p.deviceId }}</strong>
            <el-tag size="small" :type="p.onlineStatus === 'ONLINE' ? 'success' : 'info'">
              {{
                displayLabel('online_status', p.onlineStatus === 'ONLINE' ? 'ONLINE' : 'OFFLINE')
              }}
            </el-tag>
          </div>
          <div class="row-sub">
            {{ p.deviceId }} · {{ lifecycleLabel(p.lifecycleStatus) }} ·
            {{ p.salesLocked ? '停售' : '可售' }}
          </div>
          <div class="row-sub">{{ p.address || '无地址' }}</div>
          <div class="row-actions">
            <el-button
              link
              type="primary"
              size="small"
              @click.stop="goPath(`/devices/${encodeURIComponent(p.deviceId)}`)"
            >
              详情
            </el-button>
          </div>
        </div>
        <el-empty
          v-if="listHydrated && !loading && !filteredPoints.length"
          description="暂无落点（需设备填写经纬度）"
          :image-size="64"
        />
      </el-scrollbar>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import 'leaflet.markercluster';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { useNavAccess } from '@/composables/useNavAccess';
import { dictOptions, displayLabel } from '@aicabinet/shared-dict';
import { useSettingsStore } from '@/stores/settings';
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';

interface MapPoint {
  deviceId: string;
  deviceName?: string;
  merchantId?: string;
  merchantName?: string;
  onlineStatus?: string;
  lifecycleStatus?: string;
  routeCode?: string;
  salesLocked?: boolean;
  latitude: number;
  longitude: number;
  address?: string;
  /** SELF|FRANCHISE|CONSIGN */
  coopMode?: string;
}

const { goPath } = useNavAccess();
const settings = useSettingsStore();
const isDark = computed(() => settings.theme === 'dark');
const loading = ref(false);
const listHydrated = ref(false);
/**
 * 默认「全部有坐标」（`ALL`）。
 *
 * 🔴 投放地图的首要用途是**看全量点位分布**；默认收窄到「已投放」会让地图缺一大半点位，
 *    看起来像「设备没了」（2026-09-23 用户要求）。
 */
const lifecycleStatus = ref('ALL');
const onlineOnly = ref(false);
const selfOperatedOnly = ref(false);
const machineNo = ref('');
const areaKeyword = ref('');
const keyword = ref('');
const selectedId = ref('');
const tileHint = ref('');
const points = ref<MapPoint[]>([]);
const mapEl = ref<HTMLElement | null>(null);
let map: L.Map | null = null;
let cluster: L.MarkerClusterGroup | null = null;
const markerById = new Map<string, L.Marker>();
const FOCUS_ZOOM = 18;

const lifecycleOptions = [
  ...dictOptions('device_lifecycle'),
  { label: '全部有坐标', value: 'ALL' }
];

const filteredPoints = computed(() => {
  const machine = machineNo.value.trim().toLowerCase();
  const area = areaKeyword.value.trim().toLowerCase();
  const kw = keyword.value.trim().toLowerCase();
  return points.value.filter((p) => {
    if (onlineOnly.value && p.onlineStatus !== 'ONLINE') return false;
    if (selfOperatedOnly.value) {
      // 以设备合作方式为准（字典 device_coop_mode），勿用 merchantId 子串猜测
      if (String(p.coopMode || '').toUpperCase() !== 'SELF') return false;
    }
    if (
      machine &&
      !String(p.deviceId || '')
        .toLowerCase()
        .includes(machine)
    )
      return false;
    if (area) {
      const hay = `${p.address || ''} ${p.routeCode || ''}`.toLowerCase();
      if (!hay.includes(area)) return false;
    }
    if (kw) {
      const hay =
        `${p.deviceId || ''} ${p.deviceName || ''} ${p.merchantId || ''} ${p.address || ''}`.toLowerCase();
      if (!hay.includes(kw)) return false;
    }
    return true;
  });
});

function lifecycleLabel(s?: string) {
  return lifecycleOptions.find((o) => o.value === s)?.label || s || '未知状态';
}

function applyFilters() {
  renderMarkers();
}

async function load() {
  loading.value = true;
  try {
    const q = new URLSearchParams();
    if (lifecycleStatus.value) q.set('lifecycleStatus', lifecycleStatus.value);
    points.value = await api.request<MapPoint[]>(AdminEndpoints.devicesMapPoints(q), 'GET');
    await nextTick();
    renderMarkers();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    listHydrated.value = true;
    loading.value = false;
  }
}

function clusterTone(count: number): 'purple' | 'red' | 'yellow' | 'blue' {
  if (count >= 40) return 'purple';
  if (count >= 18) return 'red';
  if (count >= 9) return 'yellow';
  return 'blue';
}

function clusterSize(count: number) {
  if (count >= 100) return 64;
  if (count >= 40) return 56;
  if (count >= 18) return 48;
  if (count >= 9) return 42;
  return 36;
}

function ensureMap() {
  if (map || !mapEl.value) return;
  map = L.map(mapEl.value, {
    zoomControl: true,
    preferCanvas: false
  }).setView([31.23, 121.47], 11);

  // 优先高德彩色路网（参考图风格）；失败再切 Esri / GeoQ
  const layers = [
    L.tileLayer(
      'https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}',
      {
        maxZoom: 18,
        subdomains: '1234',
        attribution: '© 高德'
      }
    ),
    L.tileLayer(
      'https://wprd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=7&x={x}&y={y}&z={z}',
      {
        maxZoom: 18,
        subdomains: '1234',
        attribution: '© 高德'
      }
    ),
    L.tileLayer(
      'https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}',
      {
        maxZoom: 19,
        attribution: '© Esri'
      }
    ),
    L.tileLayer(
      'https://map.geoq.cn/ArcGIS/rest/services/ChinaOnlineCommunity/MapServer/tile/{z}/{y}/{x}',
      {
        maxZoom: 16,
        attribution: '© GeoQ'
      }
    )
  ];
  const attach = (i: number) => {
    if (!map || i >= layers.length) return;
    const layerTile = layers[i];
    let errors = 0;
    layerTile.on('tileerror', () => {
      errors += 1;
      if (!map || !map.hasLayer(layerTile) || errors < 6) return;
      map.removeLayer(layerTile);
      if (i + 1 < layers.length) {
        attach(i + 1);
        tileHint.value = `底图已切换备用源（${i + 2}/${layers.length}）`;
      } else {
        tileHint.value = '底图加载失败，右侧列表仍可查看点位';
      }
    });
    layerTile.addTo(map);
  };
  attach(0);

  const group = L.markerClusterGroup({
    showCoverageOnHover: false,
    maxClusterRadius: 70,
    spiderfyOnMaxZoom: true,
    disableClusteringAtZoom: 17,
    iconCreateFunction(c: L.MarkerCluster) {
      const count = c.getChildCount();
      const tone = clusterTone(count);
      const size = clusterSize(count);
      return L.divIcon({
        html: `<div class="pulse-cluster pulse-${tone}" style="--sz:${size}px">
          <span class="pulse-ring r1"></span>
          <span class="pulse-ring r2"></span>
          <span class="pulse-ring r3"></span>
          <span class="pulse-core"><b>${count}</b></span>
        </div>`,
        className: 'map-cluster-icon',
        iconSize: L.point(size + 36, size + 36),
        iconAnchor: L.point((size + 36) / 2, (size + 36) / 2)
      });
    }
  }) as L.MarkerClusterGroup;
  cluster = group;
  map.addLayer(group);
}

function pinIcon(selected: boolean) {
  const scale = selected ? 1.15 : 1;
  const w = Math.round(28 * scale);
  const h = Math.round(40 * scale);
  return L.divIcon({
    className: 'map-pin-icon',
    html: `<div class="teardrop-pin" style="--w:${w}px;--h:${h}px">
      <svg viewBox="0 0 28 40" width="${w}" height="${h}" aria-hidden="true">
        <path d="M14 0C6.3 0 0 6.3 0 14c0 10.5 14 26 14 26S28 24.5 28 14C28 6.3 21.7 0 14 0z" fill="#e53935"/>
        <circle cx="14" cy="14" r="6" fill="#fff"/>
      </svg>
    </div>`,
    iconSize: [w, h],
    iconAnchor: [w / 2, h],
    popupAnchor: [0, -h + 4]
  });
}

function renderMarkers() {
  ensureMap();
  if (!map || !cluster) return;
  cluster.clearLayers();
  markerById.clear();
  const list = filteredPoints.value;
  const bounds: L.LatLngExpression[] = [];
  for (const p of list) {
    const latlng: L.LatLngExpression = [p.latitude, p.longitude];
    bounds.push(latlng);
    const marker = L.marker(latlng, {
      icon: pinIcon(selectedId.value === p.deviceId)
    });
    const isOnline = p.onlineStatus === 'ONLINE';
    const status = displayLabel('online_status', isOnline ? 'ONLINE' : 'OFFLINE');
    const locked = p.salesLocked ? '停售' : '可售';
    marker.bindPopup(
      `<strong>${escapeHtml(p.deviceName || p.deviceId)}</strong><br/>${escapeHtml(p.deviceId)}<br/>${status} · ${locked} · ${escapeHtml(lifecycleLabel(p.lifecycleStatus))}<br/>路线：${escapeHtml(p.routeCode || '无')}<br/>${escapeHtml(p.address || '')}<br/><a href="#" class="map-goto" data-id="${escapeAttr(p.deviceId)}">查看详情</a>`
    );
    marker.on('click', () => {
      applySelection(p.deviceId);
    });
    marker.on('popupopen', () => {
      const link = document.querySelector(`a.map-goto[data-id="${CSS.escape(p.deviceId)}"]`);
      link?.addEventListener('click', (ev) => {
        ev.preventDefault();
        goPath(`/devices/${encodeURIComponent(p.deviceId)}`);
      });
    });
    markerById.set(p.deviceId, marker);
    cluster.addLayer(marker);
  }
  // 点选不再重建整层（见 applySelection 注释）；此处按真实落点框选只在初次加载/筛选刷新时做
  if (bounds.length === 1) {
    map.setView(bounds[0], 14);
  } else if (bounds.length > 1) {
    map.fitBounds(L.latLngBounds(bounds as L.LatLngTuple[]), { padding: [48, 48], maxZoom: 14 });
  }
  // 仅收敛在「整层重建」的路径上（初次加载 / 筛选刷新）。
  // 🔴 别把它挪回 focusPoint()：那里正在跑 flyTo，80ms 后插一次 invalidateSize 会打断动画。
  setTimeout(() => map?.invalidateSize(), 80);
}

/**
 * 只切换「选中 / 未选中」两个图标的差异，**不**碰其它标记。
 *
 * 🔴 为什么不能沿用 `renderMarkers()`：
 *  它开头就是 `cluster.clearLayers()`，会把**所有**标记（含聚合点的 `.pulse-cluster`）
 *  连同 DOM 一起销毁重建 —— 每次点一下点位列表，全图标记一起闪一下，
 *  聚合成环的 `map-pulse-ring` 动画也从 0 重来（实测：点 1 次列表 ⇒ 脉冲环 9 个 → 0 个，
 *  marker 面板 +12/-17 节点）。用户的原话就是「我点击点位列表的时候页面还会抖」。
 *  ⇒ 选中态是**局部**变化，就必须做局部更新。
 */
function applySelection(nextId: string) {
  const prevId = selectedId.value;
  if (prevId === nextId) return;
  selectedId.value = nextId;
  const prev = prevId ? markerById.get(prevId) : undefined;
  const cur = nextId ? markerById.get(nextId) : undefined;
  prev?.setIcon(pinIcon(false));
  cur?.setIcon(pinIcon(true));
}

function focusPoint(p: MapPoint) {
  ensureMap();
  applySelection(p.deviceId);
  if (!map) return;
  const maxZ = Math.min(FOCUS_ZOOM, map.getMaxZoom() ?? FOCUS_ZOOM);
  // 高于 disableClusteringAtZoom(17)，展开为红色水滴钉
  map.flyTo([p.latitude, p.longitude], maxZ, { duration: 0.55 });
  const openSelected = () => {
    const m = markerById.get(p.deviceId);
    if (!m || !map || !cluster) return;
    // 若仍被聚合包住，先缩放到能看到单点
    const visible = cluster.getVisibleParent?.(m);
    if (visible && visible !== m) {
      cluster.zoomToShowLayer?.(m, () => m.openPopup());
    } else {
      m.openPopup();
    }
  };
  map.once('moveend', openSelected);
  setTimeout(openSelected, 700);
}

function escapeHtml(s: string) {
  return s.replaceAll(
    /[&<>"']/g,
    (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c] || c
  );
}
function escapeAttr(s: string) {
  return s.replaceAll('"', '&quot;');
}

onMounted(async () => {
  await nextTick();
  ensureMap();
  await load();
});

onBeforeUnmount(() => {
  map?.remove();
  map = null;
  cluster = null;
});
</script>

<style scoped>
.map-page {
  position: relative;
  /**
   * 🔴 高度必须跟着「主区剩余空间」走，**不能拿视口高减常数**。三轮实测：
   *    · 旧版 `height: calc(100vh - 120px); min-height: 560px` ⇒ 1440×900 底边越出视口；
   *    · 换成 `height: clamp(320px, calc(100vh - 170px), 1200px)` 后又两头不对：
   *      ① 1440×900：可用高 770，公式只给 730 ⇒ 底部**留 40px 空白带**；
   *      ② 1092×606：主区里会多出一条窄屏提示条（`.el-alert.narrow-view*`，40px + 间距），
   *         地图可用高只剩 426，而公式仍按视口算 476 ⇒ **底边越出 50px**（比旧版更差）。
   *    · 根因：`100vh` 减常数无法反映「顶栏 / 面包屑 / 标签栏 / 窄屏提示条 / 主区内边距」
   *      在不同视口下的实际占位（1440 顶偏移 106，1092 是 156）。
   *    ⇒ 改用 `flex: 1 1 auto`：主区 `.layout-main-scroll` 是 column flex，
   *      让地图**吃掉剩余空间** —— 有富余就拉伸、无富余才被压缩，两个视口自动都对。
   *    · `min-height: 320px` 仍必须保留：本容器内部是 absolute 的 Leaflet 元素，
   *      内容最小高度 ≈ 0，只写 flex 会被压成一条细带（这条回归踩过一次）。
   */
  flex: 1 1 auto;
  min-height: 320px;
  border-radius: 10px;
  overflow: hidden;
  border: 1px solid var(--el-border-color-lighter);
  background: var(--layout-bg);
}
.map-canvas {
  width: 100%;
  height: 100%;
  z-index: 0;
}
.map-float-bar {
  position: absolute;
  top: 14px;
  left: 54px;
  right: 300px;
  z-index: var(--z-map-control, 500);
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  padding: 10px 12px;
  border-radius: 10px;
  background: color-mix(in srgb, var(--layout-card) 96%, transparent);
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.12);
  border: 1px solid var(--layout-border);
}
.float-input {
  width: 180px;
}
.map-float-meta {
  position: absolute;
  left: 54px;
  bottom: 14px;
  z-index: var(--z-map-control, 500);
  font-size: var(--admin-font-size-sm);
  color: var(--layout-text);
  background: color-mix(in srgb, var(--layout-card) 92%, transparent);
  padding: 4px 10px;
  border-radius: 999px;
  box-shadow: 0 2px 8px rgba(15, 23, 42, 0.08);
  display: flex;
  align-items: center;
  gap: 8px;
}
.map-count-btn {
  margin: 0;
  padding: 0;
  border: none;
  background: transparent;
  font: inherit;
  color: inherit;
  cursor: default;
  pointer-events: none;
}
.tile-hint {
  color: var(--el-color-warning);
}
.map-side-panel {
  position: absolute;
  top: 14px;
  right: 14px;
  bottom: 14px;
  width: 280px;
  z-index: var(--z-map-control, 500);
  border-radius: 10px;
  padding: 12px;
  background: color-mix(in srgb, var(--layout-card) 96%, transparent);
  border: 1px solid var(--layout-border);
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.12);
  overflow: hidden;
}
.map-side__title {
  font-weight: 600;
  margin-bottom: 8px;
  font-size: var(--admin-font-size-table);
}
.map-side__item {
  padding: 10px 8px;
  border-radius: 8px;
  cursor: pointer;
  border: 1px solid transparent;
  margin-bottom: 6px;
}
.map-side__item:hover,
.map-side__item.active {
  background: var(--el-fill-color-light);
  border-color: var(--el-color-primary-light-5);
}
.row-main {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  align-items: center;
}
.row-sub {
  font-size: var(--admin-font-size-sm);
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}
.row-actions {
  margin-top: 4px;
}
/* 中等宽度：先收窄输入框、再收浮层右边界，别让工具行长出第三行压住地图 */
@media (max-width: 1240px) {
  .float-input {
    width: 140px;
  }
  .map-float-bar {
    left: 12px;
    right: 292px;
  }
}

/**
 * 🔴 这里原来是 `max-width: 1100px` ⇒ 1092 宽的窗口下 `.map-side-panel` 被 display:none，
 * **整个「点位列表」消失**（实测 side.display=none），而用户的操作路径就是点这个列表。
 * 阈值收到 900（真正的窄屏）才隐藏，中间这段宁可让列表留在原地。
 */
@media (max-width: 900px) {
  .map-float-bar {
    left: 12px;
    right: 12px;
  }
  .map-side-panel {
    display: none;
  }
  .map-float-meta {
    left: 12px;
    top: auto;
    bottom: 14px;
  }
}
</style>

<style>
/* 去掉 leaflet.markercluster 默认灰气泡，改用参考图脉冲环 */
.map-cluster-icon {
  background: transparent !important;
  border: none !important;
}
.pulse-cluster {
  position: relative;
  width: calc(var(--sz, 48px) + 36px);
  height: calc(var(--sz, 48px) + 36px);
  display: flex;
  align-items: center;
  justify-content: center;
  --c: #3b82f6;
}
.pulse-purple {
  --c: #9b2cf3;
}
.pulse-red {
  --c: #ef4444;
}
.pulse-yellow {
  --c: #f5c518;
}
.pulse-blue {
  --c: #3b82f6;
}

.pulse-core {
  position: relative;
  z-index: 2;
  width: var(--sz, 48px);
  height: var(--sz, 48px);
  border-radius: 50%;
  background: var(--c);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: var(--admin-font-size-title);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.25);
  border: 2px solid rgba(255, 255, 255, 0.85);
}
.pulse-yellow .pulse-core {
  color: #1f2937;
}

.pulse-ring {
  position: absolute;
  left: 50%;
  top: 50%;
  width: var(--sz, 48px);
  height: var(--sz, 48px);
  margin-left: calc(var(--sz, 48px) / -2);
  margin-top: calc(var(--sz, 48px) / -2);
  border-radius: 50%;
  border: 2px solid var(--c);
  opacity: 0;
  pointer-events: none;
  animation: map-pulse-ring 2.4s ease-out infinite;
}
.pulse-ring.r2 {
  animation-delay: 0.8s;
}
.pulse-ring.r3 {
  animation-delay: 1.6s;
}

@keyframes map-pulse-ring {
  0% {
    transform: scale(1);
    opacity: 0.55;
  }
  70% {
    opacity: 0.12;
  }
  100% {
    transform: scale(2.35);
    opacity: 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .pulse-ring,
  .pulse-ring.r2,
  .pulse-ring.r3 {
    animation: none;
    opacity: 0.28;
  }
}

.map-pin-icon {
  background: transparent !important;
  border: none !important;
}
.teardrop-pin {
  filter: drop-shadow(0 2px 4px rgba(0, 0, 0, 0.35));
  line-height: 0;
}
/* 暗色模式下把高德/Esri 亮色底图反相为深色，避免刺眼 */
.map-page.map-dark .leaflet-tile-pane {
  filter: invert(1) hue-rotate(200deg) brightness(0.88) contrast(0.9) saturate(0.7);
}
.map-page.map-dark .leaflet-control-zoom a {
  background: var(--layout-card);
  color: var(--layout-text);
}
</style>
