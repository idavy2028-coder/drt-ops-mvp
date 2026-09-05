import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.flywaydb.core.Flyway;

/**
 * 新隔离live库专用入口；不接收命令行SQL、migration location、target或远端URL。
 * 主调用者必须先证明本轮PG PID/启动时间/监听端口归属；此处再核对本轮marker和精确URL。
 * 不接触业务代码或历史迁移。只有固定两行demo fixture写入，且事务内严格检查形状。
 */
public final class P6CompositeFlywayTool {
    private static final Set<String> ACTIONS=Set.of("MIGRATE_19","PREPARE_V20","MIGRATE_20","MIGRATE_21","VALIDATE");
    private static final String DEMOS="('33333333-3333-3333-3333-333333333331','33333333-3333-3333-3333-333333333332')";
    private static final String DEMO_FILTER=" WHERE id IN "+DEMOS+" AND dispatchable = true";
    interface Connections { Connection open() throws Exception; }
    interface Migrations { void migrate(String target) throws Exception; void validate() throws Exception; }

    private static final class SafeFailure extends IllegalStateException {
        SafeFailure(String code) { super(code); }
    }
    private static void require(boolean condition,String code) { if(!condition) throw new SafeFailure(code); }

    static void runAction(String action,Connections connections,Migrations migrations) {
        require(action!=null && ACTIONS.contains(action),"REHEARSAL_FLYWAY_ACTION_INVALID");
        try {
            switch(action) {
                case "MIGRATE_19" -> migrations.migrate("19");
                case "MIGRATE_20" -> migrations.migrate("20");
                case "MIGRATE_21" -> migrations.migrate("21");
                case "VALIDATE" -> migrations.validate();
                case "PREPARE_V20" -> prepare(connections);
                default -> throw new SafeFailure("REHEARSAL_FLYWAY_ACTION_INVALID");
            }
        } catch(SafeFailure safe) { throw safe; }
        catch(Exception unsafe) { throw new SafeFailure("REHEARSAL_FLYWAY_DATABASE_FAILED"); }
    }

    private static long count(Statement statement,String sql) throws Exception {
        try(ResultSet result=statement.executeQuery(sql)) {
            require(result.next(),"REHEARSAL_FLYWAY_FIXTURE_INVALID");
            long value=result.getLong(1);
            require(!result.next(),"REHEARSAL_FLYWAY_FIXTURE_INVALID");
            return value;
        }
    }

    private static void prepare(Connections connections) throws Exception {
        try(Connection connection=connections.open()) {
            // 不接管调用者已有事务；新连接由本函数拥有并在所有出口关闭。
            require(connection.getAutoCommit(),"REHEARSAL_FLYWAY_FIXTURE_INVALID");
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try(Statement statement=connection.createStatement()) {
                statement.setQueryTimeout(5);
                statement.execute("LOCK TABLE jt_terminals, onboard_systems, vehicles IN SHARE ROW EXCLUSIVE MODE");
                require(count(statement,"SELECT count(*) FROM jt_terminals")==0,"REHEARSAL_FLYWAY_FIXTURE_INVALID");
                require(count(statement,"SELECT count(*) FROM onboard_systems")==0,"REHEARSAL_FLYWAY_FIXTURE_INVALID");
                require(count(statement,"SELECT count(*) FROM vehicles"+DEMO_FILTER)==2,"REHEARSAL_FLYWAY_FIXTURE_INVALID");
                require(statement.executeUpdate("UPDATE vehicles SET dispatchable = false"+DEMO_FILTER)==2,"REHEARSAL_FLYWAY_FIXTURE_INVALID");
                connection.commit();
            } catch(Exception failure) {
                try { connection.rollback(); } catch(Exception rollbackFailure) {
                    throw new SafeFailure("REHEARSAL_FLYWAY_ROLLBACK_UNPROVEN");
                }
                throw failure;
            }
        }
    }

    static void validateEnvironment(Map<String,String> environment,Properties marker) {
        String run=environment.get("P6_REHEARSAL_RUN_ID");
        String nonce=environment.get("P6_REHEARSAL_OWNER_NONCE");
        String directory=environment.get("P6_REHEARSAL_RUN_DIRECTORY");
        String port=marker.getProperty("PgPort");
        require(run!=null && run.matches("[a-f0-9]{32}") && nonce!=null && nonce.matches("[a-f0-9]{64}"),"REHEARSAL_FLYWAY_OWNERSHIP_INVALID");
        require("1".equals(marker.getProperty("SchemaVersion")) && run.equals(marker.getProperty("RunId")) &&
            nonce.equals(marker.getProperty("OwnerNonce")) && directory!=null && directory.equals(marker.getProperty("RunDirectory")),"REHEARSAL_FLYWAY_OWNERSHIP_INVALID");
        require(port!=null && port.matches("[1-9][0-9]{3,4}") && Integer.parseInt(port)>=1024 && Integer.parseInt(port)<=65535,"REHEARSAL_FLYWAY_ENDPOINT_INVALID");
        require(("jdbc:postgresql://127.0.0.1:"+port+"/composite_live").equals(environment.get("P6_REHEARSAL_JDBC_URL")),"REHEARSAL_FLYWAY_ENDPOINT_INVALID");
        require("composite".equals(environment.get("P6_REHEARSAL_DB_USER")),"REHEARSAL_FLYWAY_CREDENTIAL_INVALID");
        String password=environment.get("P6_REHEARSAL_DB_PASSWORD");
        require(password!=null && password.matches("[A-Za-z0-9_-]{32,128}"),"REHEARSAL_FLYWAY_CREDENTIAL_INVALID");
        require(ACTIONS.contains(environment.getOrDefault("P6_REHEARSAL_ACTION","")),"REHEARSAL_FLYWAY_ACTION_INVALID");
    }

