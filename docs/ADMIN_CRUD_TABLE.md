# CrudTable 统一表格壳（排序 / 多选 / 固定操作列 / 导入导出 / 权限）

> 目标：列表页的表格能力**一次收口**——行为改动只改 `CrudTable.vue` + `useCrudTable.ts` 两个文件，所有接入页面同步生效；新页面零样板。
> 试点：`views/growth/NotificationsView.vue`（已迁移，可作对照模板）。

## 一、两层分工（为什么是两个文件 + 一段全局样式）

| 层 | 载体 | 生效范围 |
|---|---|---|
| 视觉规范 | `src/styles/main.css`「表格交互三件套的全局规范」段 | **全站 99 张 el-table 立即生效**（多选列居中、排序箭头主题色、空态高度），无需改页面 |
| 行为收口 | `src/components/CrudTable.vue` + `src/composables/useCrudTable.ts` | 接入 CrudTable 的页面；排序/多选/分页/操作列/导入导出/权限全部内建，改行为只改这一处 |

排序交互为**工具条上的「升序 / 降序」切换按钮**（不再使用表头箭头）；`mode: 'local'` 页面内重排，`mode: 'server'` 自动带 `sortProp/sortDir` 重查。

## 二、页面接入（迁移就是把样板换成配置）

迁移前（现状 58 页的手写样板）：`v-loading` + `:data` + `stripe/border` + `#empty` + selection 列 + `@selection-change` + `@sort-change` + `fixed="right"` 操作列 + `TableActions` + `PagePager` + `useListCsv` —— 每页 60~100 行。

迁移后：

```ts
const crud = useCrudTable<Row>({
  rowKey: (r) => r.id,
  fetchPage: ({ page, size }) =>                     // page 已是 0 起（全站约定）
    api.request(AdminEndpoints.xxxList(new URLSearchParams({ page: String(page), size: String(size) }))),
  sort: { prop: 'id', mode: 'local' }                // 或 mode: 'server'
});

const csvOptions: CrudCsvOptions = {
  filePrefix: '消息记录',
  exportPerm: 'ops:notify:list',                     // 无权限按钮整体隐藏
  headers: ['ID', '标题'],
  toRows: (rows) => rows.map((r) => [r.id, r.title]),
  // onImportRows: 导入回调（传了才渲染「下载模板/导入」）
};

function rowActions(row: Row): CrudRowAction[] {
  return [
    { key: 'edit',   label: '编辑', icon: Edit,   type: 'primary', perm: 'ops:xxx:edit' },
    { key: 'delete', label: '删除', icon: Delete, type: 'danger',  perm: 'ops:xxx:delete' }
  ];
}
```

```html
<CrudTable
  :table="crud" row-key="id" selectable
  :actions="rowActions" :csv="csvOptions"
  sort-field-label="ID" empty-text="暂无数据"
  @action="({ key, row }) => key === 'edit' ? openEdit(row) : removeRow(row)"
>
  <el-table-column prop="title" label="标题" min-width="150" />  <!-- 业务列原样保留 -->
</CrudTable>
```

控制器上页面还会用到的：`crud.load() / crud.refresh() / crud.search()`、`crud.hasSelection`、`crud.selectedKeys`、`crud.total / loading / hydrated`。

## 三、权限约定（管理员默认全有）

- 权限判断与 `v-hasPermi` 同源（`shared-rbac.matchPermission` + `auth.hasPerm`），`perm` 缺省 = 不过滤；`ops:admin` 运行时短路，admin 角色天然全量。
- **前端隐藏必须配合后端注解**：接口没有 `@RequiresPermissions` 时，隐藏按钮挡不住直连调用。新操作权限码照 `V106__export_import_button_perms.sql` 范本加 Flyway 种子 + 控制器注解。
- 存量缺口：不少页面仍用 `xxx:list` 码拦截删除/导出（如试点页），后续按域补 F 码（`ops:<module>:delete/export/import`）。

## 四、迁移批次（58 页存量）

参考 `ADMIN_TABLE_INVENTORY.md`（99 表全量盘点）。建议顺序：

1. **试点**：NotificationsView（已完成）；
2. **第一批（高频流水页）**：OrderListView、SessionListView、DeviceListView、SkuListView；
3. **第二批（系统/运营配置）**：AlertRuleView、OrgSitesView、AnnouncementsView、CouponsView 等；
4. **第三批（其余低频页）**：随触随迁。

迁移是机械替换（删样板、留业务列），每页 diff 小、可独立提交回滚。完成一批后，`npm run type-check && npm run test` 全绿即可合并。

## 五、后续增强（按需排期）

- 批量删除内建（`deletePerm` + `batchDelete` 回调，统一 confirm 文案与「成功 N 失败 M」提示）；
- 导入升级 xlsx（引入 exceljs/sheetjs，CSV 保持兜底）；
- 列显隐设置与列宽记忆（localStorage）、表格密度切换；
- 后端批量删除接口（现在多为前端 for 循环逐条调用，量大时慢）。
