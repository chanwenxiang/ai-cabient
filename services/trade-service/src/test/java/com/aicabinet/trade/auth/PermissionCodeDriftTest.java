package com.aicabinet.trade.auth;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权限码漂移回归测试。
 *
 * 背景：商家/运营权限码散落在 Java 注解、服务层调用与 Flyway 迁移三处，
 * 曾出现"新接口挂了 @RequiresPermissions 但权限码没种入 ops_permission / 没授权给角色"
 * 导致 403 或静默失效。本测试做交叉比对：
 *
 * 1) 扫描主代码中 @RequiresPermissions 与服务层 require*、has* 调用用到的 merchant:/ops: 权限码；
 * 2) 校验每个权限码都出现在 Flyway 迁移中（种入 ops_permission 或同文件授权引用）；
 * 3) 商家权限码还须在"首次种入之后"的迁移中被任一角色授权（精确码或通配，如 merchant:% / merchant:orders:*），
 *    防止新增权限码但忘记授权。
 */
class PermissionCodeDriftTest {

    private static final Pattern ANNOTATION = Pattern.compile(
            "@RequiresPermissions\\s*\\(([\\s\\S]*?)\\)");
    private static final Pattern PERM_CALL = Pattern.compile(
            "(?:requirePermission|requireAnyPermission|hasPermission|hasAnyPermission)"
                    + "\\s*\\(([\\s\\S]*?)\\)");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");
    private static final Pattern PERM_LITERAL = Pattern.compile("'(merchant|ops):([^']*)'");
    /** 前端权限码上下文：v-hasPermi 指令 / menu.perm / perms / permission 字段。 */
    private static final Pattern FRONTEND_PERM_CONTEXT = Pattern.compile(
            "(?:v-hasPermi|perm|perms|permission)\\s*[:=]\\s*(\\[[^\\]]*\\]|'[^']*')");
    private static final Pattern SINGLE_QUOTED = Pattern.compile("'([^']+)'");
    private static final Pattern HAS_PERMI_CALL = Pattern.compile(
            "hasPermi\\(\\s*'([^']+)'");

    @Test
    void permissionCodesUsedInCode_areSeeded_andMerchantCodesAreGranted() throws IOException {
        List<String> used = collectUsedCodes();
        assertFalse(used.isEmpty(), "应能从主代码中扫描到 merchant:/ops: 权限码");

        List<Migration> migrations = loadMigrations();
        Set<String> allSeeds = new TreeSet<>();
        for (Migration m : migrations) {
            allSeeds.addAll(m.seeds());
        }

        List<String> missingSeeds = used.stream()
                .filter(code -> !allSeeds.contains(code))
                .distinct()
                .sorted()
                .toList();
        assertTrue(missingSeeds.isEmpty(),
                "以下权限码在代码中使用，但未在 Flyway 迁移中种入 ops_permission（漏写种子或拼错）：" + missingSeeds);

        List<String> unGranted = used.stream()
                .filter(code -> code.startsWith("merchant:"))
                .distinct()
                .sorted()
                .filter(code -> !grantedAfterFirstSeed(migrations, code))
                .toList();
        assertTrue(unGranted.isEmpty(),
                "以下商家权限码未被任何角色授权（精确码或通配均未匹配）：" + unGranted);
    }

    @Test
    void frontendPermissionCodes_areSeeded() throws IOException {
        List<Migration> migrations = loadMigrations();
        Set<String> allSeeds = new TreeSet<>();
        for (Migration m : migrations) {
            allSeeds.addAll(m.seeds());
        }

        Set<String> frontendCodes = new TreeSet<>();
        frontendCodes.addAll(collectFrontendCodes(
                Path.of("..", "..", "clients", "merchant-mp", "src", "config", "merchant-nav.ts")));
        frontendCodes.addAll(collectFrontendCodes(
                Path.of("..", "..", "clients", "admin-vue", "src")));

        assertFalse(frontendCodes.isEmpty(), "应能从商家小程序/管理端前端扫到权限码");
        List<String> missing = frontendCodes.stream()
                .filter(code -> !allSeeds.contains(code))
                .sorted()
                .toList();
        assertTrue(missing.isEmpty(),
                "以下前端使用的权限码未在 Flyway 迁移中种入 ops_permission（拼错或漏种）：" + missing);
    }

