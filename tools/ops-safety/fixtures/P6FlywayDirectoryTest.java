import java.nio.file.*;
import java.util.*;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.lang.reflect.InvocationTargetException;

/** Exercises the actual marker reader in a child JVM with the runtime working directory. */
public class P6FlywayDirectoryTest {
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("probe")) {
            String run = Path.of("").toAbsolutePath().getFileName().toString().substring(7);
            var method = P6CompositeFlywayTool.class.getDeclaredMethod("readOwnedMarker", Map.class);
            method.setAccessible(true);
            try {
                method.invoke(null, Map.of("P6_REHEARSAL_RUN_ID", run,
                    "P6_REHEARSAL_RUN_DIRECTORY", Path.of("").toAbsolutePath().normalize().toString()));
                System.exit(0);
            } catch (InvocationTargetException ex) { System.exit(9); }
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        String id = UUID.randomUUID().toString().replace("-", "");
        Path base = root.resolve(".tmp/flyway-path-test-" + id);
        Files.createDirectories(base);
        int passed = 0;
        try {
            for (String relative : List.of(".tmp/p6iso", "other", ".superpowers/sdd/2026-09-06-p6-2-local-isolation-rehearsal")) {
                Path cwd = base.resolve(relative).resolve("native-" + id);
                Files.createDirectories(cwd);
                Properties marker = new Properties();
                marker.setProperty("CreatedAt", Instant.now().minusSeconds(60).toString());
                try (var output = Files.newOutputStream(cwd.resolve("owner.properties"))) { marker.store(output, null); }
                Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                    "-cp", System.getProperty("java.class.path"), "P6FlywayDirectoryTest", "probe")
                    .directory(cwd.toFile()).redirectErrorStream(true).start();
                try {
                    if (!child.waitFor(10, TimeUnit.SECONDS)) throw new AssertionError("CHILD_TIMEOUT");
                    int expected = relative.equals(".tmp/p6iso") ? 0 : 9;
                    if (child.exitValue() != expected) throw new AssertionError("RUNTIME_DIRECTORY_CONTRACT");
                    passed++;
                } finally {
                    if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
                }
            }
            System.out.println("FLYWAY_DIRECTORY_TESTS=" + passed + "/3");
        } finally {
            try (var paths = Files.walk(base)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
