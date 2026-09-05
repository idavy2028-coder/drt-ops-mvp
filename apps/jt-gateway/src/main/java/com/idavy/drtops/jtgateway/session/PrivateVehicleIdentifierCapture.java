package com.idavy.drtops.jtgateway.session;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/** One-shot private capture that never exposes the captured value through its snapshot. */
public final class PrivateVehicleIdentifierCapture {
    private static final Charset GBK = Charset.forName("GBK");
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    private final boolean enabled;
    private final Path outputPath;
    private Snapshot snapshot;

    private PrivateVehicleIdentifierCapture(boolean enabled, Path outputPath) {
        this.enabled = enabled;
        this.outputPath = outputPath;
        this.snapshot = new Snapshot(enabled, false, "NONE", 0, 0);
    }

    public static PrivateVehicleIdentifierCapture disabled() {
        return new PrivateVehicleIdentifierCapture(false, null);
    }

    public static PrivateVehicleIdentifierCapture enabled(Path configuredRoot, Path configuredOutput) {
        if (configuredRoot == null || configuredOutput == null) {
            throw new IllegalArgumentException("private capture root and path are required");
        }
        Path root = configuredRoot.toAbsolutePath().normalize();
        Path output = configuredOutput.toAbsolutePath().normalize();
        if (!output.startsWith(root) || output.equals(root)) {
            throw new IllegalArgumentException("private capture path must stay below its configured root");
        }
        if (Files.exists(output)) {
            throw new IllegalStateException("private capture output already exists");
        }
        return new PrivateVehicleIdentifierCapture(true, output);
    }

    public synchronized Snapshot capture(String alias, String vehicleIdentifier) {
        if (!enabled || snapshot.captured()) {
            return snapshot;
        }
        if (alias == null || !alias.matches("terminal-0[1-4]")) {
            throw new IllegalArgumentException("private capture requires a safe terminal alias");
        }
        if (vehicleIdentifier == null || vehicleIdentifier.isBlank()) {
            throw new IllegalArgumentException("private capture vehicle identifier is required");
        }
        byte[] utf8 = vehicleIdentifier.getBytes(StandardCharsets.UTF_8);
        byte[] gbk = vehicleIdentifier.getBytes(GBK);
        try {
            writeOnce(utf8);
            snapshot = new Snapshot(
                    true, true, alias, vehicleIdentifier.length(), gbk.length);
            return snapshot;
        } finally {
            Arrays.fill(utf8, (byte) 0);
            Arrays.fill(gbk, (byte) 0);
        }
    }

    public synchronized Snapshot snapshot() {
        return snapshot;
    }

    private void writeOnce(byte[] value) {
        try {
            Path parent = outputPath.getParent();
            Files.createDirectories(parent);
            boolean posix = Files.getFileStore(parent).supportsFileAttributeView("posix");
            if (posix) {
                Files.setPosixFilePermissions(parent, DIRECTORY_PERMISSIONS);
            }
            Set<OpenOption> options = Set.copyOf(EnumSet.of(
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));
            FileAttribute<?>[] attributes = posix
                    ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS)}
                    : new FileAttribute<?>[0];
            try (SeekableByteChannel channel = Files.newByteChannel(outputPath, options, attributes)) {
                ByteBuffer bytes = ByteBuffer.wrap(value);
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
            }
            if (posix) {
                Files.setPosixFilePermissions(outputPath, FILE_PERMISSIONS);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("private vehicle identifier capture failed", exception);
        }
    }

    public record Snapshot(
            boolean enabled,
            boolean captured,
            String alias,
            int characterCount,
            int gbkByteCount) {
    }
}