    private static Properties readOwnedMarker(Map<String,String> environment) throws Exception {
        String run=environment.get("P6_REHEARSAL_RUN_ID");
        require(run!=null && run.matches("[a-f0-9]{32}"),"REHEARSAL_FLYWAY_OWNERSHIP_INVALID");
        Path cwd=Path.of("").toAbsolutePath().normalize();
        require(cwd.toString().equals(environment.get("P6_REHEARSAL_RUN_DIRECTORY")) &&
            cwd.getFileName().toString().equals("native-"+run),"REHEARSAL_FLYWAY_OWNERSHIP_INVALID");
        Path parent=cwd.getParent();
        require(parent!=null && parent.getFileName().toString().equals("2026-09-06-p6-2-local-isolation-rehearsal") &&
            parent.getParent()!=null && parent.getParent().getFileName().toString().equals("sdd") &&
            parent.getParent().getParent()!=null && parent.getParent().getParent().getFileName().toString().equals(".superpowers"),"REHEARSAL_FLYWAY_OWNERSHIP_INVALID");
        for(Path cursor=cwd;cursor!=null;cursor=cursor.getParent()) {
            require(!Files.isSymbolicLink(cursor) && !Files.readAttributes(cursor,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).isOther(),"REHEARSAL_FLYWAY_OWNERSHIP_INVALID");
        }
        Path markerPath=cwd.resolve("owner.properties");
        require(Files.isRegularFile(markerPath,LinkOption.NOFOLLOW_LINKS) && Files.size(markerPath)<=4096,"REHEARSAL_FLYWAY_OWNERSHIP_INVALID");
        Properties marker=new Properties();
        try(InputStream stream=Files.newInputStream(markerPath,LinkOption.NOFOLLOW_LINKS)) { marker.load(stream); }
        require(!Instant.parse(marker.getProperty("CreatedAt","")).isAfter(Instant.now()),"REHEARSAL_FLYWAY_OWNERSHIP_INVALID");
        return marker;
    }

    public static void main(String[] args) {
        PrintStream result=System.out;
        // 第三方Flyway/JDBC日志默认不保留，避免异常/启动日志携带URL、身份、口令。
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        String code="REHEARSAL_FLYWAY_FAILED";
        try {
            require(args.length==0,"REHEARSAL_FLYWAY_ARGUMENT_INVALID");
            Map<String,String> environment=System.getenv();
            // action先拒绝，不能因非法action读取marker或连接数据库。
            require(ACTIONS.contains(environment.getOrDefault("P6_REHEARSAL_ACTION","")),"REHEARSAL_FLYWAY_ACTION_INVALID");
            Properties marker=readOwnedMarker(environment);
            validateEnvironment(environment,marker);
            String url=environment.get("P6_REHEARSAL_JDBC_URL"), user=environment.get("P6_REHEARSAL_DB_USER"), password=environment.get("P6_REHEARSAL_DB_PASSWORD");
            DriverManager.setLoginTimeout(5);
            Properties properties=new Properties();
            properties.setProperty("user",user); properties.setProperty("password",password);
            properties.setProperty("connectTimeout","5"); properties.setProperty("socketTimeout","10");
            Migrations migrations=new Migrations() {
                private Flyway configured(String target) {
                    // location和target完全由固定动作派生。默认事务失败即退出；禁止repair/baseline/clean。
                    var config=Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration")
                        .connectRetries(0).cleanDisabled(true).baselineOnMigrate(false);
                    if(target!=null) config.target(target);
                    return config.load();
                }
                public void migrate(String target) { configured(target).migrate(); }
                public void validate() { configured(null).validate(); }
            };
            runAction(environment.get("P6_REHEARSAL_ACTION"),()->DriverManager.getConnection(url,properties),migrations);
            result.println("P6_FLYWAY_STATUS=PASS");
            return;
        } catch(SafeFailure safe) { code=safe.getMessage(); }
        catch(Throwable ignored) { /* 绝不输出原始异常、cause、ReplyRecord、凭据或路径。 */ }
        result.println("P6_FLYWAY_STATUS=FAIL CODE="+code);
        System.exit(1);
    }
}
