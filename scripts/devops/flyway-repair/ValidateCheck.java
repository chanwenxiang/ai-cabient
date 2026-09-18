import org.flywaydb.core.Flyway;

/**
 * 验证 Repair.java 是否真的解决了「重启因 checksum 不匹配而拒启」。
 *
 * 与 Repair.java 必须用**同一组**参数（尤其 seed.env），否则验的不是同一件事。
 *
 * 为什么带 ignorePendingMigrations(true)：应用启动走的是 migrate()，会顺带把 pending 迁移应用掉；
 * 而裸 validate() 会把「有 pending 未应用」也当失败。要单独验证「checksum 是否对齐」，
 * 必须把 pending 这一维度摘掉 —— 否则会把「还没 apply 的新迁移」误报成校验失败。
 *
 * 用法（在仓库根执行；jar 路径见同目录 README.md）：
 *     java -Dfile.encoding=UTF-8 -cp "<classpath>" ValidateCheck.java
 * 期望输出：VALIDATE=PASS
 */
public class ValidateCheck {

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
        String seedEnv = prop("seed.env", "local");

        Flyway flyway = Flyway.configure()
                .dataSource(url, user, password)
                .locations(locations)
                .placeholders(java.util.Map.of("seed_env", seedEnv))
                // "*:pending" = 忽略「尚未应用」这一类，只看已应用迁移的 checksum 是否对齐。
                .ignoreMigrationPatterns("*:pending")
                .load();

        try {
            flyway.validate();
            System.out.println("VALIDATE=PASS");
        } catch (Exception e) {
            // 故意不引用返回类型：校验失败时 Flyway 抛 FlywayValidateException，
            // 打到 stdout 便于在日志里 grep（退出码本身不区分成因）。
            System.out.println("VALIDATE=FAIL: " + e.getMessage());
        }
    }
}
