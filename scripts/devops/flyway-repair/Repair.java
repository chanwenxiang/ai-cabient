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
 *     flyway.locations 默认 filesystem:services/trade-service/src/main/resources/db/migration
 *     seed.env    默认 local   ← 必须与运行时 SEED_ENV 一致
 *
 * 执行后请接着跑 ValidateCheck.java 确认真的对齐了。
 */
public class Repair {

    private static String prop(String key, String fallback) {
        String v = System.getProperty(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    public static void main(String[] args) {
        String url = prop("db.url", "jdbc:postgresql://127.0.0.1:15433/aicabinet");
        String user = prop("db.user", "aicabinet");
        String password = prop("db.password", "aicabinet");
        String locations = prop("flyway.locations",
                "filesystem:services/trade-service/src/main/resources/db/migration");
        // 与 application.yml 的 flyway.placeholders.seed_env 保持同一口径（默认 local）。
        String seedEnv = prop("seed.env", "local");

        System.out.println("[flyway-repair] url=" + url);
        System.out.println("[flyway-repair] locations=" + locations);
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
