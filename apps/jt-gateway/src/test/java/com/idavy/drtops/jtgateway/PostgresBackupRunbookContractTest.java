package com.idavy.drtops.jtgateway;

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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.WINDOWS)
class PostgresBackupRunbookContractTest {
    private static final Pattern CONTRACT = Pattern.compile(
            "(?s)# postgres-backup-contract-begin\\R(.*?)"
                    + "# postgres-backup-contract-end");

    @TempDir
    Path temporaryDirectory;

    @Test
    void deletesPartialDumpAndStopsWhenNativeCommandFails() throws Exception {
        Result result = executeScenario("""
                --
                -- PostgreSQL database cluster dump
                --
                CREATE ROLE gateway_user;
                -- PostgreSQL database cluster dump complete
                """, 7);

        assertNotEquals(0, result.exitCode(), result.output());
        assertFalse(Files.exists(result.dump()), "partial dump must be removed");
        assertFalse(Files.exists(result.continued()), "workflow must stop after native failure");
    }

    @Test
    void deletesDumpAndStopsWhenCompletionMarkerIsMissing() throws Exception {
        Result result = executeScenario("""
                --
                -- PostgreSQL database cluster dump
                --
                CREATE ROLE gateway_user;
                """, 0);

        assertNotEquals(0, result.exitCode(), result.output());
        assertFalse(Files.exists(result.dump()), "incomplete dump must be removed");
        assertFalse(Files.exists(result.continued()), "workflow must stop before rotation");
    }

    @Test
    void acceptsACompleteClusterDumpBeforeContinuing() throws Exception {
        Result result = executeScenario("""
                --
                -- PostgreSQL database cluster dump
                --
                CREATE ROLE gateway_user;
                --
                -- PostgreSQL database cluster dump complete
                --
                """, 0);

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(Files.size(result.dump()) > 0);
        assertTrue(Files.exists(result.continued()));
    }

    private Result executeScenario(String producerOutput, int nativeExit) throws Exception {
        Path producer = temporaryDirectory.resolve("fake-pg-dumpall.cmd");
        StringBuilder command = new StringBuilder("@echo off\r\n");
        producerOutput.lines().forEach(line -> command.append("echo ")
                .append(line.isEmpty() ? "." : line).append("\r\n"));
        command.append("exit /b ").append(nativeExit).append("\r\n");
        Files.writeString(producer, command, StandardCharsets.UTF_8);

        Path dump = temporaryDirectory.resolve("cluster.sql");
        Path continued = temporaryDirectory.resolve("continued.marker");
        String script = contractDefinition() + System.lineSeparator()
                + "$producer = '" + quote(producer) + "'" + System.lineSeparator()
                + "Invoke-CheckedPostgresDump -Destination '" + quote(dump)
                + "' -DumpCommand { & $producer }" + System.lineSeparator()
                + "Set-Content -LiteralPath '" + quote(continued) + "' -Value continued";
        Path scenario = temporaryDirectory.resolve("scenario-" + nativeExit + "-"
                + Math.abs(producerOutput.hashCode()) + ".ps1");
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
        return new Result(process.exitValue(), output, dump, continued);
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

    private record Result(int exitCode, String output, Path dump, Path continued) { }
}
