package com.aicabinet.trade.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通用数据管理（{@code /ops/admin/data/**}）**排除规则**的策略单测。
 *
 * <p>🔴 缺陷（2026-09-23 取证）：原 {@code EXCLUDED_TABLES} 是纯枚举 7 张，而同族的
 * {@code ops_user_department} / {@code ops_user_device_scope} / {@code ops_user_device_scope_pref} /
 * {@code ops_user_merchant} / {@code ops_user_route_scope} 与 {@code ops_2fa_recovery_code}
 * 全漏在保护外（另有 {@code sys_oper_log} / {@code system_config} / {@code user_account}）。
 * 即「权限 / 凭据 / 资金」语义的表有 9 张可经通用通道增删改 —— 尤其是
 * {@code ops_2fa_recovery_code}（改写 {@code code_hash} 即绕过 2FA）。</p>
 *
 * <p>本类**刻意不连库、不启 Spring**（{@code AdminDataManageService.isExcluded} 是包级可见的纯函数）：
 * 枚举型清单的失效形态是 **fail-open**（新增一张敏感表，没有任何东西提醒你补清单），
 * 所以保护规则的判据必须**不依赖「有没有人想起来跑一次集成测试」**——它得是纯逻辑、必跑、且
 * 能对**新增实体**自动报警（见 {@link #domainOpsEntities_areEitherExcludedOrKnownBusinessTable()}）。</p>
 */
class AdminDataManageTablePolicyTest {

    /**
     * {@code ops_} 前缀但**确属业务表**、运营经通用通道订正属正常。
     *
     * <p>这份清单是**有意手工维护的**：它把「哪些 ops_ 表是可操作的」从隐式变成显式，
     * 于是新增一张 ops_ 体系表时，{@link #domainOpsEntities_areEitherExcludedOrKnownBusinessTable()}
     * 会因为「既没被排除、也不在这里」而变红 —— 强迫做一次归类决策，而不是静默放开。</p>
     */
    private static final Set<String> BUSINESS_OPS_TABLES = Set.of(
            "ops_exception", // 异常单：运营要关单/订正
            "ops_department", // 组织架构
            "ops_device_org", // 设备归属组织
            "ops_org_node"); // 组织树节点

    /** 显式清单里的表（前缀表达不了的） */
    private static final Set<String> EXPLICITLY_EXCLUDED = Set.of(
            "flyway_schema_history",
            "admin_audit_log",
            "sys_oper_log",
            "ops_permission",
            "ops_2fa_recovery_code",
            "system_config",
            "user_account");

    /** 整族前缀覆盖的表 —— 含**修复前漏掉**的那 6 张 */
    private static final Set<String> PREFIX_COVERED = Set.of(
            "ops_user",
            "ops_user_role",
            "ops_user_department",
            "ops_user_device_scope",
            "ops_user_device_scope_pref",
            "ops_user_merchant",
            "ops_user_route_scope",
            "ops_role",
            "ops_role_permission");

    /** 应当**可以**经通用通道操作的业务表（防止「把所有表都禁掉」式的过度收紧） */
    private static final Set<String> MUST_STAY_MANAGED = Set.of(
            "ops_exception",
            "ops_department",
            "ops_device_org",
            "ops_org_node",
            "cabinet_order",
            "repair_ticket",
            "user_info",
            "shopping_session");

    @Test
    void explicitAndPrefixCoveredTables_areAllExcluded() {
        List<String> leaks = Stream.concat(EXPLICITLY_EXCLUDED.stream(), PREFIX_COVERED.stream())
                .filter(t -> !AdminDataManageService.isExcluded(t))
                .sorted()
                .toList();
        assertTrue(
                leaks.isEmpty(),
                "这些表应被排除规则挡住却漏了（可经通用通道增删改）：" + leaks
                        + "。若它们是后台账号/权限/凭据体系表，请补进 EXCLUDED_TABLES 或 EXCLUDED_PREFIXES");
    }

    /**
     * 🔴 **回归锁定**：这 6 张是修复前**真实漏掉**的表，逐个钉住 —— 单独列出是为了让失败信息
     * 直接指向「同族表没被整族覆盖」这个根因，而不是淹没在一条大清单里。
     */
    @Test
    void previouslyLeakedOpsFamilyTables_areNowExcluded() {
        List<String> leaked = List.of(
                        "ops_user_department",
                        "ops_user_device_scope",
                        "ops_user_device_scope_pref",
                        "ops_user_merchant",
                        "ops_user_route_scope",
                        "ops_2fa_recovery_code")
                .stream()
                .filter(t -> !AdminDataManageService.isExcluded(t))
                .sorted()
                .toList();
        assertTrue(leaked.isEmpty(), "修复前漏掉的同族表又漏了：" + leaked);
    }

    /**
     * **自动跟随**：同族新表（本仓库现在还不存在的）必须**天生**被挡 —— 这正是从「枚举」改为
     * 「前缀」的目的。若将来有人把前缀规则退回成枚举，本判据会红。
     */
    @Test
    void opsFamilyPrefixRule_coversFutureSiblingTables() {
        List<String> futureTables = List.of(
                        "ops_user_permission_override",
                        "ops_user_login_audit",
                        "ops_user_store_scope",
                        "ops_role_scope",
                        "ops_role_menu")
                .stream()
                .filter(t -> !AdminDataManageService.isExcluded(t))
                .sorted()
                .toList();
        assertTrue(
                futureTables.isEmpty(),
                "同族**新**表没被前缀规则自动覆盖：" + futureTables + "。整族前缀规则已退化成枚举？");
    }

    @Test
    void businessTables_stayManaged() {
        List<String> wronglyExcluded = MUST_STAY_MANAGED.stream()
                .filter(AdminDataManageService::isExcluded)
                .sorted()
                .toList();
        assertTrue(
                wronglyExcluded.isEmpty(),
                "这些是运营本来就该能订正的业务表，却被过度排除：" + wronglyExcluded
                        + "。⚠️ 特别注意 ops_exception 是集成测试的夹具表，被排除会让 AdminDataManage* 测试全红");
    }

    /** {@code null} 必须当成「不可操作」（fail-closed），不能因为「不是已知表」而放行。 */
    @Test
    void nullIsExcluded() {
        assertTrue(AdminDataManageService.isExcluded(null), "null 必须 fail-closed 拒绝");
    }

    // —— 列级：凭据 / 业务标识 的分界（与表级正交的第二道防线） ————————————————

    /**
     * 🔴 表级排除挡不住「**同一张表里混着业务列与凭据列**」：{@code user_info} 既要给运营订正资料，
     * 又存着 {@code password_hash}/{@code totp_secret}，而后台管理员账号（{@code ops_user_role.user_id}）
     * 就指向它。所以必须另有一道**列级**判据。
     */
    @Test
    void credentialColumns_areBlocked() {
        List<String> mustBlock = List.of(
                "password_hash", // user_info：改它=接管账号，读它=离线爆破
                "totp_secret", // user_info：读它=直接生成该管理员的一次性密码
                "session_key", // wechat_binding：微信会话密钥
                "code_hash", // ops_2fa_recovery_code
                "request_hash", // idempotency_key：改它可绕过幂等
                "api_token",
                "credential_id",
                "password_salt");
        List<String> missed = mustBlock.stream()
                .filter(c -> !AdminDataManageService.isCredentialColumn(c))
                .sorted()
                .toList();
        assertTrue(missed.isEmpty(), "这些凭据/密钥列没被挡住（可经通用通道读出或改写）：" + missed);
    }

    /**
     * 反向对账：别把 {@code *_key} 这类**业务标识**一起禁掉 —— 库里有
     * {@code task_key}/{@code dept_key}/{@code role_key}/{@code config_key}/{@code dedup_key} 等
     * 一大批，都是运营订正数据的正常对象。这条判据钉住「用子串而不是裸 `_key` 后缀」这个决策。
     */
    @Test
    void businessColumns_areNotMistakenForCredentials() {
        List<String> business = List.of(
                "task_key", "dept_key", "role_key", "config_key", "dedup_key", "limit_key",
                "idempotency_key", "template_key", "check_key", "table1_key",
                "total_amount_cents", "status", "phone_number", "created_at", "remark");
        List<String> wronglyBlocked = business.stream()
                .filter(AdminDataManageService::isCredentialColumn)
                .sorted()
                .toList();
        assertTrue(wronglyBlocked.isEmpty(), "这些是业务列，被列级规则误伤了：" + wronglyBlocked);
    }

    @Test
    void nullColumnIsCredential() {
        assertTrue(AdminDataManageService.isCredentialColumn(null), "null 列名必须 fail-closed 拒绝");
    }

    // —— 核心守卫：源码实体 ↔ 保护规则 双向对账 ——————————————————————————

    private static final Pattern TABLE_NAME_ANNOTATION =
            Pattern.compile("@TableName\\(\"(ops_[a-z0-9_]+)\"\\)");

    /** 源码里 {ops_ 实体表} 的扫描路径（相对模块根 = Maven surefire 的 working directory）。 */
    private static final Path DOMAIN_DIR =
            Path.of("src", "main", "java", "com", "aicabinet", "trade", "domain");

    /**
     * 🔴 **核心判据（自动跟随全文）**：源码里每一个 {@code @TableName("ops_*")} 实体，
     * 必须**要么**被排除规则挡住（= 后台体系表，不可经通用通道操作），**要么**显式登记在
     * {@link #BUSINESS_OPS_TABLES} 里（= 确属业务表）。
     *
     * <p>两条路都不走的，只有一种可能：**有人新增了一张 ops_ 体系表，却忘了保护它** ——
     * 这正是本次缺陷的成因（10 张体系表只挡了 5 张）。本判据让「忘记」从静默 fail-open
     * 变成**测试当场变红**，而不是等到有人拿通用通道把 {@code code_hash} 改掉才发现。</p>
     *
     * <p>⚠️ 自检：{@code found.size() >= 10}。若扫描锚点（目录 / 注解格式）失效导致「零发现」，
     * 下面的断言会恒真全绿 —— 那是比缺陷本身更坏的假绿，所以必须显式拦住。</p>
     */
    @Test
    void domainOpsEntities_areEitherExcludedOrKnownBusinessTable() throws IOException {
        assertTrue(Files.isDirectory(DOMAIN_DIR), "扫描目录不存在 —— 判据已失效：" + DOMAIN_DIR.toAbsolutePath());

        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(DOMAIN_DIR)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = TABLE_NAME_ANNOTATION.matcher(Files.readString(file));
                while (m.find()) {
                    found.add(m.group(1));
                }
            }
        }

        assertTrue(
                found.size() >= 10,
                "只从 "
                        + DOMAIN_DIR
                        + " 解析出 "
                        + found.size()
                        + " 个 ops_ 实体表（预期 ≥10）—— 扫描锚点已失效，本判据会恒真全绿。实际解析到："
                        + found);

        List<String> unclassified = found.stream()
                .filter(t -> !AdminDataManageService.isExcluded(t))
                .filter(t -> !BUSINESS_OPS_TABLES.contains(t))
                .sorted()
                .toList();
        assertTrue(
                unclassified.isEmpty(),
                "这些 ops_ 实体表既没被排除规则挡住、也不在已知业务表清单里 ⇒ 新增了后台体系表却忘了保护："
                        + unclassified
                        + "。请二选一：①若是账号/权限/凭据体系表，补进 EXCLUDED_TABLES 或 EXCLUDED_PREFIXES；"
                        + "②若确属业务表，登记进本测试的 BUSINESS_OPS_TABLES 并说明理由");

        // 反向对账：业务表清单里不该混进「其实是被排除的表」（避免清单腐烂成漂亮的谎话）
        List<String> stale = BUSINESS_OPS_TABLES.stream()
                .filter(AdminDataManageService::isExcluded)
                .sorted()
                .toList();
        assertTrue(stale.isEmpty(), "BUSINESS_OPS_TABLES 里混入了已被排除的表（清单已过期）：" + stale);
    }
}
