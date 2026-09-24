/**
 * 运营后台「动态列 / 多 Tab / 表单草稿」行类型（debt-tracker D15）。
 *
 * - **禁止**业务代码再散落写 `Record<string, any>`；统一别名至此
 * - 单实体、字段稳定的 API：优先 `@aicabinet/shared-types` OpenAPI DTO
 *   （例：补货柜门写路径用 `OpenApiReplenishmentTaskDto` / `OpenApiReplenishmentRouteDto`）
 * - 多 Tab 聚合列表、带 UI 附加字段的 reactive 表单：用本类型
 *
 * 值类型暂保留宽松索引（与历史行为一致）；继续收紧见 D15 完成记录。
 * `any` 仅允许出现在本别名；业务文件禁止再写 `Record<string, any>`。
 */
export type AdminDynamicRow = Record<string, any>;
