#!/usr/bin/env node
/**
 * O9 Flyway 治理：**种子 / 结构分离**（只向前生效，历史迁移不动）。
 *
 *   node scripts/check-flyway-seed-separation.mjs
 *   MIGRATION_BASE_REF=origin/main node scripts/check-flyway-seed-separation.mjs
 *
 * 约定见 `db/seed/README.md`：结构 = `db/migration/V*.sql`（一次性、checksum 不可变）；
 * 种子 = `db/seed/R__seed_*.sql`（可重复、**必须幂等**）。
 *
 * 🔴 判据取向（刻意**不猜语义**）：
 *    本脚本不去猜「这条 INSERT 算种子还是算 backfill」—— 那是个模糊判断，猜错就是假红，
 *    而假红会让门禁被人绕过去然后删掉。改为**强制显式声明**：
 *      · 新增 `V*.sql` 若含数据写语句，必须自己声明 `MIGRATION_KIND: schema|backfill`
 *        （声明 `seed` ⇒ 直接红，种子不许留在 V 里）；
 *      · 声明 `schema` 却写数据 ⇒ 红（要么改 backfill，要么挪去 seed）。
 *    这样「把种子塞进 V」不再是顺手能做的事，而是一次自觉且有记录的决定。
 * 🔴 历史 277 个迁移天然 grandfather：按「相对基线新增」判定，不回溯。
 *    改写已应用迁移 = checksum 漂移，需要每台环境 `flyway repair` —— 明确不做。
 *
 * 另附一条配置锚点（2026-10-24 任务 #40）：`spring.flyway.out-of-order` 必须为 true。
 *    仓里存在**已发布**的编号倒挂（V398/V399/V401 < V400/V402），只要这些文件还在，
 *    任何「跑过 400/402、缺 398/399/401」的库（备份还原、灾备、老 staging）都会在
 *    validate 阶段被拒——而失败点在生产启动，不是 CI（CI 永远起空库）。改文件名不可行：
 *    已应用旧号的库会反过来报 `applied migration not resolved locally`。
 *    ⇒ 这条配置被删 = 那类库起不来，故在这里钉死（理由详见 lessons #342）。
 */
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gitAsync } from './lib/async-spawn.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const MIGRATION_DIR = 'services/trade-service/src/main/resources/db/migration';
const SEED_DIR = 'services/trade-service/src/main/resources/db/seed';
const APP_YML = 'services/trade-service/src/main/resources/application.yml';
const PROD_YML = 'services/trade-service/src/main/resources/application-prod.yml';

const errors = [];

function err(m) {
  errors.push(m);
}
function read(rel) {
  try {
    return readFileSync(join(root, rel), 'utf8');
  } catch (e) {
    err(`读不到 ${rel}: ${e.message}`);
    return '';
  }
}
/** R11-B-N2 全量兜底：枚举 migration 目录下全部 V 迁移的仓库相对路径。 */
function listAllV() {
  const dir = join(root, MIGRATION_DIR);
  try {
    return readdirSync(dir)
      .filter((n) => /^V\d+__.*\.sql$/i.test(n))
      .sort()
      .map((n) => `${MIGRATION_DIR}/${n}`);
  } catch (e) {
    err(`枚举 ${MIGRATION_DIR} 失败: ${e.message}`);
    return [];
  }
}
function git(args) {
  // 🔴 异步 spawn：WorkBuddy 环境下 spawnSync 一律 EBUSY/status=null（lessons #292），
  // 那样会让本门禁把「git 跑不起来」误判成「有不合规迁移」。
  return gitAsync(args, { cwd: root });
}

/** 去 SQL 注释（`--` 行注释与块注释）。用于判「有没有真的写数据」。 */
function stripSqlComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/--[^\n]*/g, '');
}

