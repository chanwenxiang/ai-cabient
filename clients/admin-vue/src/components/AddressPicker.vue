<template>
  <!--
    地址录入器：省 / 市 / 区 级联下拉 + 详细地址（+ 可选「解析坐标」）。

    🔴 为什么要级联而不是让运营自由敲地址：
      高德 /v3/geocode/geo 在不限定城市时是全国模糊匹配、取第一条，实测
        「万达广场」→ 四川省南充市南部县
        「测试门店」→ 广东省梅州市兴宁市
      所以「先选行政区、再解析」是坐标可用的前提；选定后若解析结果落在
      所选行政区之外，后端会直接报错（fail-closed），不会写入错误点位。
  -->
  <div class="address-picker">
    <div class="address-picker__row">
      <!--
        🔴 必须包一层**原生 div** 再写宽度。
        `el-cascader` 是多根(Fragment)组件 ⇒ Vue **不会**把本组件的 scoped `data-v-xxx`
        透传到它的根元素 ⇒ 直接写在它身上的 scoped 规则（`width` / `flex`）会**静默失效**
        （不报错、选择器看着也「对」，只是永远匹配不上）。
        实测（`.tmp/probe/diag-width.mjs`）：元素 `attributes` 里一个 `data-v-*` 都没有，
        computed width 只有 132px（＝内容自然宽），于是「广东省 / 深圳市 / 罗湖区」
        被截成「广东省 / 深圳...」。
        ⇒ 宽度设在**原生 div**（它有 data-v）上，组件自身用内联 `width:100%` 填满。
      -->
      <div v-if="regionReady" class="address-picker__region">
        <el-cascader
          ref="cascaderRef"
          v-model="codes"
          :options="options"
          :props="cascaderProps"
          :disabled="disabled"
          placeholder="省 / 市 / 区"
          clearable
          style="width: 100%"
          @change="onRegionChange"
        />
      </div>
      <el-input
        v-model="detail"
        :disabled="disabled"
        :placeholder="regionReady ? '详细地址（街道、门牌号、门店名）' : '地址'"
        :maxlength="maxlength"
        show-word-limit
        clearable
        class="address-picker__detail"
        @input="onDetailInput"
      />
      <el-button
        v-if="resolvable"
        type="primary"
        plain
        :loading="loading"
        :disabled="disabled || !composed"
        @click="resolve"
        >解析坐标</el-button
      >
    </div>
    <div v-if="hint" class="address-picker__hint" :class="{ 'is-warn': hintWarn }">
      {{ hint }}
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { errorMessage } from '@/utils/error-message';

interface DistrictNode {
  adcode: string;
  name: string;
  level: string;
  children?: DistrictNode[];
  /**
   * 懒加载发现「这一级下面没有数据」时就地打标（见 lazyLoad 注释）。
   * 只有「省直辖县级市」这类市辖下无区县的节点会用到。
   */
  leaf?: boolean;
}

const props = withDefaults(
  defineProps<{
    /** 完整地址（省市区 + 详细） */
    modelValue?: string;
    disabled?: boolean;
    /** 是否显示「解析坐标」（需要 geo 已配置 + 有设备编辑权限） */
    resolvable?: boolean;
    /** 是否把解析出的坐标回写（false 时只作地址选择器） */
    writeCoords?: boolean;
    /** 详细地址输入框最大长度（与调用方原 el-input 的 maxlength 对齐） */
    maxlength?: number;
  }>(),
  { modelValue: '', disabled: false, resolvable: false, writeCoords: true, maxlength: undefined }
);

const emit = defineEmits<{
  'update:modelValue': [value: string];
  'update:latitude': [value: number | null];
  'update:longitude': [value: number | null];
}>();

const options = ref<DistrictNode[]>([]);
const codes = ref<string[]>([]);
const detail = ref('');
const loading = ref(false);
const hint = ref('');
const hintWarn = ref(false);
/**
 * 🔴 必须留一个级联组件句柄。
 *
 * Element Plus 的**懒加载不会把子节点写回我们传进去的 `options`**（它只挂到内部 `Node.childrenData`
 * 上），所以「按 `options` 树查名称」在懒加载层级上必然取不到，地址就会丢掉「省市区」前缀。
 *
 * ⚠️ 更不能「自己把拉到的子节点回写进 `options` 树」来自救：EP 级联面板里有
 * `watch(() => props.options, initStore, { deep: true })`，一旦深改 `options`，`initStore()` 会用
 * `options` 重建 store 并把 `menus` 重置成只剩第一级 —— 用户正展开的面板会被打回原形。
 *
 * ⇒ 唯一可靠来源就是 `getCheckedNodes()` 返回的节点：它的 `pathLabels` / `pathValues`
 *   在源码里是**同一份 `pathNodes` 的两个映射**（cascader-panel/src/node.mjs），天然成对同源。
 */
