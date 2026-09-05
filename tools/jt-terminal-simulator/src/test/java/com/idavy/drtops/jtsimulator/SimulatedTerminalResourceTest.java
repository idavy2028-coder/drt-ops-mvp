package com.idavy.drtops.jtsimulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.idavy.drtops.jt.protocol.codec.ProtocolVersion;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SimulatedTerminalResourceTest {

    @Test
    void releasesRegistrationBodyWhenAnyFixedIdentityFieldIsTooLong() {
        assertRegistrationOverflowReleases("SIMMFX", "SIM-MODEL", "SIM0001");
        assertRegistrationOverflowReleases("SIMMF", "MODEL-123456789012345", "SIM0001");
        assertRegistrationOverflowReleases("SIMMF", "SIM-MODEL", "SIM00012");
    }

    @Test
    void releasesVersionedAuthenticationBodyWhenInitialWriteFails() {
        AtomicReference<ByteBuf> allocated = new AtomicReference<>();
        SimulatedTerminal terminal = terminal(
                ProtocolVersion.JT808_2019,
                capacity -> capture(allocated, fullBuffer()));

        assertThrows(IndexOutOfBoundsException.class, terminal::sendAuthentication);

        assertEquals(0, allocated.get().refCnt());
    }

    @Test
    void releasesPositionBodyWhenInitialWriteFails() {
        AtomicReference<ByteBuf> allocated = new AtomicReference<>();
        SimulatedTerminal terminal = terminal(
                ProtocolVersion.JT808_2013,
                capacity -> capture(allocated, fullBuffer()));

        assertThrows(IndexOutOfBoundsException.class, terminal::sendPosition);

        assertEquals(0, allocated.get().refCnt());
    }

    @Test
    void releasesPositionBodyAfterSuccessfulSend() throws Exception {
        AtomicReference<ByteBuf> allocated = new AtomicReference<>();
        try (ServerSocket platform = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
                SimulatedTerminal terminal = terminal(
                        ProtocolVersion.JT808_2013,
                        capacity -> capture(allocated, Unpooled.buffer(capacity)))) {
            terminal.connect(new InetSocketAddress(
                    InetAddress.getLoopbackAddress(), platform.getLocalPort()));

            assertEquals(1, terminal.sendPosition());
            assertEquals(0, allocated.get().refCnt());
        }
    }

    @Test
    void releasesPositionBodyBeforeDisconnectedWriteFailureEscapes() {
        AtomicReference<ByteBuf> allocated = new AtomicReference<>();
        SimulatedTerminal terminal = terminal(
                ProtocolVersion.JT808_2013,
                capacity -> capture(allocated, Unpooled.buffer(capacity)));

        assertThrows(IllegalStateException.class, terminal::sendPosition);

        assertEquals(0, allocated.get().refCnt());
    }

    private static void assertRegistrationOverflowReleases(
            String manufacturerId, String model, String terminalCode) {
        AtomicReference<ByteBuf> allocated = new AtomicReference<>();
        SimulatedTerminal terminal = new SimulatedTerminal(
                "000000000901",
                ProtocolVersion.JT808_2013,
                "SYNTHETIC-VEHICLE",
                manufacturerId,
                model,
                terminalCode,
                capacity -> capture(allocated, Unpooled.buffer(128)));

        assertThrows(IllegalArgumentException.class, terminal::sendRegistration);
        assertEquals(0, allocated.get().refCnt());
    }

    private static SimulatedTerminal terminal(
            ProtocolVersion protocolVersion,
            java.util.function.IntFunction<ByteBuf> allocator) {
        return new SimulatedTerminal(
                "000000000902",
                protocolVersion,
                "SYNTHETIC-VEHICLE",
                "SIMMF",
                "SIM-MODEL",
                "SIM0001",
                allocator);
    }

    private static ByteBuf capture(AtomicReference<ByteBuf> allocated, ByteBuf body) {
        allocated.set(body);
        return body;
    }

    private static ByteBuf fullBuffer() {
        ByteBuf body = Unpooled.buffer(1, 1);
        body.writerIndex(1);
        return body;
    }
}
