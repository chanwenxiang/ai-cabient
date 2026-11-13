import org.flywaydb.core.Flyway;

/**
 * Flyway validate 助手：连接目标库，跑 validate，漂移抛异常。
 * 用法：java -cp <flyway+jdbc+slf4j+gson> FlywayValidateHelper <jdbc-url> <user> <pass> <locations>
 * 输出：VALIDATE_OK 或 VALIDATE_DRIFT: <原因>
 */
public class FlywayValidateHelper {
    public static void main(String[] args) {
        Flyway flyway = Flyway.configure()
                .dataSource(args[0], args[1], args[2])
                .locations(args[3])
                .load();
        try {
            flyway.validate();
            System.out.println("VALIDATE_OK: 无 checksum 漂移");
        } catch (Exception e) {
            System.out.println("VALIDATE_DRIFT: " + e.getMessage());
        }
    }
}
