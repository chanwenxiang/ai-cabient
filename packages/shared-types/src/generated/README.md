# OpenAPI → TypeScript

本目录由 `pnpm gen:api-types`（`scripts/gen-openapi-types.mjs`）生成。

1. 启动 trade-service（`:18080` 或 IDEA `:8080`）
2. 执行 `pnpm gen:api-types`
3. 产物：`openapi.ts`（也可先用 `.\scripts\sync-apifox-oas.ps1` 落盘 `.tmp/live-openapi.json`）

手写业务类型仍以 `src/index.ts` 的 `OrderReadModel` 为准。

- `openapi.ts`：完整 paths/components（**不参与** `shared-types` 的 `tsc`）
- `order-models.ts`：订单读模型精简别名（`OpenApiOrderReadModel*`），包路径 `@aicabinet/shared-types/generated/order-models`
