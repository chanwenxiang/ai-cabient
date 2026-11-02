package com.aicabinet.trade.integration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权限闭环反向门禁：库里的 ACTIVE 权限码必须真的被代码引用，代码引用的码也必须真的在库里。
 *
 * <p>已有两条静态门禁只做**正向**（{@code check:menu-permissions} / {@code check:frontend-perm-codes}：
 * 前端引用的码在后端/迁移里存在），没有覆盖反向。R10 §10.2 的三方对账发现过两类反向缺陷：
 * <ul>
 *   <li><b>幽灵菜单</b>：{@code ops_permission} 里 ACTIVE 但前后端零引用 ⇒ 角色树里可勾、勾了不生效
 *       （MA-021/V421、MA-036/V427 两次 RETIRED 的都是这一类）；</li>
 *   <li><b>恒 403</b>：前端导航引用了表中非 ACTIVE 的行 ⇒ 过 {@code hasPerm} 却卡 {@code isNavMenuActive}
 *       （D28-B15 栽过的形态）。</li>
 * </ul>
 *
 * <p>为什么必须是 IT 而不是静态门禁：B 集（{@code ops_permission} 行）只有连库才知道；
 * 从迁移种子半解析会有三种 INSERT 变体 + 后续 RETIRED 抵扣 + 幂等 ON CONFLICT，
 * 漏解析就会把存量正常数据误报成幽灵。本 IT 在 CI 的 integration job（真 PG + Flyway 从零）里跑。
 *
 * <p><b>集合定义</b>：
 * <ul>
 *   <li>A＝后端引用：{@code services/**}{@code /src/main/java} 下
 *       {@code @RequiresPermissions} 注解字面量 <b>+</b> 编程式检查
 *       （{@code hasPerm}/{@code hasPermission}/{@code hasAnyPermission}/{@code requirePermission}/
 *       {@code requireAnyPermission}/{@code requireOwnerPermission} 等）的字符串实参。
 *       🔴 必须双扫：{@code ops:audit:recent} 既出现在 {@code value = {...}} 数组注解里，
 *       也以 {@code requireAnyPermission(...)} 非注解形式使用；只扫一种形态会把它误判成幽灵。</li>
 *   <li>B＝{@code ops_permission} 中 {@code status='ACTIVE'} 且 {@code perm_type IN ('C','M')} 的行。</li>
 *   <li>ALL＝{@code ops_permission} 全部行（含 F 按钮码与 RETIRED），用于 A/C 的正向存在性检查。</li>
 *   <li>C＝{@code clients/**}{@code /src/**}{@code /*.{ts,vue}} 的权限码字面量
 *       （复用 {@code check-frontend-perm-codes} 的形态正则：≥2 个冒号、段内允许连字符）
 *       <b>+</b> {@code menu.ts}/{@code merchant-nav.ts} 的 {@code perm:} 配置项（含单冒号码）。</li>
 * </ul>
 *
 * <p><b>断言方向</b>（全部为 0 才算闭环）：
 * <ol>
 *   <li>{@code B ∖ (A ∪ C) = ∅} —— 幽灵权限码（白名单见下）；</li>
 *   <li>{@code A ∖ ALL = ∅} —— 后端引用了库里根本不存在的码（{@code hasPerm} 恒 false）；</li>
 *   <li>{@code C ∖ ALL = ∅} —— 前端菜单/按钮引用了库里根本不存在的码；</li>
 *   <li>{@code C ∩ (ALL ∖ ACTIVE) = ∅} —— 前端引用但行已 RETIRED（D28-B15 的 403 面）；</li>
 *   <li>A 引用 RETIRED 行也为空 —— 后端门禁指向已退役码时功能对非超管不可达。</li>
 * </ol>
 *
 * <p><b>白名单</b>（只允许结构性节点与既有书面豁免，不得新增同类）：
 * <ul>
 *   <li>{@code ops:nav:*} / {@code merchant:nav:*}（M）：侧栏分组目录行，不进代码（R10 §10.2 第 1 步）；</li>
 *   <li>realm 根 {@code ops} / {@code merchant}（M）；</li>
 *   <li>分组父节点（如 {@code ops:pricing:view}）：本身零引用，但挂着在用的 F 子节点；
 *       V421/V427 已拍板「保留父行，不让按钮无处挂靠」。清单里的子节点必须仍被引用，否则本 IT 红（防清单腐烂）；</li>
 *   <li>V138 菜单/按钮两级分离的既有豁免 {@code ops:recognition-demo:view}
 *       （与 {@code check-menu-permissions} / {@code check-frontend-perm-codes} 的 ALLOWLIST 同源）。</li>
 * </ul>
 * 已 RETIRED 的码不属于 B（查询按 ACTIVE 过滤）；若它们出现在幽灵列表里，说明查询写错，本 IT 用
 * {@link #RETIRED_GUARD} 显式断言兜住。
 *
 * <p><b>为什么要负向自测</b>：若幽灵查询写错（漏 status/type 过滤、拼错列名、集合取反），
 * 结果会恒为空而门禁恒真绿。{@link #negativeProbeMustBeDetected()} 在事务内插入一行假幽灵码，
 * 断言检测函数必须报出它，再回滚。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class PermClosureSqlIT {

    /** V427 退役的 5 个码：出现在 ACTIVE 集即说明查询丢了 status 过滤。 */
    private static final List<String> RETIRED_GUARD = List.of(
            "merchant:coupon:view",
            "ops:operlog:export",
            "ops:upload:export",
            "ops:vision:export",
            "ops:operlog");

    /** realm 根节点（M）。 */
    private static final Set<String> REALM_ROOTS = Set.of("ops", "merchant");

    /** 侧栏分组目录行（M）：结构性节点，侧栏按有无可见子页推导，目录码不进代码。 */
    private static final Pattern NAV_DIRECTORY = Pattern.compile("^(?:ops|merchant):nav:[a-z0-9-]+$");

    /** V138 菜单/按钮两级分离的既有豁免（两条既有静态门禁的 ALLOWLIST 同源）。 */
    private static final Map<String, String> V138_ALLOWLIST = Map.of(
            "ops:recognition-demo:view",
            "V138：识别演示是 C 级菜单，侧栏可见性与按钮(API) ops:sku:demo 刻意分离");

    /** 分组父节点 → 必须仍在用的子码（V421/V427 判据：父行零引用但子节点在用则保留）。 */
    private static final Map<String, String> GROUPING_NODES = Map.of(
            "ops:pricing:view", "ops:pricing:edit");

    /** 权限码形态（与 check-frontend-perm-codes 同规：≥2 个冒号，段内允许连字符）。 */
    private static final Pattern FRONTEND_CODE = Pattern.compile("^[a-z][a-z0-9-]*(?::[a-z0-9-]+){2,}$");

    /** 从字符串字面量里抽权限码（兼容注解里 "a:b:view,c:d:view" 这类逗号串）。 */
    private static final Pattern PERM_TOKEN = Pattern.compile("[a-z][a-z0-9-]*(?::[a-z0-9-]+)+");

    private static final Pattern QUOTED = Pattern.compile("'([^'\n]+)'|\"([^\"\n]+)\"");
    private static final Pattern JAVA_STRING = Pattern.compile("\"([^\"]*)\"");
    private static final Pattern ANNOTATION = Pattern.compile("@RequiresPermissions\\s*\\(");
    private static final Pattern PERMISSION_CALL =
            Pattern.compile("\\b((?:has|require)[A-Za-z]*Permission[s]?|hasPerm)\\s*\\(");
    private static final Pattern MENU_PERM =
            Pattern.compile("perm:\\s*(?:'([^']+)'|\"([^\"]+)\"|\\[([^\\]]*)\\])");

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aicabinet_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> String.valueOf(redis.getMappedPort(6379)));
    }

    @Autowired
    private JdbcTemplate jdbc;

    // ── 数据模型 ──────────────────────────────────────────────────────────────

    private record PermRow(String code, String type, String status, String path, long roleHolds) {}

    private record SourceScan(
            Set<String> annotated,
            Set<String> callSites,
            Set<String> frontend,
            int javaFiles,
            int clientFiles) {

        Set<String> backendReferences() {
            Set<String> all = new LinkedHashSet<>(annotated);
            all.addAll(callSites);
            return all;
        }
    }

    // ── 断言主体 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("权限闭环：库 ACTIVE(C/M) ⊆ 代码引用、代码引用 ⊆ 库行，所有方向全 0")
    void permissionClosureMustBeClosed() throws IOException {
        SourceScan scan = scanSources();
        Map<String, PermRow> allRows = allPermissionRows();
        Map<String, PermRow> activeRows = activeNavRows();
        Set<String> referenced = new LinkedHashSet<>(scan.backendReferences());
        referenced.addAll(scan.frontend());

        // 扫描器自身的护栏：任一侧退化成空集/小集合都会让下面的差集恒真绿。
        assertTrue(scan.javaFiles() >= 200,
                "只扫到 " + scan.javaFiles() + " 个 services 主源码 .java（预期 1000+）——仓库根/路径解析失效");
        assertTrue(scan.clientFiles() >= 100,
                "只扫到 " + scan.clientFiles() + " 个 clients/src 下的 ts/vue（预期 400+）——仓库根/路径解析失效");
        assertTrue(scan.annotated().size() >= 100,
                "A 集注解只抽到 " + scan.annotated().size() + " 个权限码（预期 150+）——注解解析失效");
        assertTrue(scan.callSites().size() >= 40,
                "A 集编程式检查只抽到 " + scan.callSites().size() + " 个权限码（预期 80+）——调用点解析失效");
        assertTrue(scan.frontend().size() >= 100,
                "C 集只抽到 " + scan.frontend().size() + " 个权限码（预期 150+）——前端扫描失效");
        assertTrue(activeRows.size() >= 60,
                "ACTIVE(C/M) 只读到 " + activeRows.size() + " 行（预期 80+）——B 集查询失效");
        assertTrue(allRows.size() >= 150,
                "ops_permission 只读到 " + allRows.size() + " 行（预期 200+）——ALL 集查询失效");

        // V427 已退役的码不得再出现在 ACTIVE 集：查询若丢了 status 过滤，这里先红。
        for (String retired : RETIRED_GUARD) {
            PermRow row = allRows.get(retired);
            assertTrue(row != null && "RETIRED".equals(row.status()),
                    retired + " 不是 RETIRED（V427 未生效/被回滚）——fresh 库上迁移链不完整");
            assertTrue(!activeRows.containsKey(retired),
                    retired + " 出现在 ACTIVE 集里 ⇒ B 集查询丢了 status='ACTIVE' 过滤（会把它误报成幽灵）");
        }

        // 分组父节点白名单不许腐烂：子节点失联时父节点应重新进入幽灵集。
        for (Map.Entry<String, String> e : GROUPING_NODES.entrySet()) {
            PermRow parent = activeRows.get(e.getKey());
            assertTrue(parent != null,
                    "分组节点白名单里的 " + e.getKey() + " 已不是 ACTIVE(C/M) —— 请重审白名单");
            assertTrue(referenced.contains(e.getValue()),
                    "分组节点 " + e.getKey() + " 的在用子节点 " + e.getValue()
                            + " 已零引用 —— 保留父行的理由消失，白名单必须重审");
        }

        // ① 幽灵码：B ∖ (A ∪ C)
        List<String> ghosts = new ArrayList<>();
        for (PermRow row : activeRows.values()) {
            if (referenced.contains(row.code()) || isWhitelisted(row, referenced)) {
                continue;
            }
            ghosts.add(row.code() + "  (type=" + row.type()
                    + ", path=" + (row.path().isEmpty() ? "-" : row.path())
                    + ", 角色持有 " + row.roleHolds() + ")");
        }
        assertEmpty("B∖(A∪C)", "库里 ACTIVE 但前后端零引用（角色树可勾、勾了不生效）", ghosts,
                "修法：沿用 MA-021/V421、MA-036/V427 的 RETIRED 口径迁移置 RETIRED；"
                        + "若确属结构节点/既有豁免，先写清依据再加白名单");

        // ② 后端引用但库里无行：A ∖ ALL
        List<String> missingRows = new ArrayList<>();
        List<String> retiredRefs = new ArrayList<>();
        for (String code : new TreeSet<>(scan.backendReferences())) {
            PermRow row = allRows.get(code);
            if (row == null) {
                missingRows.add(code);
            } else if (!"ACTIVE".equals(row.status())) {
                retiredRefs.add(code + "  (status=" + row.status() + ")");
            }
        }
        assertEmpty("A∖ALL", "后端引用了库里不存在的权限码（hasPerm 恒 false，功能对任何角色不可达）",
                missingRows, "修法：补迁移种行，或改用真实存在的码");
        assertEmpty("A∩非ACTIVE", "后端仍在校验已退役的权限码（功能对非超管不可达）",
                retiredRefs, "修法：恢复该行 ACTIVE，或把代码改到在用的码");

        // ③ 前端引用但库里无行：C ∖ ALL
        List<String> frontendMissing = new ArrayList<>();
        List<String> frontendInactive = new ArrayList<>();
        for (String code : new TreeSet<>(scan.frontend())) {
            PermRow row = allRows.get(code);
            if (row == null) {
                frontendMissing.add(code);
            } else if (!"ACTIVE".equals(row.status())) {
                frontendInactive.add(code + "  (status=" + row.status() + ")");
            }
        }
        assertEmpty("C∖ALL", "前端菜单/按钮引用了库里不存在的权限码（菜单显示但无人能进）",
                frontendMissing, "修法：补迁移种行，或改用真实存在的码");
        assertEmpty("C∩非ACTIVE", "前端引用但行已 RETIRED（过 hasPerm 却卡 isNavMenuActive ⇒ 整页 403）",
                frontendInactive, "修法：恢复 ACTIVE 或改前端引用的码（D28-B15 同形）");
    }

    @Test
    @DisplayName("A 集双扫护栏：注解 value={} 与非注解 requireAnyPermission 都必须收入 ops:audit:recent")
    void nonAnnotationPermissionCallsAreScanned() throws IOException {
        SourceScan scan = scanSources();
        assertTrue(scan.annotated().contains("ops:audit:recent"),
                "注解多值形态（@RequiresPermissions(value = {\"ops:audit:recent\", ...})）漏扫 —— "
                        + "简单正则只认首个字符串时会把它当幽灵（R10 §10.2 反例）");
        assertTrue(scan.callSites().contains("ops:audit:recent"),
                "非注解调用 OpsAuditQueryService#requireAnyPermission(\"ops:audit:recent\", ...) 漏扫 —— "
                        + "A 集必须同时扫注解与编程式检查");
    }

    @Test
    @Transactional
    @DisplayName("负向自测：插入假幽灵码后检测函数必须报出它（防查询写错恒真绿）")
    void negativeProbeMustBeDetected() throws IOException {
        String probe = "ops:__negative_probe__:view";
        jdbc.update("INSERT INTO ops_permission "
                        + "(permission_id, parent_id, perm_code, perm_name, perm_type, sort_order, status) "
                        + "VALUES (?, 0, ?, ?, 'C', 0, 'ACTIVE')",
                990_000_001L, probe, "权限闭环负向探针");
        try {
            SourceScan scan = scanSources();
            assertTrue(!scan.backendReferences().contains(probe) && !scan.frontend().contains(probe),
                    "负向探针码不应出现在源码扫描集里，否则本自测失去判别力");

            List<String> ghosts = detectGhosts(scan);
            assertTrue(ghosts.stream().anyMatch(g -> g.startsWith(probe)),
                    "插入 ACTIVE 幽灵码 " + probe + " 后检测函数没有报出它 —— 查询/差集写错，正向断言会恒真绿。"
                            + " 实际报出：" + ghosts);
        } finally {
            // 显式回滚由 @Transactional 负责；这里再删一次是为了让「自测本身」在事务失效时也不污染库。
            jdbc.update("DELETE FROM ops_permission WHERE perm_code = ?", probe);
        }
    }

    // ── 检测函数（负向自测复用同一实现） ──────────────────────────────────────

    private List<String> detectGhosts(SourceScan scan) {
        Map<String, PermRow> activeRows = activeNavRows();
        Set<String> referenced = new LinkedHashSet<>(scan.backendReferences());
        referenced.addAll(scan.frontend());
        List<String> ghosts = new ArrayList<>();
        for (PermRow row : activeRows.values()) {
            if (referenced.contains(row.code()) || isWhitelisted(row, referenced)) {
                continue;
            }
            ghosts.add(row.code() + "  (type=" + row.type() + ", 角色持有 " + row.roleHolds() + ")");
        }
        return ghosts;
    }

    private boolean isWhitelisted(PermRow row, Set<String> referenced) {
        String code = row.code();
        if ("M".equals(row.type())) {
            if (REALM_ROOTS.contains(code) || NAV_DIRECTORY.matcher(code).matches()) {
                return true;
            }
        }
        if (V138_ALLOWLIST.containsKey(code)) {
            return true;
        }
        String requiredChild = GROUPING_NODES.get(code);
        return requiredChild != null && referenced.contains(requiredChild);
    }

    private void assertEmpty(String direction, String meaning, List<String> offenders, String hint) {
        assertTrue(offenders.isEmpty(), () -> direction + " = " + offenders.size() + " 条（" + meaning + "）：\n  "
                + String.join("\n  ", offenders) + "\n" + hint);
    }

    // ── 数据库侧 ──────────────────────────────────────────────────────────────

    private Map<String, PermRow> activeNavRows() {
        Map<String, PermRow> rows = new TreeMap<>();
        jdbc.query("""
                SELECT p.perm_code, p.perm_type, p.status, coalesce(p.path, '') AS path,
                       (SELECT count(*) FROM ops_role_permission rp WHERE rp.permission_id = p.permission_id) AS holds
                  FROM ops_permission p
                 WHERE p.status = 'ACTIVE' AND p.perm_type IN ('C', 'M')
                 ORDER BY p.perm_code
                """, rs -> {
            rows.put(rs.getString("perm_code"), new PermRow(
                    rs.getString("perm_code"),
                    rs.getString("perm_type"),
                    rs.getString("status"),
                    rs.getString("path"),
                    rs.getLong("holds")));
        });
        return rows;
    }

    private Map<String, PermRow> allPermissionRows() {
        Map<String, PermRow> rows = new TreeMap<>();
        jdbc.query("""
                SELECT p.perm_code, p.perm_type, p.status, coalesce(p.path, '') AS path,
                       (SELECT count(*) FROM ops_role_permission rp WHERE rp.permission_id = p.permission_id) AS holds
                  FROM ops_permission p
                 ORDER BY p.perm_code
                """, rs -> {
            rows.put(rs.getString("perm_code"), new PermRow(
                    rs.getString("perm_code"),
                    rs.getString("perm_type"),
                    rs.getString("status"),
                    rs.getString("path"),
                    rs.getLong("holds")));
        });
        return rows;
    }

    // ── 源码扫描 ──────────────────────────────────────────────────────────────

    private static SourceScan scanSources() throws IOException {
        Path root = repoRoot();

        List<Path> javaFiles = walk(root.resolve("services"),
                p -> p.getFileName().toString().endsWith(".java") && normalize(p).contains("/src/main/java/"));
        assertTrue(javaFiles.size() >= 200,
                "services 下只找到 " + javaFiles.size() + " 个主源码 .java —— 仓库根解析成 " + root);

        Set<String> annotated = new LinkedHashSet<>();
        Set<String> callSites = new LinkedHashSet<>();
        for (Path file : javaFiles) {
            String src = Files.readString(file, StandardCharsets.UTF_8);
            collectBalanced(src, ANNOTATION, annotated);
            collectBalanced(src, PERMISSION_CALL, callSites);
        }

        List<Path> clientFiles = walk(root.resolve("clients"),
                p -> {
                    String name = p.getFileName().toString();
                    return (name.endsWith(".ts") || name.endsWith(".vue")) && normalize(p).contains("/src/");
                });
        assertTrue(clientFiles.size() >= 100,
                "clients 下只找到 " + clientFiles.size() + " 个 src 内的 ts/vue —— 仓库根解析成 " + root);

        Set<String> frontend = new LinkedHashSet<>();
        for (Path file : clientFiles) {
            String src = Files.readString(file, StandardCharsets.UTF_8);
            for (Matcher m = QUOTED.matcher(src); m.find(); ) {
                String literal = m.group(1) != null ? m.group(1) : m.group(2);
                if (literal != null && FRONTEND_CODE.matcher(literal).matches() && !literal.contains("/")) {
                    frontend.add(literal);
                }
            }
        }
        // menu.ts / merchant-nav.ts 的 perm: 配置项（可能只有 1 个冒号，如 ops:dispute）
        for (String nav : List.of(
                "clients/admin-vue/src/config/menu.ts",
                "clients/merchant-mp/src/config/merchant-nav.ts")) {
            Path navFile = root.resolve(nav);
            assertTrue(Files.isRegularFile(navFile), "缺少导航配置 " + nav + "（R10 §10.2 的 C 集来源）");
            String src = Files.readString(navFile, StandardCharsets.UTF_8);
            for (Matcher m = MENU_PERM.matcher(src); m.find(); ) {
                if (m.group(1) != null || m.group(2) != null) {
                    frontend.add(m.group(1) != null ? m.group(1) : m.group(2));
                } else if (m.group(3) != null) {
                    for (Matcher q = QUOTED.matcher(m.group(3)); q.find(); ) {
                        frontend.add(q.group(1) != null ? q.group(1) : q.group(2));
                    }
                }
            }
        }
        return new SourceScan(annotated, callSites, frontend, javaFiles.size(), clientFiles.size());
    }

    /**
     * 找到 {@code pattern} 的匹配后，取其后**配平的括号块**，抽块内字符串字面量里的权限码。
     * 必须配平而不能只匹配到行尾：{@code @RequiresPermissions(value = {...}, logical = OR)}
     * 与跨行的 {@code requireAnyPermission(...)} 都会让简单正则漏扫。
     */
    private static void collectBalanced(String src, Pattern pattern, Set<String> out) {
        Matcher m = pattern.matcher(src);
        while (m.find()) {
            int i = m.end();
            int depth = 1;
            while (i < src.length() && depth > 0) {
                char c = src.charAt(i++);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                }
            }
            if (depth != 0) {
                continue; // 未闭合（扫描到文件尾）——不让半截块污染结果
            }
            String block = src.substring(m.end(), i - 1);
            Matcher q = JAVA_STRING.matcher(block);
            while (q.find()) {
                Matcher token = PERM_TOKEN.matcher(q.group(1));
                while (token.find()) {
                    out.add(token.group());
                }
            }
        }
    }

    private static String normalize(Path p) {
        return p.toString().replace('\\', '/');
    }

    /** 从测试工作目录（模块 basedir 或仓根）向上找同时含 services/ 与 clients/ 的目录。 */
    private static Path repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("services")) && Files.isDirectory(p.resolve("clients"))) {
                return p;
            }
        }
        throw new AssertionError("从 " + dir + " 向上找不到仓库根（需同时含 services/ 与 clients/）；"
                + "本 IT 依赖源码树，不能在仅有 jar 的环境里静默跳过");
    }

    private static List<Path> walk(Path root, Predicate<Path> fileFilter) throws IOException {
        List<Path> out = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root)) {
                    String name = dir.getFileName().toString();
                    if (name.equals("target") || name.equals("build") || name.equals("node_modules")
                            || name.equals("dist") || name.equals(".git")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (fileFilter.test(file)) {
                    out.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        out.sort(Comparator.comparing(Path::toString));
        return out;
    }
}
