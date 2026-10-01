import { describe, expect, it } from 'vitest';
import { ref } from 'vue';
import { useHomeCatalog } from './use-home-catalog';
import type { DeviceProduct } from '@aicabinet/shared-types';

/**
 * 商品目录检索/分类逻辑判据（P3-3 切二从页面搬出后补的覆盖：原页内逻辑零测试）。
 */
function product(skuId: string, skuName: string, category?: string): DeviceProduct {
  return { skuId, skuName, category, priceCents: 100 } as DeviceProduct;
}

describe('useHomeCatalog', () => {
  it('分类去重排序（中文 locale），空分类不入列', () => {
    const products = ref([
      product('A', '可乐', '饮料'),
      product('B', '雪碧', '饮料'),
      product('C', '薯片', '零食'),
      product('D', '无分类'),
      product('E', '空白分类', '  ')
    ]);
    const { productCategories } = useHomeCatalog(products);
    // zh locale 按拼音排序（L<Y）：零食在饮料前
    expect(productCategories.value).toEqual(['零食', '饮料']);
  });

  it('关键词过滤不区分大小写，分类过滤精确匹配，两者叠加', () => {
    const products = ref([
      product('A', 'Coca-Cola', '饮料'),
      product('B', '矿泉水', '饮料'),
      product('C', '薯片', '零食')
    ]);
    const { searchKeyword, activeCategory, filteredProducts } = useHomeCatalog(products);

    searchKeyword.value = 'cola';
    expect(filteredProducts.value.map((p) => p.skuId)).toEqual(['A']);

    searchKeyword.value = '';
    activeCategory.value = '饮料';
    expect(filteredProducts.value.map((p) => p.skuId)).toEqual(['A', 'B']);

    searchKeyword.value = '水';
    expect(filteredProducts.value.map((p) => p.skuId)).toEqual(['B']);
  });

  it('分类 chip 再点一次取消选中（toggle 语义）；dataset 取数容错', () => {
    const products = ref([product('A', '可乐', '饮料')]);
    const { activeCategory, onCategoryChipTap, datasetOf } = useHomeCatalog(products);

    onCategoryChipTap({ currentTarget: { dataset: { cat: '饮料' } } });
    expect(activeCategory.value).toBe('饮料');
    onCategoryChipTap({ currentTarget: { dataset: { cat: '饮料' } } });
    expect(activeCategory.value).toBe('');
    onCategoryChipTap({ currentTarget: { dataset: {} } });
    expect(activeCategory.value).toBe('');

    expect(datasetOf(null, 'cat')).toBe('');
    expect(datasetOf({ currentTarget: { dataset: { skuId: 7 } } }, 'skuId')).toBe('7');
  });

  it('重置目录筛选同时清空关键词与分类', () => {
    const products = ref([]);
    const { searchKeyword, activeCategory, resetCatalogFilter } = useHomeCatalog(products);
    searchKeyword.value = 'x';
    activeCategory.value = 'y';
    resetCatalogFilter();
    expect(searchKeyword.value).toBe('');
    expect(activeCategory.value).toBe('');
  });
});
