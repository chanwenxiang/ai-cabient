# R8 系统（15 页 + 登录/错误页 + 权限矩阵专项）

- **锚**：发现锚 `d79c52fe`（HEAD 以 `git rev-parse --short HEAD` 为准）；修复锚＝批 1 源码 `d913c59f` + 产物 `911ebf4b`、批 2 `55bac20f`。
- **环境**：本机 dev 栈（trade `:18080`、nginx `:80`、admin vite `:3100`）；UI 一律 Playwright 驱动本机真 Chrome（headless 批量探针 + 关键页截图）。
- **进度**：批 1（6 页）✅ 完成 —— `/operators` `/roles` `/departments` `/approvals` `/menus` `/dicts`；批 2（9 页 + 登录/错误页 + 权限矩阵）✅ 完成 —— `/system-configs` `/alert-rules` `/scheduled-tasks` `/org-sites` `/announcements` `/audit` `/devops` `/observability` `/profile` + `/login` `/forbidden` `/not-found`。**R8 全模块收口**。

## 批 1 逐页结论

| 页面 | 数据/口径核对（真值来源） | 交互与写面 | 结论 |
|------|---------------------------|------------|------|
| `/operators` 运营账号 | 11 行 = SQL；`phone` 为**服务端过滤**（`139`→5、全号→1、乱码→0，`total` 同步）；「数据范围」列与 `ops_user_merchant` 逐行一致（100000011=1、100000010=2、100000009=2） | 新增/重置密码/角色/商户范围/设备范围均为独立弹窗（本批只开框验证确认门，未提交写操作） | 无缺陷 |
| `/roles` 角色管理 | 「权限数」10 行逐条 = `ops_role_permission` 计数（198/75/23/44/29/41/22/19/23/11） | 关键词+状态筛选进 SQL；导入/导出/模板齐 | 无缺陷 |
| `/departments` 部门管理 | 4 行 = `ops_department`；「成员数」1/2/1/1 = `ops_user_department` 计数 | 无筛选条（4 行树表，可接受）；批量启用/停用有确认 | 无缺陷 |
| `/approvals` 审批流配置 | 7 条流程定义；节点图预览列有值 | 新增/编辑走弹窗 | 无缺陷（节点指派的越权面留批 2 权限矩阵） |
| `/menus` 菜单管理 | 树 201 行 = `ops_permission`；C 行 ACTIVE 62 条 | 关键词/类型筛选、含停用开关、新增/编辑/停用有确认 | **MA-019（P2）已修（档 A）** |
| `/dicts` 字典管理 | 左列 96 类型 = `sys_dict_type`；「项数」逐条 = `sys_dict_data` ACTIVE 计数（抽样 14 条全等）；右表 50/页且表头显式给「N 项」 | 类型/字典项分栏、导入导出、编辑确认 | 无缺陷 |

## MA-019（P2，档 A 已修）：菜单树提示语谎报「一一对应」，停用一行会连带隐藏最多 8 个侧栏页

- **原话（旧提示）**：「运营侧栏叶子菜单与本树 ACTIVE『菜单 C』一一对应（一级目录 = 侧栏分组）。停用菜单会从侧栏隐藏。」
- **实测**：侧栏 77 页里只有 62 条 C 行，且**一个权限码被多页共用**（`NAV_ITEMS.perm` 计数）：
  `ops:warehouse:list` → **8 页**、`ops:device:list` → 4 页、`ops:replenishment:list` / `ops:fund:list` → 3 页、
  `ops:config:list`（参数配置）→ 2 页（连带 `/alert-rules`）、`ops:devops:view` → 2 页（连带 `/observability`）等，共 10 个共用码 ⇒ **19 个侧栏页没有自己的 C 行**。
  后端 `OpsRbacService.activeNavPermissions`（只取 ACTIVE 的 C/M 码）+ `OpsPermissionMapper.selectPermCodesByUserId`（`p.status='ACTIVE'`）实锤：停用一行 = 共用它的全部页面一起从侧栏消失。
- **反向**：C 树里 3 条路径不是侧栏叶子 —— `/warehouse`、`/oper-logs` 是兼容重定向（`/warehouse` 旧正则漏识别，已修 `step0-inventory.mjs`），`/recognition-demo`（`ops:recognition-demo:view`，3 个角色持有）**前端无路由无页面**＝幽灵行。
- **档 A（已做）**：提示语改成实话（一个权限码可被多页共用 + 停用连带影响），并新增「侧栏页」列：C 行显示 `1 页` 或 `N 页共用`（warning tag），非 C 行 `—`。实测渲染 10 条「页共用」，无 console 错误。
- **档 B（待拍板，未动）**：给 19 个共用页补独立 C 行（迁移 + 逐角色授权）。推荐**不做**——授权面从 62 涨到 81、角色编辑弹窗变长，而收益只是「能单独隐藏某一页」，运营侧目前没有这个诉求；如要做，必须先定「谁该单独可停」。

