package com.idavy.drtops.jtsimulator;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Command line entry: {@code JtTerminalSimulatorApplication <scenario.json> <host> <port>}.
 * Prints the scenario report (masked aliases only) and exits non-zero when any step failed.
 */
public final class JtTerminalSimulatorApplication {
    private JtTerminalSimulatorApplication() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            System.err.println("usage: JtTerminalSimulatorApplication <scenario.json> <host> <port>");
            System.exit(2);
        }
        String json = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
        InetSocketAddress endpoint = new InetSocketAddress(args[1], Integer.parseInt(args[2]));
        ScenarioReport report = ScenarioRunner.run(Scenario.parse(json), endpoint);
        System.out.println(report.asText());
        System.exit(report.allPassed() ? 0 : 1);
    }
}
