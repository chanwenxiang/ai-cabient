import org.flywaydb.core.Flyway;

/**
 * 一次性运维工具：对某个库执行 flyway repair（对齐 schema history 的 checksum）。
 *
 * 何时需要：**已经 apply 过的迁移文件内容被再次编辑**（例如给历史种子迁移补 ${seed_env} 环境
 * 守卫、修 SQL 笔误）。Flyway 默认 validate-on-migrate=true，重启时会因 checksum 不匹配直接拒绝
 * 启动：
 *     Migration checksum mismatch for migration version 244
 *     -> Applied to database : 1234567890
 *     -> Resolved locally    : 987654321
 * repair() 只把 schema history 表里已记录行的 checksum/description 重写为「当前文件算出的值」，
 * **不动任何业务数据**，也不动 pending 迁移的 apply 状态。
 *
 * 🔴 关键坑（吃过一次）：placeholder 必须与**运行时**一致。
 *     application.yml 里 migration 的 placeholder 是 ${SEED_ENV:local}，所以本机/开发库默认 local。
 *     若 repair 时漏传 seed_env（或传了别的值），Flyway 算出的 checksum 与下次应用启动时算出的
 *     仍然不一致 —— repair 会「成功」，但重启照旧失败，表现为「修了没用」。
 *
 * 用法（在仓库根执行；jar 路径见同目录 README.md）：
 *     java -Dfile.encoding=UTF-8 -cp "<classpath>" Repair.java
 * 可覆盖项（-D 系统属性）：
 *     db.url      默认 jdbc:postgresql://127.0.0.1:15433/aicabinet
 *     db.user     默认 aicabinet
 *     db.password 默认 aicabinet
 *     flyway.locations 默认 db/migration + db/seed 两目录（必须与运行时同集，见 locations()）
 *     seed.env    默认 local   ← 必须与运行时 SEED_ENV 一致
 *
 * 执行后请接着跑 ValidateCheck.java 确认真的对齐了。
 */
public class Repair {

    private static String prop(String key, String fallback) {
        String v = System.getProperty(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    /**
     * 🔴 必须与运行时 {@code application.yml} 的 {@code spring.flyway.locations} **同集**：
     * 本仓迁移分两目录——{@code db/migration}（结构 V*.sql）与 {@code db/seed}（可重复 R__seed_*.sql）。
     * 只指 {@code db/migration} 时，Flyway 判定那 3 条已应用的种子「本地不存在」，
     * repair 会给它们写 DELETE 标记 ⇒ 下次启动重跑种子、再下次 repair 又标一次
     * （2026-11-13 实测已是第 4 次，历史里留着前 3 轮同样标记）。
     * 覆盖 -Dflyway.locations 时自行按逗号切并 trim，不依赖 Flyway 的拆分行为。
     */
    private static String[] locations() {
        String raw = prop("flyway.locations",
                "filesystem:services/trade-service/src/main/resources/db/migration,"
                        + "filesystem:services/trade-service/src/main/resources/db/seed");
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }

    public static void main(String[] args) {
        String url = prop("db.url", "jdbc:postgresql://127.0.0.1:15433/aicabinet");
        String user = prop("db.user", "aicabinet");
        String password = prop("db.password", "aicabinet");
        String[] locations = locations();
        // 与 application.yml 的 flyway.placeholders.seed_env 保持同一口径（默认 local）。
        String seedEnv = prop("seed.env", "local");

        System.out.println("[flyway-repair] url=" + url);
        System.out.println("[flyway-repair] locations=" + java.util.Arrays.toString(locations));
        System.out.println("[flyway-repair] seed_env=" + seedEnv
                + "   (必须与运行时 SEED_ENV 一致，否则 repair 白做)");

        Flyway flyway = Flyway.configure()
                .dataSource(url, user, password)
                .locations(locations)
                .placeholders(java.util.Map.of("seed_env", seedEnv))
                .load();

        flyway.repair();
        System.out.println("REPAIR_OK");
    }
}