const cascaderRef = ref<{
  getCheckedNodes?: (leafOnly?: boolean) => Array<{ pathLabels?: string[]; pathValues?: string[] }>;
} | null>(null);
/**
 * 行政区数据取不到时（未配 Key / 后端不可达）**退回普通文本框**，
 * 不让「选不了省市区」变成「地址没法填」。
 */
const regionReady = ref(true);
/** 组件自己发起的 modelValue 更新不再回灌，避免与外部输入互相覆盖 */
let selfEmit = false;

const cascaderProps = {
  lazy: true,
  value: 'adcode',
  label: 'name',
  /**
   * 🔴 `leaf` 不是可有可无的装饰，它决定「点一下能不能选中」。
   *
   * Element Plus 的 `Node.isLeaf`（cascader-panel/src/node.mjs）在 lazy 模式下是：
   *   `isFunction(leaf) ? leaf(data) : data[leaf]` … 若为 undefined ⇒ `lazy && !loaded ? false : …`
   * 也就是**未加载过的节点一律 isLeaf=false**；而 `node.vue` 的 `handleClick` 只有
   * `isLeaf` 为真才走 `handleCheck(true)`，否则只去 `doLoad()`（懒加载）——
   * 结果就是「点区县第一次没反应，必须点第二次」。
   *
   * 行政区数据自带 `level`（province / city / district）⇒ 区县即叶子，一次点击就能选中。
   * `data.leaf` 兜住「省直辖县级市」这类**市辖下无区县**的节点（懒加载拿到空数组时打标）。
   */
  leaf: (data: DistrictNode) => data.level === 'district' || data.leaf === true,
  lazyLoad: async (
    node: { level: number; value?: string; data?: DistrictNode },
    resolve: (n: DistrictNode[]) => void
  ) => {
    try {
      const list = await fetchDistricts(node.level === 0 ? '' : String(node.value ?? ''));
      // 这一级没有下级 ⇒ 就地标成叶子。不标的话它永远 isLeaf=false，用户点了也选不上。
      if (!list.length && node.data) node.data.leaf = true;
      resolve(list);
    } catch {
      resolve([]);
    }
  }
};

async function fetchDistricts(parent: string): Promise<DistrictNode[]> {
  const data = await api.request<DistrictNode[]>(AdminEndpoints.geoDistricts(parent), 'GET');
  return Array.isArray(data) ? data : [];
}

/** 取某节点的下一级（结果缓存进 `children`，与级联自身的懒加载、以及 `selection()` 的回退同源） */
async function childrenOf(node: DistrictNode): Promise<DistrictNode[]> {
  if (!node.children) {
    try {
      node.children = await fetchDistricts(node.adcode);
    } catch {
      return [];
    }
  }
  return node.children;
}

/**
 * 高德对**直辖市**会在「市级」造一层**合成名**（不是真实地名）—— 真接口实测：
 *   北京市 → 北京城区(110100)｜上海市 → 上海城区(310100)｜天津市 → 天津城区(120100)
 *   重庆市 → 重庆城区(500100) **＋** 重庆郊县(500200)   ← 唯一有两个合成层的
 *
 * 🔴 这类名字拼进地址串会把高德带偏（真接口 A/B，同一 `city=310100&district=310110`）：
 *   「上海市上海城区杨浦区万达广场」→ adcode=**310115** 浦东新区「上海(九六广场)」⇒ 与所选行政区不一致，被后端拦下
 *   「上海市杨浦区万达广场」        → adcode=**310110** 杨浦区「上海市杨浦区万达广场」✅
 *
 * ⚠️ 只认 `level === 'city'`：区县一级确实存在**真实**地名带「城区」的（阳泉市城区 / 汕尾市城区），
 *    不能按名字一刀切；真实地级市也不会以「城区 / 郊县」结尾。
 */
const SYNTHETIC_CITY_NAME = /(城区|郊县)$/;
function isSyntheticCityNode(n: Pick<DistrictNode, 'name' | 'level'>): boolean {
  return n.level === 'city' && SYNTHETIC_CITY_NAME.test(n.name);
}

/**
 * 地址串前缀 = 行政区名拼接，**剔除合成层**。
 * `pathLabels` 只有名字、没有 `level`，而级联固定是「省(0) / 市(1) / 区(2)」（区县即叶子，见 `leaf`），
 * 所以按「市级＝下标 1」定位即可。
 */
function realPrefix(names: string[]): string {
  return names.filter((n, i) => !(i === 1 && SYNTHETIC_CITY_NAME.test(n))).join('');
}