// ── 锚点：目录与配置必须真的存在，否则本门禁会「扫不到任何东西却常绿」 ────────
for (const d of [MIGRATION_DIR, SEED_DIR]) {
  if (!existsSync(join(root, d))) {
    err(`${d} 不存在 —— 扫描锚点失效，本门禁会恒绿`);
  }
}
const appYml = read(APP_YML);
const locM = appYml.match(/locations\s*:\s*(.+)/);
if (!locM) {
  err(`${APP_YML} 里找不到 spring.flyway.locations`);
} else {
  const locs = locM[1];
  if (!locs.includes('classpath:db/migration')) {
    err(`flyway.locations 不含 classpath:db/migration（实际：${locs.trim()}）`);
  }
  if (!locs.includes('classpath:db/seed')) {
    err(
      `flyway.locations 不含 classpath:db/seed ⇒ 写进 db/seed 的种子会被 Flyway **静默忽略**` +
        `（实际：${locs.trim()}）`
    );
  }
}
// prod 必须保留种子守卫，否则种子会直接落到生产
const prodYml = read(PROD_YML);
if (prodYml && !/seed_env\s*:\s*none/.test(prodYml)) {
  err(`${PROD_YML} 的 spring.flyway.placeholders.seed_env 不再是 none —— 生产会执行种子语句`);
}

// 2026-10-24 任务 #40：已发布的倒挂（V398/V399/V401 < V400/V402）只能靠 out-of-order 兜
if (!/^\s+out-of-order\s*:\s*true\s*$/m.test(appYml)) {
  err(
    `${APP_YML} 缺 spring.flyway.out-of-order: true —— 本仓存在已发布的编号倒挂` +
      `（V398/V399/V401 < V400/V402），删掉它 = 任何「跑过 400/402 而缺 398/399/401」的库` +
      `（备份还原/灾备/老 staging）启动即 Validate failed；改文件名会让已应用旧号的库反过来` +
      `报 applied migration not resolved locally（见 lessons #342）`
  );
}

// ── 新增文件（相对基线）─────────────────────────────────────────────────────
const baseRef = process.env.MIGRATION_BASE_REF || 'origin/dev';
let diff = await git([
  'diff',
  '--name-only',
  '--diff-filter=A',
  `${baseRef}...HEAD`,
  '--',
  MIGRATION_DIR,
  SEED_DIR
]);
if (!diff.ok) {
  diff = await git([
    'diff',
    '--name-only',
    '--diff-filter=A',
    'HEAD',
    '--',
    MIGRATION_DIR,
    SEED_DIR
  ]);
}
// 刻意不加 --exclude-standard：未跟踪的 .sql 同样是一次真实的新迁移（与 check:migration-safety 同口径）
const untracked = await git(['ls-files', '--others', '--', MIGRATION_DIR, SEED_DIR]);
if (!diff.ok && !untracked.ok) {
  err('git 不可用，无法判定新增迁移 —— 拒绝静默放行');
}

const files = [
  ...new Set(
    [
      ...(diff.ok && diff.out ? diff.out.split(/\r?\n/) : []),
      ...(untracked.ok && untracked.out ? untracked.out.split(/\r?\n/) : [])
    ]
      .map((s) => s.trim())
      .filter((s) => /\.sql$/i.test(s))
  )
];