    /** 收集单文件（商家导航）或整目录（admin-vue src）中的权限码字面量。 */
    private static Set<String> collectFrontendCodes(Path path) throws IOException {
        Set<String> codes = new TreeSet<>();
        if (Files.isRegularFile(path)) {
            collectFrontendCodesFromFile(path, codes);
            return codes;
        }
        if (!Files.isDirectory(path)) {
            return codes;
        }
        try (var stream = Files.walk(path)) {
            for (Path file : stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".ts") || p.toString().endsWith(".vue"))
                    .toList()) {
                collectFrontendCodesFromFile(file, codes);
            }
        }
        return codes;
    }

    private static void collectFrontendCodesFromFile(Path file, Set<String> out) throws IOException {
        String src = Files.readString(file, StandardCharsets.UTF_8);
        Matcher context = FRONTEND_PERM_CONTEXT.matcher(src);
        while (context.find()) {
            Matcher q = SINGLE_QUOTED.matcher(context.group(1));
            while (q.find()) {
                addPermCode(q.group(1), out);
            }
        }
        Matcher call = HAS_PERMI_CALL.matcher(src);
        while (call.find()) {
            addPermCode(call.group(1), out);
        }
    }

    private static void addPermCode(String code, Set<String> out) {
        if (code == null || code.isBlank()) {
            return;
        }
        if (code.startsWith("merchant:") || code.startsWith("ops:")) {
            out.add(code);
        }
    }

    private static boolean grantedAfterFirstSeed(List<Migration> migrations, String code) {
        int seedIndex = -1;
        for (int i = 0; i < migrations.size(); i++) {
            if (migrations.get(i).seeds().contains(code)) {
                seedIndex = i;
                break;
            }
        }
        if (seedIndex < 0) {
            return false;
        }
        for (int i = seedIndex; i < migrations.size(); i++) {
            for (String grant : migrations.get(i).grants()) {
                if (toPattern(grant).matcher(code).matches()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** SQL 通配（% 与存储权限码中的 *）统一转为正则，如 merchant:% / merchant:orders:*。 */
    private static Pattern toPattern(String grantLiteral) {
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < grantLiteral.length(); i++) {
            char c = grantLiteral.charAt(i);
            if (c == '%' || c == '*') {
                sb.append(".*");
            } else if ("\\.^$()[]{}|+-?".indexOf(c) >= 0) {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        return Pattern.compile(sb.append('$').toString());
    }

    private static List<String> collectUsedCodes() throws IOException {
        Set<String> codes = new TreeSet<>();
        try (var stream = Files.walk(Path.of("src", "main", "java"))) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();
            for (Path file : files) {
                String src = Files.readString(file, StandardCharsets.UTF_8);
                collectFrom(ANNOTATION, src, codes);
                collectFrom(PERM_CALL, src, codes);
            }
        }
        return codes.stream()
                .filter(code -> code.startsWith("merchant:") || code.startsWith("ops:"))
                .toList();
    }

    private static void collectFrom(Pattern pattern, String src, Set<String> out) {
        Matcher m = pattern.matcher(src);
        while (m.find()) {
            Matcher q = QUOTED.matcher(m.group(1));
            while (q.find()) {
                String code = q.group(1);
                if (code.startsWith("merchant:") || code.startsWith("ops:")) {
                    out.add(code);
                }
            }
        }
    }

    private static List<Migration> loadMigrations() throws IOException {
        List<Migration> migrations = new ArrayList<>();
        Path dir = Path.of("src", "main", "resources", "db", "migration");
        try (var stream = Files.list(dir)) {
            List<Path> files = stream
                    .filter(p -> p.getFileName().toString().matches("^V\\d+.*\\.sql$"))
                    .sorted(Comparator.comparingInt(p -> parseVersion(p.getFileName().toString())))
                    .toList();
            for (Path file : files) {
                String sql = Files.readString(file, StandardCharsets.UTF_8);
                Set<String> seeds = new LinkedHashSet<>();
                Set<String> grants = new LinkedHashSet<>();
                if (sql.contains("INSERT INTO ops_permission")) {
                    collectPermLiterals(sql, seeds);
                }
                if (sql.contains("INSERT INTO ops_role_permission")) {
                    collectPermLiterals(sql, grants);
                }
                migrations.add(new Migration(parseVersion(file.getFileName().toString()), seeds, grants));
            }
        }
        return migrations;
    }

    private static void collectPermLiterals(String sql, Set<String> out) {
        Matcher m = PERM_LITERAL.matcher(sql);
        while (m.find()) {
            out.add(m.group(1) + ":" + m.group(2));
        }
    }

    private static int parseVersion(String name) {
        Matcher m = Pattern.compile("^V(\\d+)").matcher(name);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private record Migration(int version, Set<String> seeds, Set<String> grants) {
    }
}
