package com.idavy.drtops.jtgateway;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.WINDOWS)
class PostgresBackupRunbookContractTest {
    private static final Pattern CONTRACT = Pattern.compile(
            "(?s)# postgres-backup-contract-begin\\R(.*?)"
                    + "# postgres-backup-contract-end");
    private static final byte[] COMPLETE_UTF8_DUMP = ("--\r\n"
                    + "-- PostgreSQL database cluster dump\r\n"
                    + "--\r\n"
                    + "CREATE ROLE gateway_user;\r\n"
                    + "COMMENT ON ROLE gateway_user IS '公交数据-中文哨兵';\r\n"
                    + "--\r\n"
                    + "-- PostgreSQL database cluster dump complete\r\n"
                    + "--\r\n")
            .getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path temporaryDirectory;

    @Test
    void deletesHostAndContainerPartialFilesWhenContainerDumpFails() throws Exception {
        Result result = executeScenario(COMPLETE_UTF8_DUMP, 7, 0, 0);

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("container pg_dumpall failed with exit code 7"),
                result.output());
        assertFalse(Files.exists(result.dump()), "host partial dump must be removed");
        assertFalse(Files.exists(result.containerDump()), "container partial dump must be removed");
        assertFalse(Files.exists(result.continued()), "workflow must stop after exec failure");
        assertCommandPhases(result, false);
    }

    @Test
    void deletesHostAndContainerPartialFilesWhenComposeCopyFails() throws Exception {
        Result result = executeScenario(COMPLETE_UTF8_DUMP, 0, 9, 0);

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("docker compose cp failed with exit code 9"),
                result.output());
        assertFalse(Files.exists(result.dump()), "host partial dump must be removed");
        assertFalse(Files.exists(result.containerDump()), "container dump must be removed");
        assertFalse(Files.exists(result.continued()), "workflow must stop after cp failure");
        assertCommandPhases(result, true);
    }

    @Test
    void deletesCopiedDumpWhenCompletionMarkerIsMissing() throws Exception {
        byte[] incompleteDump = ("--\r\n"
                        + "-- PostgreSQL database cluster dump\r\n"
                        + "--\r\n"
                        + "CREATE ROLE gateway_user;\r\n")
                .getBytes(StandardCharsets.UTF_8);

        Result result = executeScenario(incompleteDump, 0, 0, 0);

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("pg_dumpall output is incomplete"), result.output());
        assertFalse(Files.exists(result.dump()), "incomplete host dump must be removed");
        assertFalse(Files.exists(result.containerDump()), "container dump must be removed");
        assertFalse(Files.exists(result.continued()), "workflow must stop before rotation");
        assertCommandPhases(result, true);
    }

    @Test
    void preservesNonAsciiUtf8BytesAndCleansContainerFileBeforeContinuing() throws Exception {
        Result result = executeScenario(COMPLETE_UTF8_DUMP, 0, 0, 0);

        assertEquals(0, result.exitCode(), result.output());
        assertArrayEquals(COMPLETE_UTF8_DUMP, Files.readAllBytes(result.dump()),
                "docker compose cp must preserve the SQL dump byte-for-byte");
        assertFalse(Files.exists(result.containerDump()), "container dump must be removed");
        assertTrue(Files.exists(result.continued()));
        assertCommandPhases(result, true);
    }

    @Test
    void deletesHostDumpAndStopsWhenContainerCleanupFails() throws Exception {
        Result result = executeScenario(COMPLETE_UTF8_DUMP, 0, 0, 11);

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("container dump cleanup failed with exit code 11"),
                result.output());
        assertFalse(Files.exists(result.dump()), "host dump must be removed after cleanup failure");
        assertFalse(Files.exists(result.continued()), "workflow must stop after cleanup failure");
        assertCommandPhases(result, true);
    }

    @Test
    void preservesPrimaryFailureWhenContainerCleanupAlsoFails() throws Exception {
        Result result = executeScenario(COMPLETE_UTF8_DUMP, 7, 0, 11);

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("container pg_dumpall failed with exit code 7"),
                result.output());
        assertTrue(result.output().contains("container dump cleanup failed with exit code 11"),
                result.output());
        assertFalse(Files.exists(result.dump()), "host partial dump must be removed");
        assertFalse(Files.exists(result.continued()), "workflow must stop after native failures");
        assertCommandPhases(result, false);
    }

    private Result executeScenario(
            byte[] sourceBytes, int execExit, int copyExit, int cleanupExit) throws Exception {
        Path sourceDump = temporaryDirectory.resolve("source.sql");
        Path containerDump = temporaryDirectory.resolve("container.sql");
        Path dump = temporaryDirectory.resolve("cluster.sql");
        Path continued = temporaryDirectory.resolve("continued.marker");
        Path fakeDockerScript = temporaryDirectory.resolve("fake-docker.ps1");
        Path fakeDocker = temporaryDirectory.resolve("docker.cmd");
        Path fakeDockerLog = temporaryDirectory.resolve("docker.log");
        Files.write(sourceDump, sourceBytes);
        Files.writeString(fakeDockerScript, fakeDockerScript(), StandardCharsets.UTF_8);
        Files.writeString(fakeDocker, "@echo off\r\n"
                        + "\"%SystemRoot%\\System32\\WindowsPowerShell\\v1.0\\powershell.exe\" "
                        + "-NoProfile -NonInteractive -ExecutionPolicy Bypass "
                        + "-File \"%FAKE_DOCKER_SCRIPT%\" %*\r\n"
                        + "exit /b %ERRORLEVEL%\r\n",
                StandardCharsets.US_ASCII);
        Files.writeString(dump, "pre-existing host partial", StandardCharsets.UTF_8);

        String script = "$ErrorActionPreference = 'Stop'" + System.lineSeparator()
                + contractDefinition() + System.lineSeparator()
                + "$env:Path = '" + quote(temporaryDirectory) + ";' + $env:Path"
                + System.lineSeparator()
                + "$env:FAKE_DOCKER_SCRIPT = '" + quote(fakeDockerScript) + "'"
                + System.lineSeparator()
                + "$env:FAKE_DOCKER_LOG = '" + quote(fakeDockerLog) + "'"
                + System.lineSeparator()
                + "$env:FAKE_SOURCE_DUMP = '" + quote(sourceDump) + "'"
                + System.lineSeparator()
                + "$env:FAKE_CONTAINER_DUMP = '" + quote(containerDump) + "'"
                + System.lineSeparator()
                + "$env:FAKE_HOST_DUMP = '" + quote(dump) + "'"
                + System.lineSeparator()
                + "$env:FAKE_EXEC_EXIT = '" + execExit + "'"
                + System.lineSeparator()
                + "$env:FAKE_COPY_EXIT = '" + copyExit + "'"
                + System.lineSeparator()
                + "$env:FAKE_CLEANUP_EXIT = '" + cleanupExit + "'"
                + System.lineSeparator()
                + "$env:FAKE_CONTAINER_PATH = '/tmp/postgres-contract.sql'"
                + System.lineSeparator()
                + "$compose = @('--env-file', 'fake.env', '-f', 'fake-compose.yml')"
                + System.lineSeparator()
                + "Invoke-CheckedPostgresDump -Destination '" + quote(dump)
                + "' -ContainerPath '/tmp/postgres-contract.sql' -ComposeArguments $compose"
                + System.lineSeparator()
                + "Set-Content -LiteralPath '" + quote(continued) + "' -Value continued";
        Path scenario = temporaryDirectory.resolve(
                "scenario-" + execExit + "-" + copyExit + "-" + cleanupExit
                        + "-" + sourceBytes.length + ".ps1");
        Files.writeString(scenario, script, StandardCharsets.UTF_8);

        String powershell = Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell",
                "v1.0", "powershell.exe").toString();
        Process process = new ProcessBuilder(
                powershell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-File", scenario.toString())
                .redirectErrorStream(true)
                .start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "PowerShell contract timed out");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(
                process.exitValue(), output, dump, containerDump, continued, fakeDockerLog);
    }

    private static void assertCommandPhases(Result result, boolean copyExpected) throws IOException {
        String commands = Files.readString(result.dockerLog(), StandardCharsets.UTF_8);
        assertTrue(commands.contains("pg_dumpall"), commands);
        if (copyExpected) {
            assertTrue(commands.contains(" cp "), commands);
        } else {
            assertFalse(commands.contains(" cp "), commands);
        }
        assertTrue(commands.contains("rm -f"), commands);
    }

    private static String fakeDockerScript() {
        return """
                $joined = $args -join ' '
                [System.IO.File]::AppendAllText(
                    $env:FAKE_DOCKER_LOG, $joined + [Environment]::NewLine)
                $validPrefix = $args.Count -ge 6 -and
                    $args[0] -eq 'compose' -and
                    $args[1] -eq '--env-file' -and $args[2] -eq 'fake.env' -and
                    $args[3] -eq '-f' -and $args[4] -eq 'fake-compose.yml'
                if (!$validPrefix) {
                    [Console]::Error.WriteLine("invalid compose prefix: $joined")
                    exit 91
                }
                if ($args[5] -eq 'cp') {
                    $validCopy = $args.Count -eq 8 -and
                        $args[6] -eq "postgres:$env:FAKE_CONTAINER_PATH" -and
                        $args[7] -eq $env:FAKE_HOST_DUMP
                    if (!$validCopy) {
                        [Console]::Error.WriteLine("invalid compose cp: $joined")
                        exit 92
                    }
                    [byte[]] $bytes = [System.IO.File]::ReadAllBytes($env:FAKE_CONTAINER_DUMP)
                    if ([int] $env:FAKE_COPY_EXIT -ne 0) {
                        [byte[]] $partial = $bytes[0..([Math]::Min(7, $bytes.Length - 1))]
                        [System.IO.File]::WriteAllBytes($env:FAKE_HOST_DUMP, $partial)
                        exit [int] $env:FAKE_COPY_EXIT
                    }
                    [System.IO.File]::WriteAllBytes($env:FAKE_HOST_DUMP, $bytes)
                    exit 0
                }
                $validExec = $args.Count -eq 13 -and
                    $args[5] -eq 'exec' -and $args[6] -eq '-T' -and
                    $args[7] -eq 'postgres' -and $args[8] -eq 'sh' -and
                    $args[9] -eq '-ceu' -and $args[11] -eq 'sh' -and
                    $args[12] -eq $env:FAKE_CONTAINER_PATH
                if (!$validExec) {
                    [Console]::Error.WriteLine("invalid compose exec: $joined")
                    exit 93
                }
                if ($args[10] -eq 'umask 077; pg_dumpall --username $POSTGRES_USER > $1') {
                    [byte[]] $bytes = [System.IO.File]::ReadAllBytes($env:FAKE_SOURCE_DUMP)
                    if ([int] $env:FAKE_EXEC_EXIT -ne 0) {
                        [byte[]] $partial = $bytes[0..([Math]::Min(7, $bytes.Length - 1))]
                        [System.IO.File]::WriteAllBytes($env:FAKE_CONTAINER_DUMP, $partial)
                        exit [int] $env:FAKE_EXEC_EXIT
                    }
                    [System.IO.File]::WriteAllBytes($env:FAKE_CONTAINER_DUMP, $bytes)
                    exit 0
                }
                if ($args[10] -eq 'rm -f -- $1') {
                    if ([int] $env:FAKE_CLEANUP_EXIT -ne 0) {
                        exit [int] $env:FAKE_CLEANUP_EXIT
                    }
                    Remove-Item -LiteralPath $env:FAKE_CONTAINER_DUMP -Force -ErrorAction SilentlyContinue
                    exit 0
                }
                [Console]::Error.WriteLine("invalid container shell command: $joined")
                exit 94
                """;
    }

    private static String contractDefinition() throws IOException {
        Path working = Path.of("").toAbsolutePath();
        Path runbook = working.resolve("../../docs/pilot/jt-gateway-operations.md").normalize();
        if (!Files.exists(runbook)) {
            runbook = working.resolve("docs/pilot/jt-gateway-operations.md");
        }
        Matcher matcher = CONTRACT.matcher(Files.readString(runbook, StandardCharsets.UTF_8));
        assertTrue(matcher.find(), "runbook must contain executable PostgreSQL backup contract");
        return matcher.group(1);
    }

    private static String quote(Path path) {
        return path.toAbsolutePath().toString().replace("'", "''");
    }

    private record Result(
            int exitCode,
            String output,
            Path dump,
            Path containerDump,
            Path continued,
            Path dockerLog) { }
}