const DATA_WRITE = /\b(INSERT\s+INTO|DELETE\s+FROM|TRUNCATE|UPDATE\s+[A-Za-z_][\w."]*\s+SET)\b/i;

// ── 🔴 演示锚点不得再进 V 迁移的写语句（C-2026-10-13 生产实证的结构性缺陷）──────────
// 历史迁移（V2…V302）在 seed_env 守卫（V202）出现前内嵌了演示数据 ⇒ 版本迁移全环境必跑，
// 任何全新环境（含未来重建的生产）都会长出演示 SKU/商户/柜子（生产实证：商品管理/选品诊断
// 出现 8 个演示 SKU）。V344 是一次性补偿清理。本规则防复发：
// 新增 V 迁移的**写语句**里引用演示锚点 ⇒ 红。演示数据必须放 db/seed/R__seed_*
// （application-prod.yml 的 seed_env:none 会跳过，生产天然干净）。
// 判定口径（与写侧对称）：
//   · 语句级：注释剥离后按分号切段，语句含 INSERT/UPDATE/MERGE 且命中演示锚点 ⇒ 红；
//   · 纯 DELETE 清理（V344 形态）与 DDL 不拦；
//   · 写目标全部是 `_` 前缀临时表（守卫迁移的内部夹具，如 V344 的 _demo_user）不拦。
const DEMO_ANCHOR =
  /MCH-(?:DEFAULT|OTHER)|SKU-(?:DEMO|SODA|WATER|SNACK|MILK|NOODLE|APPLE)-\d+|WH-DEMO-\d+|SUP-DEMO-\d+|(?:PO|BR)-DEMO-\d+|CAB-(?:001|OTHER)|demo-rec-|1380013800[01]/;

function demoWriteViolation(raw) {
  const body = stripSqlComments(raw);
  for (const stmt of body.split(';')) {
    if (!DEMO_ANCHOR.test(stmt)) continue;
    const targets = [
      ...stmt.matchAll(/\b(?:INSERT\s+INTO|UPDATE|(?:MERGE\s+INTO))\s+([A-Za-z_][\w."]*)/gi)
    ].map((m) => m[1]);
    if (!targets.length) continue; // 只有 SELECT/DELETE 命中锚点 ⇒ 清理语句，放行
    if (targets.every((t) => t.startsWith('_'))) continue; // 临时表夹具，放行
    return stmt.trim().slice(0, 120);
  }
  return null;
}

let vCount = 0;
let rCount = 0;

for (const rel of files) {
  const raw = read(rel);
  if (!raw) continue;
  const name = rel.split('/').pop();

  if (rel.startsWith(SEED_DIR + '/')) {
    rCount++;
    if (!/^R__seed_[A-Za-z0-9_]+\.sql$/.test(name)) {
      err(`${rel}: 种子文件名必须形如 R__seed_<用途>.sql（可重复迁移必须用 R__ 前缀）`);
    }
    const body = stripSqlComments(raw);
    const idem = /ON\s+CONFLICT/i.test(body) || /WHERE\s+NOT\s+EXISTS/i.test(body);
    if (!idem) {
      err(
        `${rel}: 可重复种子**必须幂等**（至少要出现 ON CONFLICT 或 WHERE NOT EXISTS）——` +
          `R__ 在 checksum 变化时会重跑，不幂等会重复插入或直接报错`
      );
    }
    if (/\bTRUNCATE\b/i.test(body)) {
      err(`${rel}: 种子不得 TRUNCATE —— 重跑会反复清库`);
    }
    for (const stmt of body.split(';')) {
      if (/\bDELETE\s+FROM\b/i.test(stmt) && !/\bWHERE\b/i.test(stmt)) {
        err(`${rel}: 无条件 DELETE FROM —— 可重复迁移下会反复清表`);
        break;
      }
    }
    continue;
  }

  if (rel.startsWith(MIGRATION_DIR + '/')) {
    vCount++;
    const kindM = raw.match(/MIGRATION_KIND\s*:\s*(schema|backfill|seed)\b/i);
    const kind = kindM ? kindM[1].toLowerCase() : '';
    const writes = DATA_WRITE.test(stripSqlComments(raw));

    if (kind === 'seed') {
      err(
        `${rel}: 声明 MIGRATION_KIND: seed ⇒ 种子不许放在 V 迁移里。` +
          `请改为 db/seed/R__seed_<用途>.sql（可重复 + 幂等），见 db/seed/README.md`
      );
    } else if (!kind && writes) {
      err(
        `${rel}: 含数据写语句但未声明种类。请在文件头加一行 ` +
          `\`-- MIGRATION_KIND: backfill\`（一次性数据修复，属 V 的正当用途）或 ` +
          `\`-- MIGRATION_KIND: schema\`；若其实是种子数据，请移到 db/seed/R__seed_*.sql`
      );
    } else if (kind === 'schema' && writes) {
      err(
        `${rel}: 声明 MIGRATION_KIND: schema 却含数据写语句 —— 二者矛盾，请改 backfill 或移入 db/seed`
      );
    }
    if (!kind || kind === 'backfill') {
      const v = demoWriteViolation(raw);
      if (v) {
        err(
          `${rel}: 写语句引用了演示锚点（…${v}…）—— 演示数据不得再进版本迁移` +
            `（V2…V302 内嵌演示种子曾在生产长出可见演示数据，V344 已做一次性清理）。` +
            `请把演示数据移到 db/seed/R__seed_*.sql（生产 seed_env:none 自动跳过）；` +
            `若确属生产必需的数据修复，请改写为不依赖演示锚点`
        );
      }
    }
    continue;
  }

  err(`${rel}: 落在预期目录之外（既非 ${MIGRATION_DIR} 也非 ${SEED_DIR}）`);
}

// ── 汇总 ────────────────────────────────────────────────────────────────────
if (files.length === 0) {
  console.log('[check-flyway-seed-separation] 无相对基线新增的迁移；锚点与配置已校验，OK');
} else {
  console.log(
    `[check-flyway-seed-separation] 新增 V 迁移 ${vCount} 个、种子 ${rCount} 个（文件共 ${files.length}）`
  );
}

// ── 🔴 R11-B-N2：全量兜底棘轮（堵「只查相对 origin/dev 新增」的永久盲区）──────────
// 背景：V451/V452/V453 经分支直合通道入库，未过本门禁增量扫描 ⇒ 「含数据写语句但缺
// MIGRATION_KIND 声明」永久隐形。历史存量（规则生效前的 V 迁移）达 200+ 条，逐条
// 白名单必腐化 ⇒ 采用项目既有「冻结基线棘轮」：基线内嵌为常量，**只减不增**——
// 全量扫描命中的违例若不在基线 ⇒ 红；基线中的文件日后补了头注释可移除条目。
// ⚠️ 铁律 50：基线条目（已应用迁移）不可直改补注释，只能登记豁免。
const baselineRef = new Set([
  'V102__device_refund_policy.sql',
  'V104__device_ops_and_exception_sla.sql',
  'V106__export_import_button_perms.sql',
  'V107__user_verify_button_perm.sql',
  'V108__promotion_launch_perm.sql',
  'V109__align_ota_sla_vision_nav.sql',
  'V10__concurrency_control.sql',
  'V110__order_refund_perm.sql',
  'V111__procurement_perms.sql',
  'V113__dispute_review_code.sql',
  'V115__unpaid_order_ops.sql',
  'V116__finance_role_and_merchant_split_perm.sql',
  'V117__demo_ops_role_accounts.sql',
  'V118__tighten_demo_role_perms.sql',
  'V119__fix_garbled_merchant_names.sql',
  'V11__production_sku_seed.sql',
  'V120__fix_garbled_dispute_reasons.sql',
  'V121__merchant_menu_tree_groups.sql',
  'V123__align_three_end_data_consistency.sql',
  'V124__ops_consistency_menu.sql',
  'V125__competitive_gap_features.sql',
  'V126__align_ops_menu_with_sidebar.sql',
  'V127__device_asset_lifecycle.sql',
  'V128__remove_invite_and_commercial_hub.sql',
  'V132__device_map_and_repair_ticket.sql',
  'V133__line_manager_wallet.sql',
  'V136__remove_points_and_messaging_legacy.sql',
  'V137__purge_inactive_ops_permissions.sql',
  'V138__align_menus_with_sidebar.sql',
  'V139__merchant_withdraw.sql',
  'V13__ops_audit_permission.sql',
  'V140__merchant_team_roles.sql',
  'V142__admin_crud_button_perms.sql',
  'V143__device_info_auto_id.sql',
  'V144__sku_master_code_and_fields.sql',
  'V145__sku_local_demo_images.sql',
  'V146__sku_local_demo_images_by_code.sql',
  'V149__fix_garbled_sku_catalog_names.sql',
  'V14__vision_upload_permissions.sql',
  'V150__device_ref_perm.sql',
  'V151__device_online_since_and_availability_kpi.sql',
  'V152__scheduled_task_module.sql',
  'V153__device_kpi_perm.sql',
  'V154__bigscreen_perm.sql',
  'V157__sku_real_product_photos.sql',
  'V158__cleanup_remaining_demo_svg_images.sql',
  'V15__merchant_revenue_split.sql',
  'V162__fix_merchant_replenisher_demo_account.sql',
  'V163__growth_marketing_modules.sql',
  'V165__points_coupon_expiry_recall.sql',
  'V166__notify_prefs_points_idempotent.sql',
  'V167__marketing_roi_perm.sql',
  'V168__notify_channels_member_levels.sql',
  'V169__merchant_line_wallet_withdraw_perm.sql',
  'V170__new_pages_menu_perms.sql',
  'V171__scheduled_task_group_wallet_entry_dict.sql',
  'V177__tighten_viewer_write_perms.sql',
  'V178__viewer_revoke_exports_and_repair_closed_dict.sql',
  'V179__announcement_status_archived_label.sql',
  'V180__promotion_and_consistency_dict_labels.sql',
  'V181__obs_viewer_ops_coords_distance.sql',
  'V182__fix_garbled_names_and_demo_points.sql',
  'V183__unlock_grace_and_sanitize_feedback.sql',
  'V184__notify_title_strip_hash_id.sql',
  'V185__balance_refund_request.sql',
  'V186__merchant_dispute_resolve_perm.sql',
  'V188__refund_self_policy_defaults.sql',
  'V189__nearby_coupon_member_discount.sql',
  'V190__invoice_and_mid_pri.sql',
  'V191__low_pri_risk_ad_demo.sql',
  'V192__wave1_onboard_device_scope.sql',
  'V195__session_refund_lock_fee.sql',
  'V196__session_kind_consumer_dict.sql',
  'V197__fix_viewer_invoice_edit_and_titles.sql',
  'V199__settlement_recognition_mode.sql',
  'V19__sku_demo_enrich.sql',
  'V200__fix_demo_four_consistency_failures.sql',
  'V201__fix_points_consistency_checker_align.sql',
  'V202__consistency_prod_guards_align.sql',
  'V207__order_revenue_split_order_id_unique.sql',
  'V209__clamp_device_slot_over_capacity.sql',
  'V20__user_password.sql',
  'V210__purge_orphan_device_slots.sql',
  'V211__device_slot_device_fk.sql',
  'V212__merchant_wallet_merchant_fk.sql',
  'V213__device_inventory_fk.sql',
  'V214__warehouse_inventory_fk.sql',
  'V215__transaction_chain_fk.sql',
  'V216__ops_transit_slot_fk.sql',
  'V217__ops_ad_scope_fk.sql',
  'V218__user_order_line_fk.sql',
  'V219__merchant_order_slot_fk.sql',
  'V220__coupon_ops_slot_fk.sql',
  'V221__admin_audit_log_operator_fk.sql',
  'V222__core_table_comments_and_demo_seed.sql',
  'V223__category_id_backfill_and_empty_module_seed.sql',
  'V224__verify_channel_dict.sql',
  'V225__notification_biz_type_dict.sql',
  'V226__platform_bill_trade_type_dict.sql',
  'V227__pull_off_reason_dict.sql',
  'V228__approval_workflow.sql',
  'V229__devops_hub_perm.sql',
  'V230__approval_biz_extensions.sql',
  'V231__ops_department_and_approval_config.sql',
  'V232__fix_approval_list_perm_path.sql',
  'V234__devops_sonar_scan_perm.sql',
  'V235__fix_system_user_name_encoding.sql',
  'V23__ops_commercial_enhancements.sql',
  'V244__member_level_demo_discount.sql',
  'V246__ops_inbox_approval_reminder_copy.sql',
  'V248__finance_warehouse_list_revoke_devops.sql',
  'V249__revoke_devops_non_admin.sql',
  'V250__fix_device_100001_name_encoding.sql',
  'V251__demo_supplier_name_zh.sql',
  'V252__seed_uat_order_amount_diff.sql',
  'V254__uat_pending_purchase_approval.sql',
  'V256__device_data_fee_bill.sql',
  'V257__consumer_preauth_hold.sql',
  'V258__user_account_type.sql',
  'V259__merchant_alert_sales_locked.sql',
  'V25__demo_catalog_seed.sql',
  'V261__revoke_staff_team_list_perm.sql',
  'V262__finance_dept_member_and_stuck_approval_tasks.sql',
  'V263__bug004_skip_auto_lock_never_heartbeat.sql',
  'V264__clarify_replenisher_role_names.sql',
  'V265__platform_replenisher_merchant_portal.sql',
  'V266__replenishment_require_evidence_config.sql',
  'V267__replenishment_checkin_door_config.sql',
  'V268__consistency_check_type_extend.sql',
  'V26__warehouse_phase_b.sql',
  'V271__cross_link_and_inventory_guard.sql',
  'V272__dispute_resolution_dict.sql',
  'V273__ops_operator_reset_password.sql',
  'V275__scheduled_task_seed_gap.sql',
  'V276__device_auto_unlock_stable_minutes_15_to_5.sql',
  'V278__withdraw_paying_timeout_scheduled_task.sql',
  'V27__phase_c_finance.sql',
  'V28__demo_vision_mapping_seed.sql',
  'V291__org_node_type_dict.sql',
  'V294__merchant_replenisher_trend.sql',
  'V295__merchant_replenisher_trend_revoke.sql',
  'V2__user_order_sku.sql',
  'V30__device_slot_planogram.sql',
  'V315__device_driver_type_and_switch_ledger.sql',
  'V316__enable_promo_slot_for_preview.sql',
  'V319__coupon_scope.sql',
  'V34__procurement_and_warehouse_ledger.sql',
  'V35__merchant_portal.sql',
  'V36__merchant_portal_p1.sql',
  'V37__merchant_portal_p2.sql',
  'V38__merchant_portal_p3.sql',
  'V397__register_ota_publish_and_audit_recent_perms.sql',
  'V39__merchant_settlements_m1.sql',
  'V40__backfill_settlement_batch.sql',
  'V41__merchant_sku_pricing_m2.sql',
  'V42__merchant_dispute_collaboration_m3.sql',
  'V43__merchant_replenishment_request_m4.sql',
  'V449__drop_merchant_replen_request_approval.sql',
  'V44__merchant_scope_hardening_m5.sql',
  'V450__drop_recognition_needs_review_dict.sql',
  'V451__fix_menu_perm_type.sql',
  'V452__jiangyi_product_state_poll_task.sql',
  'V453__stocktake_mode_drop_legacy_visible.sql',
  'V45__merchant_analytics_m6.sql',
  'V46__merchant_finance_notify_m7.sql',
  'V48__ops_finance_permission.sql',
  'V49__merchant_self_service_flags.sql',
  'V4__dispute_ops.sql',
  'V50__demo_merchant_self_service.sql',
  'V53__mock_recharge_idempotency.sql',
  'V54__simulator_demo_skus.sql',
  'V55__system_config.sql',
  'V56__vision_mapping_seed.sql',
  'V57__repair_demo_device_name.sql',
  'V58__sku_vision_mapping_yolo_sku.sql',
  'V59__cleanup_min_uat_replenishment_routes.sql',
  'V5__dispute_vision.sql',
  'V60__demo_soda_min_charge_confidence.sql',
  'V61__retail_os_vision_mapping.sql',
  'V62__oriental_leaf_tea_aliyun_mapping.sql',
  'V63__sku_vision_enrollment.sql',
  'V65__sys_dict_and_analytics.sql',
  'V66__coupon_promotion_system.sql',
  'V67__member_feedback_notification.sql',
  'V68__enhance_existing_tables.sql',
  'V70__jsonb_fk_cascade.sql',
  'V71__concurrency_control.sql',
  'V74__payscore_payment.sql',
  'V79__member_management.sql',
  'V86__production_enhancements.sql',
  'V87__clamp_slot_lot_over_capacity.sql',
  'V88__dispute_status_closed_label.sql',
  'V89__coupon_edit_perm.sql',
  'V8__ops_rbac.sql',
  'V90__ruoyi_menu_perms.sql',
  'V92__split_rbac_menus.sql',
  'V93__align_menu_and_button_perms.sql',
  'V94__align_menu_tree_with_sidebar.sql',
  'V95__reparent_button_perms_to_menus.sql',
  'V96__operator_account_status.sql',
  'V97__demo_merchant_wechat_receiver.sql',
  'V99__member_marketing_consumer.sql',
  'V9__commercial_ops.sql'
]);
let kindSweep = 0;
for (const rel of listAllV()) {
  const raw = read(rel);
  if (!raw) continue;
  kindSweep++;
  const name = rel.split('/').pop();
  const hasDataWrite = DATA_WRITE.test(stripSqlComments(raw));
  const hasKind = /MIGRATION_KIND\s*:\s*(schema|backfill)\b/i.test(raw);
  if (hasDataWrite && !hasKind && !baselineRef.has(name)) {
    err(
      `全量兜底：${rel} 含数据写语句但缺 MIGRATION_KIND 声明（增量扫描盲区补扫，R11-B-N2）。` +
        `带头注释 \`-- MIGRATION_KIND: backfill\` 或 \`-- MIGRATION_KIND: schema\``
    );
  }
}
console.log(
  `[check-flyway-seed-separation] 全量兜底：${kindSweep} 个 V 迁移已扫；` +
    `基线冻结 ${baselineRef.size} 条（只减不增，R11-B-N2）`
);

if (errors.length) {
  for (const e of errors) console.error(`[check-flyway-seed-separation] FAIL ${e}`);
  console.error(`[check-flyway-seed-separation] ${errors.length} 处不合规`);
  process.exit(1);
}
console.log('[check-flyway-seed-separation] OK：种子与结构分离策略得到遵守');