## 待拍板 / 待办（未值守期间只记录不动）

1. `/recognition-demo` 幽灵 C 行：删除 or 置 INACTIVE？我倾向 **INACTIVE + 备注留档**（3 个角色仍持有，删行会连带 `ops_role_permission` 级联，取证面变差）。
2. 权限矩阵专项（批 2）：`/profile` 是唯一无 perm 码的侧栏页（登录即可见），需确认「个人中心」是否应受 `ops:operator:self` 类码约束。

## 门禁/脚本顺带修复

- `docs/module-audit/step0-inventory.mjs`：兼容重定向识别支持 `{ path, name, redirect: (to) => … }` 形态（`/warehouse` 此前被误判为「路由有、菜单未登记」的疑似死页）。

## 批 2 逐页结论

| 页面 | 数据/口径核对（真值来源） | 交互与写面 | 结论 |
|------|---------------------------|------------|------|
| `/system-configs` 参数配置 | 「共 115 条」= `select count(*) from system_config` **115**；首行键/分组/中文说明/值齐（`balance.refund.max_cents`=500000） | 新增/编辑/删除走弹窗并带确认；导入导出 | 无缺陷 |
| `/alert-rules` 告警规则 | 31 行由 `BUILTIN_GROUPS` 白名单键 ∪ 自定义键派生（数据源仍是 `system_config`，与上页同源，两页 hint 已各自交代） | 「测试告警通道」调 `systemConfigAlertTest`；删除/批量保存带确认 | 无缺陷（硬编码白名单族已在 R7 门禁 `check:no-inline-dict-display` 轮结论里登记） |
| `/scheduled-tasks` 定时任务 | 「共 42 条」= `scheduled_task` **42**；行内「最近执行/状态/耗时/说明」逐列 = 表列 `last_run_at/last_result/last_duration_ms/last_message`；抽查 `auto-replenish-task` 2026-11-01 11:08:39 成功 22ms、`deposit-monthly-refund`「跳过 / skipped: task disabled」与 `enabled=false` 一致 | 启停开关 + 立即执行均有确认；无手写下一行 | 无缺陷 |
| `/org-sites` 组织与点位 | 「共 0 条」= `site_contract` **0** 且 `ops_org_node` **0** ⇒ 空态是真·无数据，不是过滤掉 | 组织树/点位合同两个分区，新增走弹窗 | 无缺陷 |
| `/announcements` 通知公告 | 「暂无公告」= `announcement` **0** | — | 无缺陷 |
| `/audit` 审计日志 | 明细「共 304 条」= `admin_audit_log` **304**；折叠里的敏感操作汇总 = 后端 `SENSITIVE_ACTION_PREFIXES`(23 前缀) + 近 7 天窗口的 GROUP BY，SQL 复算恰为 **3 行**（`WAREHOUSE_MANAGER` 2、`OPS_ROLE_PERM_ASSIGN` 1、`OPS_USER_ROLE_ASSIGN` 1）且首行文本/时间一致 | 动作/对象筛选、只看我的、CSV 导出 | 无缺陷 |
| `/devops` DevOps 中心 | 四张卡状态与实探一致：Grafana 在线（`:13000/api/health` 200）、Prometheus 在线（`:9090/-/healthy` 200）、SonarQube「未检测到服务」（`:9000` 不通，dev 未起）、GitHub「外部链接」 | 「新窗口打开」/「重跑 Sonar」（未触发）/ Prometheus 中文查询按钮 | **MA-022（P3）已修**（内嵌 iframe 在 dev 恒 404） |
| `/observability` 日志中心 | 6 个看板 = Grafana 里 6 个已 provision 的 dash（`/etc/grafana/provisioning/dashboards/json` 六个 json，API search 亦返回 6 条），uid 逐个对得上 | 分段按钮切页签（hint 说「页签」，实现是 radio-group，语义可接受） | 同 **MA-022**；其余无缺陷 |
| `/profile` 个人中心 | 只读本人资料 + 改密/绑定验证器入口，无列表数据可核 | 改密、绑定 2FA 均走弹窗带确认（本批只开框，未提交） | 无缺陷；`/profile` 无 perm 码这件事仍是待拍板 #2 |
| `/login` 登录页 | 未登录态：勾选项唯一且默认「√记住账号密码」；口令框初始长度 **0**（A-1「不预填演示口令」仍成立）；图形验证码 data URI 正常 | 「忘记密码？」开「重置登录密码」弹窗（短信码 + 图形码），只开框未提交 | **MA-020（P1）待拍板**：口令以 base64 常驻 localStorage |
| `/forbidden` `/not-found` | 空态文案 + 「返回工作台 / 返回上一页 / 个人中心」按钮齐；无接口 404 | — | 无缺陷 |

