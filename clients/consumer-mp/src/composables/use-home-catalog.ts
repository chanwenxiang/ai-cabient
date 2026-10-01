import { computed, ref, type Ref } from 'vue';
import type { DeviceProduct } from '@aicabinet/shared-types';

/**
 * P3-3 切二：首页商品目录的检索/分类/过滤逻辑（从 pages/index/index.vue 搬移）。
 *
 * 页面保留 `products`（加载编排）并把 ref 传入；本 composable 只管「怎么看这批商品」：
 * 关键词/分类两个筛选维度的状态、派生列表、以及目录区的一组交互 handler。
 * `datasetOf` 是首页多个事件委托 handler 共用的跨端取数工具，一并收口到此导出。
 */
export function useHomeCatalog(products: Ref<DeviceProduct[]>) {
  const searchKeyword = ref('');
  const activeCategory = ref('');

  const productCategories = computed(() => {
    const cats = new Set<string>();
    for (const p of products.value) {
      const cat = String(p.category || '').trim();
      if (cat) cats.add(cat);
    }
    return Array.from(cats).sort((a, b) => a.localeCompare(b, 'zh-Hans-CN'));
  });

  const filteredProducts = computed(() => {
    const kw = searchKeyword.value.trim().toLowerCase();
    const cat = activeCategory.value;
    return products.value.filter((p) => {
      if (cat && String(p.category || '').trim() !== cat) return false;
      if (
        kw &&
        !String(p.skuName || '')
          .toLowerCase()
          .includes(kw)
      )
        return false;
      return true;
    });
  });

  function resetCatalogFilter() {
    searchKeyword.value = '';
    activeCategory.value = '';
  }

  function clearSearchKeyword() {
    searchKeyword.value = '';
  }

  function clearCategory() {
    activeCategory.value = '';
  }

  /**
   * 从 uni-app 事件里取 `currentTarget.dataset[key]`。
   *
   * 跨端唯一稳定的取数位置就是 `currentTarget.dataset`（H5 是 DOM 事件、小程序是自定义对象，
   * 其余字段两端不一致）。原先每个 handler 各自声明一套窄类型
   * `{ currentTarget?: { dataset?: Record<string, string> } }`，与 Vue 给原生元素 `@click`
   * 推导出的 `PointerEvent` 参数不兼容 → vue-tsc 报 TS2345（共 3 处模板命中）。
   * 收口成 `unknown` 入参 + 单点断言后，既满足模板类型，也消掉了 4 份重复声明。
   */
  function datasetOf(e: unknown, key: string): string {
    const target = (e as { currentTarget?: { dataset?: Record<string, unknown> } } | null)
      ?.currentTarget;
    const value = target?.dataset?.[key];
    return value == null ? '' : String(value);
  }

  function onCategoryChipTap(e: unknown) {
    const cat = datasetOf(e, 'cat');
    if (!cat) return;
    activeCategory.value = activeCategory.value === cat ? '' : cat;
  }

  return {
    searchKeyword,
    activeCategory,
    productCategories,
    filteredProducts,
    resetCatalogFilter,
    clearSearchKeyword,
    clearCategory,
    datasetOf,
    onCategoryChipTap
  };
}
