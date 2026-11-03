# R13 admin 写面实测 — 轮报告

> 镜头：Playwright headless 真实登录态逐页访问（history 路由）+ 白屏/403/console 错误检测 + 高风险页写面形态验证 + 安全写操作端到端。
> 执行：2026-11-03 上午，锚 `33b65060`，活栈（gateway :80 + trade :18080）全绿。

## 战果

### ① 全量 79 路由读面覆盖：100% 干净

- Playwright 真实 UI 登录（13900000001 + 图形验证码，cookie 模式），逐页 goto history URL：
  **79 页全部正常渲染，0 白屏、0 真 403、0 console 错误**。
- 初跑 4 页（dashboard/disputes/audit/oper-logs）报 ⚠️ 经复核全部为**误报**（页面数据内容含字面「403」
  状态码/数值，非整页不可达；截图复核确认页面真实渲染）。
- 工程认知（留痕）：admin 是 **history 路由**（`/admin/<route>`，非 `index.html#hash`——hash 形态会整页
  落 forbidden）；登录态是 **cookie 模式**（`admin_cookie_auth=1`，token 不落 storage——MA-020 修复后的形态）。

### ② 高风险写面形态验证（批 1 六页 + 弹窗）

- **调拨创建弹窗（MA-041/058 修复活体复验 ✓）**：有效期字段存在、「可空」误导提示已除、
  6 个必填项标记在位——与修复证据 `evidence/MA-041-transfer-form-created.png` 一致。
- 机器账单/线长/财务/提现/字典五页渲染与表单弹窗开启正常（截图 `.tmp/r13-evidence/`）。

### ③ 安全写操作端到端（建→验→清）

- **字典项全链 ✓**：POST `purchase_order_status/items` → 列表可见 → DELETE，零残留。
- **公告**：POST 草稿 ✓（announceId=1）→ 发现**无 DELETE 端点**（只有 archive，设计如此）→ archive 清理 ✓。
- **部门**：POST ✓（deptId=5）→ **无 DELETE 端点**（只有 PUT 停用，设计如此）→ PUT 改名+INACTIVE 清理 ✓。
- 顺带实证：公告/部门的删除语义走 archive/停用而非物理删——与台账「feedback 可物理硬删」（MA-111）
  形成对照，MA-111 的修法方向（补 delete 码+留痕）维持。

### 新发现

无新领号（批 1 的 MA-041/058 修复复验通过、读面零新增缺陷；R12 静态已把写面参数/权限问题领光）。

### 未挣

- 页面级「真实点击写按钮→填表单→提交」的逐页全遍历（79 页 × 写操作矩阵）由「R12 写面全量（95 Controller
  静态四查）+ 本轮全量读面 + 高风险弹窗/写操作抽查」三面拼图覆盖；逐页点击剩余面留待按需。