## MA-020（P1，待拍板未动码）：后台把可逆还原的登录口令常驻 localStorage，且默认开启

- **实测（真 Chrome，登录一次后读存储）**：`admin_password` 存在且 `atob(admin_password) === 本次登录口令`（判据 `true`）；`admin_remember_password="1"`；`admin_phone` 同在。
- **代码定位**：[clients/admin-vue/src/views/LoginView.vue:291-309](file:///D:/ai-generated%20code/ai-cabinet/clients/admin-vue/src/views/LoginView.vue#L291-L309)（`PW_STORE_KEY = 'admin_password'` 在 :298，注释 :296 自认「base64 编码（便利性混淆，非安全边界）」，`readRememberedPassword()` 用 `atob`）+ [clients/admin-vue/src/api/auth-storage.ts:87-89](file:///D:/ai-generated%20code/ai-cabinet/clients/admin-vue/src/api/auth-storage.ts#L87-L89)（只有**取消勾选**才清 ⇒ 默认勾选的用户登出后口令仍留机）。
- **为什么算缺陷而不是「已拍板的便利」**：base64 是编码不是加密，任何 admin 源上的 XSS、任何有 storage 权限的浏览器扩展、任何能打开 devtools 的人，都能一步还原成明文口令并重放登录；这个后台管的是提现、分账、改价。风险与「便利」不在一个量级，而 2026-10-22 的拍板主题是「两个勾选合并成一个」，**并未评估过凭据在浏览器侧可逆留存这一层**。
- **三档（推荐 B）**：
  - A｜保持现状不改：接受凭据留存面（须把注释与 hint 写成「口令明文可逆存本机」，别再叫「非安全边界」）。
  - B｜**应用不再存口令**（推荐）：`admin_password` 不再写、进页面即 `removeItem` 清历史值，「记住账号密码」改名「记住账号」，交给浏览器密码管理器（体验等价，凭据进 OS 级保管，不再被脚本读取）。改动面：LoginView 两处 + auth-storage 一处，无迁移、无后端改动。
  - C｜折中「只记一次」：口令存 sessionStorage（关标签即失），仍留 XSS 可读窗口，收益比 B 小、代码比 B 多。
- **值守期决定**：属「推翻既有拍板」类，未动码，等用户回来定档；定了 B 大约 20 分钟（含活栈复验 + 产物重建）。

## MA-021（P2，已修 `V421`）：4 个零引用页面权限码仍以 ACTIVE 留在权限表＝幽灵菜单，角色树里可勾选但什么都不打开

- **成因**：H51 那轮修「导航码 ≠ 后端校验码」时**只改了前端一侧**，[clients/admin-vue/src/config/menu.ts](file:///D:/ai-generated%20code/ai-cabinet/clients/admin-vue/src/config/menu.ts) 里 :35 / :188 / :441 三条注释写明了换码理由，但被换下来的 `ops_permission` 行没处理。
- **判据（逐码复核，非仅 grep）**：`ops:ad:campaign:list`(615) / `ops:bigscreen:view`(576) / `ops:sku-vision:list`(562) / `ops:vision:list`(151) 四行 status=ACTIVE、perm_type=C，
  ① 不在 `NAV_ITEMS.perm` 全集（56 个码）里；② `services/**/*.java` 与 `clients/**/{ts,vue,js}` 全域 0 命中（只剩迁移种子、docs 取证与 menu.ts 注释）；③ `parent_id` 子节点数 **0** ⇒ 退役不会把在用的按钮一起带走。
- **用户可见后果**：「菜单管理」（数据源 `rbacPermissionsIncludeInactive`）把它们显示成启用中的页面；角色编辑的权限树取 ACTIVE 全集（[clients/admin-vue/src/views/system/RoleManageView.vue:402](file:///D:/ai-generated%20code/ai-cabinet/clients/admin-vue/src/views/system/RoleManageView.vue#L402)）⇒ 运营可以勾给角色（现 4/2/3/2 个角色就勾着），勾了既不放开页面也不放开接口。
- **修复**：`V421__retire_phantom_page_perms.sql` 按 V386（`ops:session:upload`）先例置 **RETIRED 不删行**（授权与审计留痕原样保留，`OpsPermissionMapper` 两条读查询带 `p.status='ACTIVE'` ⇒ 即刻失效）。
- **实跑证据**：dev 库事务内 dry-run（`BEGIN` → 迁移体 → `ROLLBACK`）：`UPDATE 4`，四行 ACTIVE→RETIRED，同前缀的 `ops:vision:export` 未误伤，ACTIVE 非 F 行数 91→按预期递减；迁移本身幂等（`AND status <> 'RETIRED'`）。
- **刻意不动的同族**（判据不同，勿顺手对齐）：
  - `ops:operlog`(290)、`ops:pricing:view`(718)：本身零引用，但各挂一个**在用**的 F 子节点（`ops:operlog:export`、`ops:pricing:edit` 前后端都有实引用），退役父行会让按钮在角色树里无处挂靠 ⇒ 保留，它们现在的作用是分组节点。
  - `ops:nav:*` 7 个 M 目录行：侧栏分组由「有无可见子页」推导（`config/sidebar.ts:176-184`），目录码按设计不进代码 ⇒ 结构节点，保留。
  - `ops:recognition-demo:view`：两条门禁按 V138 显式豁免，去留在待拍板 #1。

## MA-022（P3，已修）：dev 端「DevOps 中心 / 日志中心」内嵌看板恒 404，与同一页的「在线」判定自相矛盾

- **实测**：vite `:3100` 下 `/devops` 报 1 个、`/observability` 报 6 个 404，全部落在 `/devops/grafana/d/ai-cabinet-*`；卡片却显示「Grafana 看板 · 在线」——因为在线探针由**后端**发起（能连容器），而 iframe 走**浏览器同源路径**，vite 只代理了 `/api`。
- **生产形态不是缺陷**（已实证，避免误判）：`curl -u <htpasswd 用户>` 打网关 `http://127.0.0.1/devops/grafana/d/ai-cabinet-overview/…` → **200**（先 Basic，再跳 Grafana 自身登录，`GF_AUTH_ANONYMOUS_ENABLED=false` + 网关剥 `Authorization`，与 [infra/gateway/nginx-full.conf:167-192](file:///D:/ai-generated%20code/ai-cabinet/infra/gateway/nginx-full.conf#L167-L192) 注释一致）。所以只缺 dev 代理。
- **修复**：[clients/admin-vue/vite.config.ts:60-68](file:///D:/ai-generated%20code/ai-cabinet/clients/admin-vue/vite.config.ts#L60-L68) 增加 `/devops` 代理，默认直连 compose 发布的 Grafana 端口 `127.0.0.1:13000`（root_url 已含子路径，无需 rewrite），可用 `VITE_DEV_DEVOPS_TARGET` 覆盖；不改生产链、不引入凭据。
- **实跑证据**：另起临时 dev（`:3199`，不动在跑的 `:3100`）后 `GET http://127.0.0.1:3199/devops/grafana/api/health` → **200**（改前同路径由 vite 直接 404）。

## 观察项（未领号，交后续轮）

1. `/scheduled-tasks` 里 `data-consistency` 最近一次「巡检完成，仍有不一致 5 条」= `data_consistency_record` 里 `status='FAIL'` 的 **5** 条（CROSS_LINK 3、SLOT_PHYSICAL 1、DEVICE_LEDGER 1）⇒ 页面与任务口径互证，但 dev 库确有 5 条未平账，属 **R11 业务流数据面**，非 UI 缺陷。
2. `/observability` 六个 iframe 一次全挂（`v-for` + `v-show`），切页签不重新加载＝刻意换即时性；代价是打开即并发 6 个 Grafana 看板查询。R10 横切面可评估「首次访问才挂载」。
3. `ops:vision:export`（F 码）在前后端同样 0 命中，但它是 `ops:sku:list` 的子节点——**按钮码**里的幽灵族不属本条页面码口径，R10 若收 F 码面再领号。
4. 权限矩阵**正向**全绿：56 个 `NAV_ITEMS.perm` 逐个都有 ACTIVE C/M 行（0 缺失、0 INACTIVE）；反向即 MA-021 那 4 条。

## 待拍板 / 待办（值守期间只记录不动）

1. `/recognition-demo` 幽灵 C 行：删除 or 置 INACTIVE？仍推荐 **INACTIVE + 备注留档**。（同批 1）
2. 权限矩阵专项：`/profile` 是侧栏唯一无 perm 码的页（登录即可见），是否该给它一个 self 码？批 2 结论：**倾向不加**——页内只读本人资料、改密与 2FA 绑定本身就是本人动作，加码只多一条需要维护的授权面。
3. **MA-020 三档拍板**（值守期按「实在需要我」处理：只登记 + 推荐 B，未动码）。
