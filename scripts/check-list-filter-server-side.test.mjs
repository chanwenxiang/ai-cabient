#!/usr/bin/env node
/**
 * `check-list-filter-server-side.mjs` 的自测：fetchPage 块解析 + 行内过滤识别 + 边界不误报。
 *
 *   node scripts/check-list-filter-server-side.test.mjs
 */
import { extractFetchPageBlocks, findClientRowFilters } from './check-list-filter-server-side.mjs';

const TAG = '[check-list-filter-server-side.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`断言失败：${message}`);
}

// 1) 能抓到 MA-007 形态（rows = rows.filter(...) + total 换成页内条数）
const ma007 = `
const crud = useCrudTable({
  fetchPage: async (params) => {
    const data = await api.request(url, 'GET');
    let rows = data.items || [];
    rows = rows.filter((r) => (r.sessionKind || 'CONSUMER') === kindFilter.value);
    const total = rows.length < 1 ? rows.length : (data.total ?? 0);
    return { items: rows, total };
  }
});`;
{
  const blocks = extractFetchPageBlocks(ma007);
  assert(blocks.length === 1, `ma007 应解析出 1 个 fetchPage，实际 ${blocks.length}`);
  const hits = findClientRowFilters(blocks[0].body);
  assert(hits.length === 1, `ma007 应命中 1 处行过滤，实际 ${hits.length}`);
  assert(
    hits[0].snippet.includes('rows.filter'),
    `ma007 命中的应是 rows.filter，实际 ${hits[0].snippet}`
  );
}

// 2) 能抓到 MA-012 形态（const items = kw ? (data.items||[]).filter(...)）
const ma012 = `
const crud = useCrudTable({
  fetchPage: async (params) => {
    const data = await api.request(url, 'GET');
    const items = kw ? (data.items || []).filter((r) => matchKeyword(r, kw)) : data.items || [];
    return { items, total: Number(data.total) || 0 };
  }
});`;
{
  const hits = findClientRowFilters(extractFetchPageBlocks(ma012)[0].body);
  assert(hits.length === 1, `ma012 应命中 1 处，实际 ${hits.length}`);
}

// 3) 服务端过滤不误报（只有 URLSearchParams 构造与请求）
const serverSide = `
const crud = useCrudTable({
  fetchPage: async (params) => {
    const q = new URLSearchParams({ page: String(params.page) });
    if (keyword.value.trim()) q.set('q', keyword.value.trim());
    const data = await api.request(url(q), 'GET');
    return { items: data.items || [], total: Number(data.total) || 0 };
  }
});`;
{
  const hits = findClientRowFilters(extractFetchPageBlocks(serverSide)[0].body);
  assert(hits.length === 0, `服务端过滤写法不应命中，实际 ${hits.length}`);
}

// 4) 非行变量的 filter 不误报（筛选项、选项列表等）
const optionsOnly = `
const crud = useCrudTable({
  fetchPage: async (params) => {
    const data = await api.request(url, 'GET');
    const opts = options.filter((o) => o.enabled);
    return { items: data.items || [], total: data.total, opts };
  }
});`;
{
  const hits = findClientRowFilters(extractFetchPageBlocks(optionsOnly)[0].body);
  assert(hits.length === 0, `options.filter 不应命中，实际 ${hits.length}`);
}

// 5) fetchPage 之外的 filter 不误报（computed 展示层）
const outside = `
const displayRows = computed(() => crud.items.filter((r) => r.visible));
const crud = useCrudTable({
  fetchPage: async (params) => {
    const data = await api.request(url, 'GET');
    return { items: data.items || [], total: Number(data.total) || 0 };
  }
});`;
{
  const hits = findClientRowFilters(extractFetchPageBlocks(outside)[0].body);
  assert(hits.length === 0, `fetchPage 之外的 filter 不应计入，实际 ${hits.length}`);
}

// 6) 多块并存：嵌套花括号里也能正确配平（块内 return { ... } 不影响）
const nested = `
const crud = useCrudTable({
  fetchPage: async (params) => {
    const data = await api.request(url, 'GET');
    const items = (data.items || []).filter((r) => {
      if (!r.name) return false;
      return true;
    });
    return { items, total: data.total };
  }
});`;
{
  const blocks = extractFetchPageBlocks(nested);
  assert(blocks.length === 1, `nested 应解析出 1 块，实际 ${blocks.length}`);
  assert(findClientRowFilters(blocks[0].body).length === 1, 'nested 多行 filter 应命中 1 处');
}

console.log(`${TAG} OK（${checks} 条断言）`);
