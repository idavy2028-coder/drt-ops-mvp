package com.idavy.drtops.jtgateway.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrivateVehicleIdentifierCaptureTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void disabledCaptureNeverWritesSensitiveData() {
        Path output = temporaryDirectory.resolve("disabled.bin");
        PrivateVehicleIdentifierCapture capture = PrivateVehicleIdentifierCapture.disabled();

        PrivateVehicleIdentifierCapture.Snapshot snapshot =
                capture.capture("terminal-01", "PRIVATE-PLATE");

        assertFalse(Files.exists(output));
        assertFalse(snapshot.enabled());
        assertFalse(snapshot.captured());
        assertFalse(snapshot.toString().contains("PRIVATE-PLATE"));
    }

    @Test
    void enabledCaptureWritesFirstValueOnceWithOwnerOnlyPermissionsAndSafeSnapshot() throws Exception {
        Path root = temporaryDirectory.resolve("private-diagnostics");
        Path output = root.resolve("terminal-01-vehicle-identifier.bin");
        PrivateVehicleIdentifierCapture capture =
                PrivateVehicleIdentifierCapture.enabled(root, output);

        PrivateVehicleIdentifierCapture.Snapshot first =
                capture.capture("terminal-01", "PRIVATE-PLATE");
        PrivateVehicleIdentifierCapture.Snapshot second =
                capture.capture("terminal-01", "MUST-NOT-OVERWRITE");

        assertArrayEquals("PRIVATE-PLATE".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(output));
        assertTrue(first.enabled());
        assertTrue(first.captured());
        assertEquals("terminal-01", first.alias());
        assertEquals(13, first.characterCount());
        assertEquals(13, first.gbkByteCount());
        assertEquals(first, second);
        assertFalse(first.toString().contains("PRIVATE-PLATE"));
        assertFalse(first.toString().contains("MUST-NOT-OVERWRITE"));
        if (Files.getFileStore(output).supportsFileAttributeView("posix")) {
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(output));
        }
    }

    @Test
    void enabledCaptureRejectsUnsafeOrPreexistingOutputPath() throws Exception {
        Path root = temporaryDirectory.resolve("private-diagnostics");
        Files.createDirectories(root);
        Path existing = root.resolve("existing.bin");
        Files.writeString(existing, "do-not-overwrite", StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class,
                () -> PrivateVehicleIdentifierCapture.enabled(
                        root, temporaryDirectory.resolve("outside.bin")));
        assertThrows(IllegalStateException.class,
                () -> PrivateVehicleIdentifierCapture.enabled(root, existing));
    }
}