/**
 * 当前选中的行政区 —— **展示名与 adcode 必须成对取自同一来源**。
 *
 * 🔴 踩过的坑：以前用 `pathLabels` 判「选了行政区」、却用 `codes` 发请求，
 * 两者不一致时就会出现「界面看着选好了，请求里却没有 adcode」⇒ 高德又退回全国模糊匹配，
 * 正好是本次要消灭的缺陷。所以这里二者长度不等就一律退回以传入的 adcode 为准。
 *
 * @param adcodes 外部权威值（`v-model` 绑定的 `codes`，调用方显式传入以建立响应式依赖）
 */
function selection(adcodes: string[]): { names: string[]; adcodes: string[] } {
  const node = cascaderRef.value?.getCheckedNodes?.(true)?.[0];
  const labels = (node?.pathLabels ?? []).filter(Boolean);
  const values = ((node?.pathValues ?? []) as string[]).filter(Boolean);
  /**
   * 🔴 只有「级联内部认的路径」与「外部权威值」**逐项一致**时，才敢采信它的 labels。
   *
   * 懒加载 + 回填场景下，EP 内部可能只认到**省级**（子节点没被点开过就不在 store 里），
   * 此时 `labels.length === values.length === 1` —— 长度相等，但内容是**前缀残缺**的。
   * 旧代码只看「两个长度相等」，会把残缺路径当成完整选择返回，
   * 于是 `sel.adcodes[1]`（市级）拿到 undefined（见 `resolve` 的守卫注释）。
   */
  const sameAsCodes = values.length === adcodes.length && values.every((v, i) => v === adcodes[i]);
  if (sameAsCodes && labels.length === values.length && labels.length) {
    return { names: labels, adcodes: values };
  }
  // 回退：以 adcode 为准，名称按 options 树查（回填 hydrate 时我们已把 children 补进树里）
  const names: string[] = [];
  let level = options.value;
  for (const code of adcodes.filter(Boolean)) {
    const hit = level.find((n) => n.adcode === code);
    if (!hit) break;
    names.push(hit.name);
    level = hit.children || [];
  }
  return { names, adcodes };
}

const composed = computed(() => {
  // 显式读一次 `codes` 以建立响应式依赖：`getCheckedNodes()` 不是响应式的，
  // 不这样写时「刚选完省市区」的地址拼接会取到 computed 的旧缓存值。
  const picked = codes.value;
  if (!regionReady.value) return detail.value.trim();
  // 🔴 用 `realPrefix` 而不是直接 join：直辖市下的「上海城区 / 重庆郊县」是合成层，
  // 拼进地址串会让高德解析跑偏（见 `isSyntheticCityNode` 的真接口证据）。
  return `${realPrefix(selection(picked).names)}${detail.value.trim()}`;
});

function emitValue() {
  const v = composed.value;
  if (v === props.modelValue) return;
  selfEmit = true;
  emit('update:modelValue', v);
}

function onRegionChange() {
  hint.value = '';
  hintWarn.value = false;
  emitValue();
}

function onDetailInput() {
  hint.value = '';
  hintWarn.value = false;
  emitValue();
}

/** 解析坐标：把行政区 adcode 一并带上，让高德在指定区划内收敛。 */
async function resolve() {
  const sel = selection(codes.value);
  const address = composed.value.trim();
  if (!address) return;
  /**
   * 🔴 光判「names 与 adcodes 长度相等」是不够的：长度 1 === 1 也能通过，
   * 但 `sel.adcodes[1]`（市级）是 undefined ⇒ 请求丢掉 city/district ⇒
   * 高德退回**全国模糊匹配**（正是本组件要消灭的那个行为），同名 POI 可能落到别的省市。
   * ⇒ 必须显式要求**市级拿得到**；区县可为空（兼容「省直辖县级市」这类两级地址）。
   */
  if (!sel.names.length || sel.adcodes.length !== sel.names.length || !sel.adcodes[1]) {
    ElMessage.warning('请先把省 / 市 选全（能选到区县更好），再解析坐标');
    return;
  }
  loading.value = true;
  try {
    const data = await api.request<{
      longitude: number;
      latitude: number;
      formattedAddress: string;
      province?: string;
      city?: string;
      district?: string;
      level?: string;
    }>(AdminEndpoints.geoGeocode(address, sel.adcodes[1], sel.adcodes[2]), 'GET');
    if (props.writeCoords) {
      emit('update:longitude', Number(data.longitude));
      emit('update:latitude', Number(data.latitude));
    }
    const where = `${data.province || ''}${data.city || ''}${data.district || ''}`;
    const coarse = /^(省|市|区县|country|province|city|district)$/.test(String(data.level || ''));
    hintWarn.value = coarse;
    hint.value = coarse
      ? `只匹配到「${where || data.formattedAddress}」（${data.level}）中心点，请把详细地址写具体后重解析，或手动微调经纬度`
      : `已定位：${data.formattedAddress}${data.level ? `（${data.level}）` : ''} → ${data.longitude}, ${data.latitude}`;
    if (!coarse) ElMessage.success('已写入经纬度，可再手动微调后保存');
  } catch (e) {
    hintWarn.value = true;
    hint.value = errorMessage(e, '地址解析失败');
    ElMessage.error(hint.value);
  } finally {
    loading.value = false;
  }
}

