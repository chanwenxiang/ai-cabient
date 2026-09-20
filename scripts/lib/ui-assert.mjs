/**
 * 三端 UAT 共用的**结构化**页面断言。
 *
 * 存在理由（别退回到文本正则）：旧判据形如
 *   `bodyText.includes('争议审核')` / `/订单|已支付|暂无/.test(text)`
 * 而这些词**同时出现在常驻外壳里**——
 *   · 运营端侧栏菜单标题就是「订单管理 / 争议审核 / 异常中心」（`src/config/menu.ts:94/108/115`）
 *   · H5 端页面导航栏标题即「我的订单」「柜机订单」「争议处理」
 * 于是**内容区整块没渲染也照样绿**：判据测的是导航栏，不是页面。
 *
 * 这里改为断言「该页面专属的结构确实存在且已水合」：
 *   运营端 = 内容区标题精确匹配 + `.report-table` 存在 + 有数据行或已水合的 el-empty + 不在 loading
 *   H5 端 = 页面专属内容容器存在 + 有列表项或空态 + 无可见错误态
 * 这类判据是**可以变红**的：改错期望标题/选择器即失败（见证据文档的 A/B 反证）。
 */

/** 轮询直到条件成立或超时；返回最后一次探测结果（含 ok 字段）。 */
export async function pollUntil(page, fn, { timeoutMs = 8000, intervalMs = 250 } = {}) {
  const deadline = Date.now() + timeoutMs;
  let last = null;
  for (;;) {
    last = await fn();
    if (last && last.ok) return last;
    if (Date.now() >= deadline) return last;
    await page.waitForTimeout(intervalMs);
  }
}

/**
 * 运营后台内容区状态。
 *
 * 注意 `.page-card-head__title` 只在 `src/views/**` 使用（64 处），
 * **布局与侧栏不引用它** ⇒ 它可以区分「内容区渲染」与「导航栏文案」。
 *
 * @param {import('playwright').Page} page
 * @param {{title: string, tableSel?: string, timeoutMs?: number}} opts
 *   title 必须是该页内容区标题的**精确**文本（不是子串）
 */
export async function adminPageState(
  page,
  { title, tableSel = '.report-table', timeoutMs = 8000 }
) {
  const probe = () =>
    page.evaluate(
      ({ title, tableSel }) => {
        const norm = (s) => (s || '').replace(/\s+/g, ' ').trim();
        const actualTitle = norm(
          document.querySelector('.page-card-head__title .title')?.innerText
        );
        const titleOk = !!title && actualTitle === title;

        const table = document.querySelector(tableSel);
        const tablePresent = !!table;
        const rows = table ? table.querySelectorAll('.el-table__body tr.el-table__row').length : 0;
        // el-table 的 #empty 插槽在本项目里被 `v-if="listHydrated && !loading"` 门控
        // ⇒ 它的出现等价于「列表已水合」，而 `empty-text=" "` 的默认空块不算。
        const hydratedEmpty = !!(table && table.querySelector('.el-table__empty-block .el-empty'));
        const mask = table ? table.querySelector('.el-loading-mask') : null;
        const loading = !!mask && getComputedStyle(mask).display !== 'none';

        const ok = titleOk && tablePresent && !loading && (rows > 0 || hydratedEmpty);
        return {
          ok,
          titleOk,
          actualTitle,
          tablePresent,
          rows,
          hydratedEmpty,
          loading,
          detail: `title=${JSON.stringify(actualTitle)} table=${tablePresent} rows=${rows} hydratedEmpty=${hydratedEmpty} loading=${loading}`
        };
      },
      { title, tableSel }
    );

  return pollUntil(page, probe, { timeoutMs });
}

/**
 * H5（uni-app）列表页状态。
 *
 * `contentSel` 只用来判「内容分支已渲染」；`itemSel/emptySel/errorSel` 一律从 document 查，
 * 因为它们常常**不在** contentSel 之内（例如商户订单页的列表项 `.card` 与
 * `.filter-panel` 是兄弟节点）。写成「从 contentSel 内部查」会永远命中 0。
 *
 * @param {import('playwright').Page} page
 * @param {{contentSel: string, itemSel: string, emptySel?: string, errorSel?: string, timeoutMs?: number}} opts
 *   contentSel 必须是**只在内容分支渲染**的容器（例如消费者订单页的 `.orders-main`
 *   由 `v-else` 门控：非加载中、已登录才出现），不能是导航栏/外壳。
 */
export async function mpListPageState(
  page,
  {
    contentSel,
    itemSel,
    emptySel = '.empty-state',
    errorSel = '.error-state',
    timeoutMs = 8000
  } = {}
) {
  const probe = () =>
    page.evaluate(
      ({ contentSel, itemSel, emptySel, errorSel }) => {
        const visible = (el) => {
          if (!el) return false;
          const st = getComputedStyle(el);
          return st.display !== 'none' && st.visibility !== 'hidden' && el.offsetHeight > 0;
        };
        const contentOk = visible(document.querySelector(contentSel));
        const items = document.querySelectorAll(itemSel).length;
        const emptyVisible = [...document.querySelectorAll(emptySel)].some(visible);
        const errors = [...document.querySelectorAll(errorSel)].filter(visible).length;

        const ok = contentOk && errors === 0 && (items > 0 || emptyVisible);
        return {
          ok,
          contentOk,
          items,
          emptyVisible,
          errors,
          detail: `${contentSel}=${contentOk} ${itemSel}=${items} empty=${emptyVisible} errors=${errors}`
        };
      },
      { contentSel, itemSel, emptySel, errorSel }
    );

  return pollUntil(page, probe, { timeoutMs });
}