/**
 * 回填：把已有地址拆成「省/市/区 + 详细」。
 *
 * 做法是**按已加载的行政区名逐级匹配前缀**（而不是正则猜），匹配不到就整串当详细地址，
 * 所以最坏情况只是退化成「一个文本框」，不会把地址改错。
 *
 * 🔴 合成层（直辖市下的「上海城区 / 重庆郊县」，见 `isSyntheticCityNode`）**不在地址串里**
 *    （`composed` 已经把它剔掉了），所以这里**不能拿它去消费 `rest`** —— 它的 adcode 仍要收进
 *    `picked`（请求要靠它收敛城市），但名字匹配要落到它**下面那一级**真实节点上。
 *    否则「上海市杨浦区万达广场」只会认出「上海市」，省市区整段退化成详细地址文本。
 */
async function hydrateFromExisting(value: string) {
  const text = (value || '').trim();
  detail.value = text;
  if (!text) return;
  let level = options.value;
  const picked: string[] = [];
  let rest = text;
  // 步数上限 5 兜住「合成层不消费 rest」带来的额外跳（最多 省→[合成]→市→区 四跳），避免死循环
  for (let step = 0; step < 5 && picked.length < 3 && level.length; step++) {
    // ① 先在真实节点里找前缀匹配
    let hit = level.find((n) => !isSyntheticCityNode(n) && rest.startsWith(n.name));
    let via: DistrictNode | null = null;
    // ② 本层只有合成节点（北京城区；重庆是「重庆城区 + 重庆郊县」两个）⇒ 逐个下钻它的子级再找
    if (!hit) {
      for (const syn of level.filter(isSyntheticCityNode)) {
        const kids = await childrenOf(syn);
        const k = kids.find((n) => rest.startsWith(n.name));
        if (k) {
          via = syn;
          hit = k;
          break;
        }
      }
    }
    if (!hit) break;
    if (via) picked.push(via.adcode);
    picked.push(hit.adcode);
    rest = rest.slice(hit.name.length);
    if (picked.length < 3) level = await childrenOf(hit);
  }
  if (picked.length) {
    codes.value = picked;
    detail.value = rest.trim();
    emitValue();
  }
}

onMounted(async () => {
  try {
    options.value = await fetchDistricts('');
    regionReady.value = options.value.length > 0;
  } catch {
    options.value = [];
    regionReady.value = false;
  }
  if (props.modelValue) await hydrateFromExisting(props.modelValue);
});

// 外部传入的地址变化（如切换设备）时重新回填；自己发出去的更新跳过
watch(
  () => props.modelValue,
  (v) => {
    if (selfEmit) {
      selfEmit = false;
      return;
    }
    if ((v || '') === composed.value) return;
    void hydrateFromExisting(v || '');
  }
);
</script>

<style scoped>
.address-picker {
  width: 100%;
}
.address-picker__row {
  display: flex;
  gap: 8px;
  align-items: center;
  width: 100%;
}
.address-picker__region {
  /**
   * 🔴 这一个 div 存在的唯一理由：承载宽度。
   * 直接把 `.address-picker__region` 写在 `el-cascader` 上是**无效**的 —— 它是多根组件，
   * 拿不到 scoped 的 `data-v`（见模板里的长注释与 `diag-width.mjs` 实测）。
   *
   * 260px 也装不下三级全名：「广东省 / 深圳市 / 罗湖区」需要约 155px 文本宽 +
   * 箭头/内边距。给足即可；窄屏由下方 @media 回落成全宽。
   */
  flex: 0 0 auto;
  width: 340px;
  min-width: 0;
}
.address-picker__detail {
  flex: 1 1 auto;
  min-width: 0;
}
.address-picker__hint {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--layout-muted);
}
.address-picker__hint.is-warn {
  color: var(--el-color-warning);
}
@media (max-width: 900px) {
  .address-picker__row {
    flex-wrap: wrap;
  }
  .address-picker__region {
    width: 100%;
  }
}
</style>
